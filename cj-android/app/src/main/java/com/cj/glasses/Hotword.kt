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
 * "city", "c e o", "jay"…) so everyday speech matches those instead. The name only counts at the start of what
 * was said or right after "hey" / "okay": "did you see Jake" decodes as "you c j" and is ignored. Keep GRAMMAR
 * and [called] in sync with tools/wake_test.py (51/51 on synthetic clips; the previous rule scored 47/51).
 */
class Hotword(private val context: Context) {
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
                recognizer.setWords(true)  // settled results then carry each word's confidence
                var last = ""
                while (isActive) {
                    val frame = frames.receive()
                    val final = recognizer.acceptWaveForm(frame, frame.size)
                    // Partials let CJ wake the moment you say its name; settled results add a confidence check.
                    val words = if (final) settled(recognizer.result)
                                else JSONObject(recognizer.partialResult).optString("partial")
                                    .split(" ").filter { it.isNotEmpty() }.map { Heard(it, null) }
                    val heard = words.joinToString(" ") { it.word }
                    // Words only (no audio), so a missed wake word can be diagnosed from the log.
                    if (heard.isNotBlank() && heard != last) Log.d("Hotword", "heard: $heard")
                    last = heard
                    if (called(words)) {
                        Trail.log("wake: \"$heard\"" + if (final) " (settled)" else "")
                        return@withContext
                    }
                }
            }
        }
    }

    companion object {
        private fun settled(json: String): List<Heard> {
            val result = JSONObject(json).optJSONArray("result") ?: return emptyList()
            return (0 until result.length()).map { result.getJSONObject(it) }
                .map { Heard(it.optString("word"), it.optDouble("conf", 0.0)) }
        }

        private val GRAMMAR = JSONArray(listOf("see jay", "c j", "see", "jay", "she", "sea", "city", "okay", "see you",
            "say", "j", "g", "hey", "jai", "c e o", "the", "joined", "jake", "seat", "[unk]")).toString()
    }
}

/** One recognised word; [confidence] is known only in settled results. */
data class Heard(val word: String, val confidence: Double?)

private val PAIRS = setOf("see" to "jay", "c" to "j")
private val CALL = setOf("hey", "okay")  // words that may come right before the name
private const val MIN_CONFIDENCE = 0.4    // the weakest real "CJ" in tools/wake_test.py scored 0.49

/** "CJ" at the start of what was said or right after "hey" / "okay", and (when known) said clearly. */
fun called(words: List<Heard>): Boolean = words.indices.any { i ->
    val a = words[i]
    val b = words.getOrNull(i + 1) ?: return@any false
    val sure = a.confidence == null || minOf(a.confidence, b.confidence ?: 0.0) >= MIN_CONFIDENCE
    (a.word to b.word) in PAIRS && sure && (i == 0 || words[i - 1].word in CALL)
}
