package com.cj.glasses

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/**
 * Hands-free CJ. Runs in the background with the screen off:
 * listen for "CJ" -> beep -> record the question -> the brain transcribes it and, for "what is this"
 * questions, CJ takes a photo with the glasses -> answer in the ear -> up to two follow-ups without the wake
 * word -> back to waiting.
 *
 * The glasses stay on their Bluetooth call link for the whole session and one mic stream feeds both the wake
 * word and recording, so nothing switches audio mid-conversation.
 */
class CJService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var mic: GlassesMic
    private lateinit var stream: MicStream
    private lateinit var wake: WakeWord
    private var loop: Job? = null
    private var streamJob: Job? = null
    @Volatile private var conversing = false
    @Volatile private var pausedForMusic = false
    private var focus: AudioFocusRequest? = null
    private val talkNow = Channel<Unit>(Channel.CONFLATED)
    private val beep by lazy { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Core.init(this)
        mic = GlassesMic(this)
        stream = MicStream(mic)
        wake = WakeWord(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "CJ hands-free", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = notification("Starting…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (intent?.action == ACTION_TALK) talkNow.trySend(Unit)
        if (loop == null) loop = scope.launch { run() }
        running = true
        Core.handsFree = true
        // Not sticky: Android only lets a service use the mic if it was started while you were using CJ.
        return START_NOT_STICKY
    }

    private suspend fun run() {
        Core.glasses.initialize()
        Core.speaker.setVoiceCall(true)
        if (mic.musicPlaying()) pausedForMusic = true else listenOnGlasses()
        scope.launch { watchAudio() }
        while (scope.isActive) {
            setStatus(idleStatus())
            stream.drain()
            if (!waitForWakeOrTap()) continue
            converse()
        }
    }

    private fun startStream() {
        streamJob?.cancel()
        streamJob = scope.launch {
            try {
                stream.run()
            } catch (e: CancellationException) {
                throw e  // CJ stopped the recorder itself; not a failure
            } catch (e: Exception) {
                Log.w(TAG, "microphone failed", e)
                setStatus("Microphone unavailable: ${e.message}")
            }
        }
    }

    /** Puts the glasses on their call link and (re)starts recording from them. */
    private suspend fun listenOnGlasses() {
        mic.route()
        delay(1_000)  // the Bluetooth call link takes a moment to come up
        startStream()  // a fresh recorder picks the glasses as its input
    }

    /**
     * Checks the audio every 2 s while CJ waits for "CJ":
     * - Music playing: the glasses can't do full-quality music and a call link at once, so CJ gives the link
     *   back to the music and pauses the wake word (tap Talk instead). It listens again when the music stops.
     * - Call link dropped (glasses folded or taken off, the camera session, another app): reconnect.
     *   Without this CJ kept recording from the phone's mic in your pocket and stopped hearing "CJ".
     */
    private suspend fun watchAudio() {
        var failedReconnects = 0
        while (scope.isActive) {
            // Back off after repeated failures: each attempt briefly forces the glasses into call audio, which
            // stutters any music. 62 attempts in 3 minutes did exactly that.
            delay(if (failedReconnects >= 3) 30_000 else 2_000)
            if (conversing) continue
            val music = mic.musicPlaying() && !Core.store.wakeDuringMusic
            when {
                music && !pausedForMusic -> {
                    pausedForMusic = true
                    streamJob?.cancel()
                    mic.release()
                    setStatus(idleStatus())
                }
                !music && pausedForMusic -> {
                    pausedForMusic = false
                    listenOnGlasses()
                    setStatus(idleStatus())
                }
                !pausedForMusic && mic.connected() && !mic.routed() -> {
                    listenOnGlasses()
                    failedReconnects = if (mic.routed()) 0 else failedReconnects + 1
                    Log.i(TAG, "glasses mic link lost; reconnect ${if (failedReconnects == 0) "ok" else "failed x$failedReconnects"}, music=$music")
                    setStatus(idleStatus())
                }
                else -> if (mic.routed() || pausedForMusic) failedReconnects = 0
            }
        }
    }

    private fun idleStatus(): String = when {
        pausedForMusic -> "Music playing: tap Talk to ask CJ"
        mic.routed() -> "Say \"CJ\""
        else -> "Glasses mic not connected: using the phone's mic"
    }

    /** True when "CJ" was heard or Talk was tapped in the notification. */
    private suspend fun waitForWakeOrTap(): Boolean = coroutineScope {
        val heard = async {
            try {
                wake.await(stream.frames)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "wake word failed", e)
                setStatus("Wake word unavailable: ${e.message}")
                delay(5_000)
                false
            }
        }
        val tapped = async { talkNow.receive(); true }
        val result = select { heard.onAwait { it }; tapped.onAwait { it } }
        heard.cancel()
        tapped.cancel()
        result
    }

    /** One conversation. Started during music (via Talk), it pauses the music first and resumes it after. */
    private suspend fun converse() {
        conversing = true
        val duringMusic = pausedForMusic
        try {
            if (duringMusic) {
                holdMusic(true)
                listenOnGlasses()
            }
            talk()
        } finally {
            if (duringMusic) {
                streamJob?.cancel()
                mic.release()
                holdMusic(false)  // Spotify resumes at full quality
            }
            conversing = false
        }
    }

    /** Asks music apps to pause (they resume when CJ lets go). */
    private fun holdMusic(hold: Boolean) {
        val audio = getSystemService(AudioManager::class.java)
        if (hold) {
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .build().also { audio.requestAudioFocus(it) }
        } else {
            focus?.let { audio.abandonAudioFocusRequest(it) }
            focus = null
        }
    }

    private suspend fun talk() {
        beep.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        // The conversation stays open, with the mic listening, until you say goodbye: no time limit.
        while (scope.isActive) {
            setStatus("Listening… say \"bye\" to finish")
            val wav = stream.recordQuestion(waitForSpeechMs = 30_000) ?: continue  // silence: keep waiting
            val (heard, spoken) = try {
                Core.askSpoken(wav)
            } catch (e: BrainException) {
                "?" to (e.message ?: "I couldn't reach my brain on the laptop.")
            }
            if (heard.isEmpty()) continue  // background noise, not speech: stay quiet and keep listening
            if (wantsStop(heard)) {
                say("Okay, CJ is going quiet.")
                stopSelf()
                return
            }
            if (wantsBye(heard)) {
                say(if (heard.any { it in 'ऀ'..'ॿ' } || Regex("(?i)chalo|alvida|bas|phir").containsMatchIn(heard)) "Theek hai, bye!" else "Bye!")
                return
            }
            say(spoken)
        }
    }

    /** Speaks, then drops whatever the mic picked up of CJ's own voice. */
    private suspend fun say(text: String) {
        setStatus("Speaking…")
        Core.speak(text)
        stream.drain()
    }

    private fun setStatus(text: String) {
        Core.status = text
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        fun action(action: String, code: Int) = PendingIntent.getService(this, code,
            Intent(this, CJService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("CJ is listening")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Talk", action(ACTION_TALK, 1)).build())
            .addAction(Notification.Action.Builder(null, "Stop", action(ACTION_STOP, 2)).build())
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        Core.speaker.setVoiceCall(false)
        mic.release()
        beep.release()
        running = false
        Core.handsFree = false
        Core.status = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CJService"
        private const val CHANNEL = "cj-hands-free"
        private const val NOTIFICATION_ID = 1
        const val ACTION_TALK = "com.cj.glasses.TALK"
        const val ACTION_STOP = "com.cj.glasses.STOP"

        @Volatile var running = false
            private set

        fun start(context: Context, talk: Boolean = false) {
            val intent = Intent(context, CJService::class.java)
            if (talk) intent.action = ACTION_TALK
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CJService::class.java).setAction(ACTION_STOP))
        }
    }
}
