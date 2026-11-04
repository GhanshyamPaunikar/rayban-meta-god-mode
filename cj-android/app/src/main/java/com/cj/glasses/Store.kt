package com.cj.glasses

import android.content.Context
import org.json.JSONObject

/** Remembers which brain this phone is paired with. App-private storage; Android Keystore encryption comes later. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("cj", Context.MODE_PRIVATE)

    fun pairing(): Pairing? {
        val url = prefs.getString("url", null) ?: return null
        val token = prefs.getString("token", null) ?: return null
        return Pairing(url, token)
    }

    fun save(p: Pairing) {
        prefs.edit().putString("url", p.url.trim()).putString("token", p.token.trim()).apply()
    }

    /** Keep listening for "CJ" while music plays, at the cost of call-quality music. Off by default. */
    var wakeDuringMusic: Boolean
        get() = prefs.getBoolean("wake_during_music", false)
        set(value) = prefs.edit().putBoolean("wake_during_music", value).apply()

    /** Visual memory: the glasses take a photo every few minutes and the brain keeps a short note. Off by default. */
    var visualMemory: Boolean
        get() = prefs.getBoolean("visual_memory", false)
        set(value) = prefs.edit().putBoolean("visual_memory", value).apply()

    /** Real-time voice (Gemini Live): answers start in under a second and you can interrupt. On by default. */
    var liveVoice: Boolean
        get() = prefs.getBoolean("live_voice", true)
        set(value) = prefs.edit().putBoolean("live_voice", value).apply()

    /** True once the first-launch permission screens are finished (or skipped through). */
    var onboarded: Boolean
        get() = prefs.getBoolean("onboarded", false)
        set(value) = prefs.edit().putBoolean("onboarded", value).apply()

    // Setup → Accessibility. Each is off until switched on; the brain turns them into instructions (cj/access.py).
    var readAloud: Boolean
        get() = prefs.getBoolean("read_aloud", false)
        set(value) = prefs.edit().putBoolean("read_aloud", value).apply()

    var moneyReader: Boolean
        get() = prefs.getBoolean("money", false)
        set(value) = prefs.edit().putBoolean("money", value).apply()

    var describeSurroundings: Boolean
        get() = prefs.getBoolean("describe", false)
        set(value) = prefs.edit().putBoolean("describe", value).apply()

    var medicineLabels: Boolean
        get() = prefs.getBoolean("medicine", false)
        set(value) = prefs.edit().putBoolean("medicine", value).apply()

    /** Waits longer before deciding you've finished talking. */
    var slowSpeech: Boolean
        get() = prefs.getBoolean("slow_speech", false)
        set(value) = prefs.edit().putBoolean("slow_speech", value).apply()

    /** Always says what it's about to do and waits for yes before calling, messaging, paying or ordering. */
    var confirmFirst: Boolean
        get() = prefs.getBoolean("confirm", false)
        set(value) = prefs.edit().putBoolean("confirm", value).apply()

    /** How fast CJ's voice plays, 0.75 to 1.5. */
    var speechSpeed: Float
        get() = prefs.getFloat("speech_speed", 1f)
        set(value) = prefs.edit().putFloat("speech_speed", value).apply()

    /** "short", "normal" or "detailed". */
    var detail: String
        get() = prefs.getString("detail", "normal") ?: "normal"
        set(value) = prefs.edit().putString("detail", value).apply()

    /** SOS: who to text and call when you say "CJ, help" (see Sos.kt). */
    var sosOn: Boolean
        get() = prefs.getBoolean("sos", false)
        set(value) = prefs.edit().putBoolean("sos", value).apply()

    var sosName: String
        get() = prefs.getString("sos_name", "") ?: ""
        set(value) = prefs.edit().putString("sos_name", value).apply()

    var sosNumber: String
        get() = prefs.getString("sos_number", "") ?: ""
        set(value) = prefs.edit().putString("sos_number", value).apply()

    /** Text them a map link to where you are. On by default. */
    var sosText: Boolean
        get() = prefs.getBoolean("sos_text", true)
        set(value) = prefs.edit().putBoolean("sos_text", value).apply()

    /** Then call them. On by default. */
    var sosCall: Boolean
        get() = prefs.getBoolean("sos_call", true)
        set(value) = prefs.edit().putBoolean("sos_call", value).apply()

    /** Sent with every question and Live session. */
    fun access(): JSONObject = JSONObject()
        .put("read_aloud", readAloud).put("money", moneyReader).put("describe", describeSurroundings)
        .put("medicine", medicineLabels).put("slow_speech", slowSpeech).put("confirm", confirmFirst).put("detail", detail)

    fun clear() {
        prefs.edit().clear().apply()
    }
}
