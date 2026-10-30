package com.cj.glasses

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class TimerItem(val label: String, val dueAt: Long)

/**
 * State shared by the app screens and the background service: one speaker, one glasses link, one
 * conversation, and the "ask CJ" pipeline both of them use. Touch it from the main thread.
 */
object Core {
    lateinit var store: Store
        private set
    lateinit var speaker: Speaker
        private set
    lateinit var glasses: Glasses
        private set

    val log = mutableStateListOf<Turn>()
    var details by mutableStateOf("")
    var photo by mutableStateOf<Snapshot?>(null)
    var status by mutableStateOf<String?>(null)
    /** True while the hands-free service is running. */
    var handsFree by mutableStateOf(false)

    /** Running timers ("remind me in 10 minutes…"), shown on the Talk screen. */
    val timers = mutableStateListOf<TimerItem>()

    private lateinit var app: Context
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var cached: Pair<Pairing, BrainClient>? = null
    private var pendingVoice: Pair<String, ByteArray>? = null

    /** Says [text] in CJ's Azure voice when the brain sent it with the answer, else with the phone's voice. */
    suspend fun speak(text: String) {
        val voice = pendingVoice
        pendingVoice = null
        if (voice != null && voice.first == text) speaker.play(voice.second) else speaker.say(text)
    }

    fun init(context: Context) {
        if (::store.isInitialized) return
        app = context.applicationContext
        store = Store(app)
        speaker = Speaker(app)
        glasses = Glasses(app)
    }

    fun brain(): BrainClient? {
        val pairing = store.pairing() ?: return null
        cached?.let { (p, client) -> if (p == pairing) return client }
        return BrainClient(pairing).also { cached = pairing to it }
    }

    /**
     * Runs [block] against the brain. If the saved address stopped answering (the Mac got a new Wi-Fi
     * address), finds the brain on the network, saves the new address and tries once more.
     */
    private suspend fun <T> withBrain(block: suspend (BrainClient) -> T): T {
        val brain = brain() ?: throw BrainException("Pair CJ with your laptop first, in the Setup tab.")
        return try {
            block(brain)
        } catch (e: BrainUnreachable) {
            val pairing = store.pairing() ?: throw e
            val found = Discovery.find(app) ?: throw e
            if (found == pairing.url.trimEnd('/')) throw e
            store.save(pairing.copy(url = found))
            block(brain() ?: throw e)
        }
    }

    /** Counts down on the phone, then says it in CJ's voice (through the glasses) and shows a notification. */
    fun startTimer(timer: Timer) {
        val item = TimerItem(timer.label, System.currentTimeMillis() + timer.seconds * 1000L)
        timers.add(item)
        appScope.launch {
            delay(timer.seconds * 1000L)
            timers.remove(item)
            val text = if (item.label.isBlank()) "Your timer is done." else "Time's up: ${item.label}."
            notifyTimer(text)
            val voice = runCatching { brain()?.voiceFor(text) }.getOrNull()
            if (voice != null) speaker.play(voice) else speaker.say(text)
        }
    }

    private fun notifyTimer(text: String) {
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("cj-timers", "CJ timers", NotificationManager.IMPORTANCE_HIGH))
        nm.notify((System.currentTimeMillis() % 100_000).toInt() + 100, Notification.Builder(app, "cj-timers")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("CJ")
            .setContentText(text)
            .setAutoCancel(true)
            .build())
    }

    fun glassesLinked(): Boolean =
        glasses.ready.value && glasses.registration.value == RegistrationState.REGISTERED

    /**
     * Asks CJ, optionally with a photo from the glasses first. Adds both turns to [log] and returns the
     * spoken answer. Throws [BrainException] or [GlassesException].
     */
    suspend fun ask(text: String, look: Boolean): String {
        if (brain() == null) throw BrainException("Pair CJ with your laptop first, in the Setup tab.")
        var shot: Snapshot? = null
        if (look) {
            status = "Looking through your glasses…"
            try {
                shot = glasses.takePhoto()
                photo = shot
            } finally {
                status = null
            }
        }
        val history = log.toList()
        log.add(Turn("user", if (shot != null) "[photo] $text" else text))
        val answer = withBrain { it.ask(text, history, shot?.jpegBase64) }
        log.add(Turn("assistant", answer.spoken))
        details = answer.details
        answer.timer?.let(::startTimer)
        pendingVoice = answer.voice?.let { answer.spoken to it }
        return answer.spoken
    }

    /**
     * A spoken question (WAV) from hands-free mode. The brain transcribes it; if it's a "what is this"
     * question CJ takes a photo with the glasses and asks again with it. Returns what was heard (empty when
     * nothing clear was said) and the answer to speak.
     */
    suspend fun askSpoken(wav: ByteArray): Pair<String, String> {
        status = "Thinking…"
        val history = log.toList()
        val first = withBrain { it.askSpoken(wav, history) }
        val heard = first.heard.trim()
        if (heard.isEmpty() || wantsStop(heard)) return heard to ""
        if (!first.needsPhoto) {
            log.add(Turn("user", heard))
            log.add(Turn("assistant", first.spoken))
            details = first.details
            pendingVoice = first.voice?.let { first.spoken to it }
            first.timer?.let(::startTimer)
            return heard to first.spoken
        }
        val cantLook = when {
            !glassesLinked() -> "I can't see right now. Link your glasses in CJ's Setup tab first."
            !glasses.hasCameraPermission() -> "To let me see, open CJ once and tap Look to allow the camera."
            else -> null
        }
        if (cantLook != null) {
            log.add(Turn("user", heard))
            log.add(Turn("assistant", cantLook))
            return heard to cantLook
        }
        return try {
            heard to ask(heard, look = true)
        } catch (e: GlassesException) {
            heard to (e.message ?: "I couldn't take a photo.")
        }
    }
}

private val LOOK_PHRASES = Regex(
    """what am i looking at|what('s| is) (this|that|it)|read (this|that|it)|look at|can you see|""" +
        """identify|tell me about this|\bye(h)? kya\b|kya hai (ye|yeh)|\bdekh|\bpadh|is(ka|ki|ko) |iska\b|""" +
        """ये क्या|यह क्या|देख|पढ़|इसका|इसको""",
    RegexOption.IGNORE_CASE,
)

/** Whether a spoken question needs a photo from the glasses ("what am I looking at", "ye kya hai"). */
fun wantsLook(text: String): Boolean = LOOK_PHRASES.containsMatchIn(text)

private val STOP_PHRASES = Regex(
    """\b(stop listening|go to sleep|turn off|band karo|so jao|chup ho ja)\b|बंद करो|सो जाओ""",
    RegexOption.IGNORE_CASE,
)

fun wantsStop(text: String): Boolean = STOP_PHRASES.containsMatchIn(text)

private val BYE_PHRASES = Regex(
    """\b(bye( bye)?( g)?|good ?bye|that'?s all|that is all|that'?s it|see you|talk later|chalo bye|ok bye|""" +
        """bas itna hi|bas itna|alvida|phir milte)\b|बाय|अलविदा|फिर मिलते""",
    RegexOption.IGNORE_CASE,
)

/**
 * "Bye", "that's all", "alvida": ends the conversation (CJ goes back to waiting for "CJ"). Only when it's
 * the point of what was said: a short phrase, or one ending with it. "How do you say bye in French?" doesn't count.
 */
fun wantsBye(text: String): Boolean {
    val clean = text.trim().trimEnd('.', '!', '?', '।').trim()
    val match = BYE_PHRASES.findAll(clean).lastOrNull() ?: return false
    return clean.split(Regex("\\s+")).size <= 6 || match.range.last >= clean.length - 1
}
