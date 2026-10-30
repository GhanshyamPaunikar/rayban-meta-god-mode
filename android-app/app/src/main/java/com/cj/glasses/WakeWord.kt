package com.cj.glasses

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Offline "CJ" (or "hey CJ") detection with Vosk and its small Indian-English model in assets/model-en-in
 * (fetched by scripts/get-wake-model.sh). Nothing leaves the phone until the wake word is heard.
 *
 * To Vosk, "CJ" is the words "see jay" / "c j". The grammar lists decoys made of the same sounds ("see you",
 * "city", "c e o", "jay"…) so everyday speech matches those instead. Keep GRAMMAR and WAKE in sync with
 * tools/wake_test.py (47/51 on synthetic clips; the weak spot is "did you see Jake").
 */
class WakeWord(private val context: Context) {
    private var model: Model? = null

    private suspend fun load(): Model = model ?: suspendCancellableCoroutine { cont ->
        StorageService.unpack(context, "model-en-in", "model",
            { m -> model = m; cont.resume(m) },
            { e -> cont.resumeWithException(e) })
    }

    /** Reads [frames] from the shared mic stream until "CJ" is heard. Cancel to stop listening. */
    suspend fun await(frames: ReceiveChannel<ShortArray>) {
        val model = load()
        withContext(Dispatchers.Default) {
            Recognizer(model, MicStream.RATE.toFloat(), GRAMMAR).use { recognizer ->
                var last = ""
                while (isActive) {
                    val frame = frames.receive()
                    val final = recognizer.acceptWaveForm(frame, frame.size)
                    val heard = if (final) JSONObject(recognizer.result).optString("text")
                                else JSONObject(recognizer.partialResult).optString("partial")
                    // Words only (no audio), so a missed wake word can be diagnosed from the log.
                    if (heard.isNotBlank() && heard != last) Log.d("WakeWord", "heard: $heard")
                    last = heard
                    // Final results: the wake word anywhere ("hey c j"). Partials: only as the latest words, so a
                    // phrase still being decoded ("see jay…" -> "see jake") can't fire early.
                    val words = heard.trim().split(" ").filter { it.isNotEmpty() }
                    if (if (final) WAKE_RE.containsMatchIn(heard) else words.takeLast(2).joinToString(" ") in WAKE) return@withContext
                }
            }
        }
    }

    companion object {
        private val WAKE = setOf("see jay", "c j")
        private val WAKE_RE = Regex("""(^|\s)(see jay|c j)(\s|$)""")
        private val GRAMMAR = JSONArray(listOf("see jay", "c j", "see", "jay", "she", "sea", "city", "okay", "see you",
            "say", "j", "g", "hey", "jai", "c e o", "the", "joined", "jake", "seat", "[unk]")).toString()
    }
}
