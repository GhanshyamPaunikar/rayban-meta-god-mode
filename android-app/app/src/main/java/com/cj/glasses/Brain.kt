package com.cj.glasses

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class Pairing(val url: String, val token: String)
data class Turn(val role: String, val text: String)
data class Timer(val seconds: Int, val label: String)
data class Answer(val spoken: String, val details: String, val language: String, val voice: ByteArray? = null,
                  val timer: Timer? = null)
data class SpokenAnswer(val heard: String, val needsPhoto: Boolean, val spoken: String, val details: String,
                        val voice: ByteArray? = null, val timer: Timer? = null)

open class BrainException(message: String) : Exception(message)

/** The brain didn't answer at all (wrong address, Mac asleep, other Wi-Fi), as opposed to answering with an error. */
class BrainUnreachable(message: String) : BrainException(message)

/** Talks to the CJ brain server on the laptop. Every call sends the pairing token. */
class BrainClient(private val pairing: Pairing) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private suspend fun call(method: String, path: String, body: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(pairing.url.trimEnd('/') + path)
                .header("Authorization", "Bearer ${pairing.token}")
                .method(method, body?.toString()?.toRequestBody(jsonType))
                .build()
            try {
                http.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    when {
                        response.code == 401 -> throw BrainException("The brain rejected the pairing token. Pair again in Devices.")
                        !response.isSuccessful -> throw BrainException("Brain error ${response.code}: ${text.take(160)}")
                        else -> JSONObject(text)
                    }
                }
            } catch (e: IOException) {
                throw BrainUnreachable("Can't reach the brain at ${pairing.url}. Is it running, and are you on the same Wi-Fi?")
            }
        }

    suspend fun health(): Map<String, Boolean> {
        val providers = call("GET", "/health").getJSONObject("providers")
        return providers.keys().asSequence().associateWith { providers.getBoolean(it) }
    }

    suspend fun ask(text: String, history: List<Turn>, imageJpegBase64: String? = null): Answer {
        val past = JSONArray().apply {
            history.takeLast(8).forEach { put(JSONObject().put("role", it.role).put("text", it.text)) }
        }
        val body = JSONObject().put("text", text).put("history", past).put("voice", true)
        if (imageJpegBase64 != null) body.put("image_b64", imageJpegBase64).put("mime", "image/jpeg")
        val r = call("POST", "/api/ask", body)
        return Answer(r.getString("spoken"), r.optString("details"), r.optString("language", "en"), voiceOf(r), timerOf(r))
    }

    /** A spoken question as a WAV clip; the brain transcribes it and says whether it must look first. */
    suspend fun askSpoken(wav: ByteArray, history: List<Turn>): SpokenAnswer {
        val past = JSONArray().apply {
            history.takeLast(8).forEach { put(JSONObject().put("role", it.role).put("text", it.text)) }
        }
        val r = call("POST", "/api/ask", JSONObject()
            .put("audio_b64", Base64.encodeToString(wav, Base64.NO_WRAP)).put("audio_mime", "audio/wav")
            .put("history", past).put("voice", true))
        return SpokenAnswer(r.optString("heard"), r.optBoolean("needs_photo"), r.optString("spoken"), r.optString("details"), voiceOf(r), timerOf(r))
    }

    /** The answer spoken by CJ's Azure voice, when the brain sent one. */
    private fun voiceOf(r: JSONObject): ByteArray? =
        r.optString("voice_b64").takeIf { it.isNotEmpty() }?.let { Base64.decode(it, Base64.DEFAULT) }

    private fun timerOf(r: JSONObject): Timer? =
        r.optJSONObject("timer")?.let { t -> Timer(t.optInt("seconds"), t.optString("label")).takeIf { it.seconds > 0 } }

    /** CJ's voice for something the phone says by itself (a timer going off). Null when Azure isn't set up. */
    suspend fun voiceFor(text: String): ByteArray? = voiceOf(call("POST", "/api/speak", JSONObject().put("text", text)))

}
