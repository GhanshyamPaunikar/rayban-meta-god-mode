package com.cj.glasses

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telephony.SmsManager
import android.util.Log
import androidx.activity.result.contract.ActivityResultContract
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/**
 * SOS (Setup → Accessibility): "CJ, help", the SOS button in the app or the one in the notification. After a
 * five-second countdown you can cancel, CJ texts your chosen contact a map link to where you are, then calls them.
 * Runs on the phone alone, so it works when the brain on your laptop can't be reached.
 */
object Sos {
    private const val TAG = "Sos"

    /** The whole of what was said is a call for help: "help", "help me", "SOS", "emergency", "bachao", "madad karo". */
    private val PHRASE = Regex("""^(please |cj |hey cj )*(help( me)?|sos|s o s|emergency|bachao|bachao mujhe|madad( karo)?|""" +
        """help karo|बचाओ|मदद( करो)?)( please| jaldi| now)*$""")

    fun wanted(text: String): Boolean = Core.store.sosOn && isHelp(text)

    /** Words of real distress. Someone in trouble says "help, help me, I'm dying", not a tidy "help". */
    private val DISTRESS = Regex("""\b(i'?m dying(?! (to|of laugh))|i am dying(?! (to|of laugh))|i( have|'?ve)? fallen|i fell (down|over|and)|""" +
        """can'?t breathe|cannot breathe|having a heart attack|call (an |the )?ambulance|i'?m (hurt|bleeding|injured)|""" +
        """someone help|somebody help|bachao|madad karo|बचाओ)\b""")

    /**
     * A call for help: the whole sentence is one ("help", "SOS", "bachao"), "help" said more than once, or words of
     * distress. "Help me write an email" isn't: the five-second countdown catches anything else that slips through.
     */
    fun isHelp(text: String): Boolean {
        val clean = text.lowercase().replace("’", "'").replace(Regex("""[^\p{L}\p{M}\s']"""), " ").trim()
            .replace(Regex("\\s+"), " ")
        if (PHRASE.matches(clean.replace("'", ""))) return true
        if (Regex("""\bhelp\b""").findAll(clean).count() >= 2) return true
        return DISTRESS.containsMatchIn(clean)
    }

    fun ready(): Boolean = Core.store.sosNumber.isNotBlank()

    /** Texts the contact where you are. Returns what to tell you: what was sent, or what went wrong. */
    suspend fun sendText(context: Context): String {
        val name = Core.store.sosName.ifBlank { "your contact" }
        if (!Core.store.sosText) return ""
        if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED)
            return "I couldn't text $name: CJ isn't allowed to send texts."
        val place = freshPlace(context)
        val message = buildString {
            append("SOS from CJ: I need help.")
            if (place != null) {
                append(" I'm here: https://maps.google.com/?q=%.6f,%.6f".format(Locale.US, place.lat, place.lon))
                place.accuracy?.let { append(" (within about ${it.toInt()} m)") }
            } else {
                append(" My location isn't available.")
            }
            append(" Sent at ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())}.")
        }
        return try {
            val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getSystemService(SmsManager::class.java)
                      else @Suppress("DEPRECATION") SmsManager.getDefault()
            sms.sendMultipartTextMessage(Core.store.sosNumber, null, sms.divideMessage(message), null, null)
            Trail.log("sos: text sent (location ${if (place != null) "included" else "unavailable"})")
            if (place != null) "I've texted $name where you are." else "I've texted $name, but I couldn't find your location."
        } catch (e: Exception) {
            Log.w(TAG, "SOS text failed", e)
            "I couldn't text $name."
        }
    }

    /** A new location fix (up to 6 s), else the last one known. */
    @SuppressLint("MissingPermission")
    private suspend fun freshPlace(context: Context): Place? {
        val allowed = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (allowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val lm = context.getSystemService(LocationManager::class.java)
            val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
            if (provider != null) {
                val fix = withTimeoutOrNull(6_000) {
                    suspendCancellableCoroutine { cont ->
                        val cancel = android.os.CancellationSignal()
                        cont.invokeOnCancellation { cancel.cancel() }
                        lm.getCurrentLocation(provider, cancel, context.mainExecutor) { cont.resume(it) }
                    }
                }
                if (fix != null) return Place(fix.latitude, fix.longitude, if (fix.hasAccuracy()) fix.accuracy else null)
            }
        }
        return Core.place()
    }
}

/** Picks one phone number from your contacts. Android shares only that number, so CJ needs no contacts permission. */
class PickPhoneNumber : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: Context, input: Unit) =
        Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent?.data?.takeIf { resultCode == android.app.Activity.RESULT_OK }
}

/** The name and number of the contact picked with [PickPhoneNumber]. */
fun pickedContact(context: Context, uri: Uri?): Pair<String, String>? {
    uri ?: return null
    val columns = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
    return runCatching {
        context.contentResolver.query(uri, columns, null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0).orEmpty() to c.getString(1).orEmpty() else null
        }
    }.getOrNull()
}
