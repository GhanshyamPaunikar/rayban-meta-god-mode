package com.cj.glasses

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * One microphone stream for the whole hands-free session: the glasses when they're connected, else the
 * phone. The wake word and question recording both read from it. Opening and closing the mic every turn
 * made the glasses switch between call and music audio, which clipped speech and replies.
 */
class MicStream(private val mic: GlassesMic) {
    /** 128 ms frames of 16 kHz mono PCM. Old frames are dropped if nobody is reading. */
    val frames = Channel<ShortArray>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Typical loudness of the room, tracked continuously; speech detection is relative to it. */
    @Volatile var noiseFloor = 200.0
        private set

    @SuppressLint("MissingPermission")  // RECORD_AUDIO is granted before the service starts
    suspend fun run() = withContext(Dispatchers.IO) {
        val minBuffer = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, FRAME * 4))
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IOException("The microphone is busy or not allowed.")
        }
        mic.inputDevice()?.let { record.preferredDevice = it }
        try {
            record.startRecording()
            while (isActive) {
                val frame = ShortArray(FRAME)
                val n = record.read(frame, 0, FRAME)
                if (n <= 0) continue
                val level = rms(frame, n)
                // Falls fast to quiet, rises slowly, so speech barely moves it.
                noiseFloor = if (level < noiseFloor) 0.7 * noiseFloor + 0.3 * level else 0.995 * noiseFloor + 0.005 * level
                frames.trySend(if (n == FRAME) frame else frame.copyOf(n))
            }
        } finally {
            record.stop()
            record.release()
        }
    }

    /** Throws away buffered audio, e.g. CJ's own reply picked up while it was speaking. */
    fun drain() {
        while (frames.tryReceive().isSuccess) Unit
    }

    /**
     * Records one question: waits up to [waitForSpeechMs] for speech, then stops after 0.8 s of quiet
     * (or 15 s). Returns a WAV clip, or null when nobody spoke.
     */
    suspend fun recordQuestion(waitForSpeechMs: Int): ByteArray? {
        val pcm = ByteArrayOutputStream()
        val start = maxOf(noiseFloor * 3, 250.0)
        val keep = maxOf(noiseFloor * 1.8, 180.0)
        var speaking = false
        var quietMs = 0
        var loudMs = 0
        var elapsedMs = 0
        while (elapsedMs < MAX_MS) {
            val frame = frames.receive()
            val ms = frame.size * 1000 / RATE
            elapsedMs += ms
            pcm.write(toBytes(frame))
            val level = rms(frame, frame.size)
            if (level > start) loudMs += ms
            if (!speaking) {
                if (level > start) speaking = true
                else if (elapsedMs > waitForSpeechMs) return null
            } else {
                quietMs = if (level < keep) quietMs + ms else 0
                if (quietMs >= END_SILENCE_MS) break
            }
        }
        if (loudMs < MIN_SPEECH_MS) {
            Log.d(TAG, "ignored ${loudMs} ms of sound: too short to be speech")
            return null  // noise: don't send it to Gemini
        }
        Log.d(TAG, "question ${elapsedMs} ms, floor ${noiseFloor.toInt()}, start ${start.toInt()}")
        return wav(pcm.toByteArray())
    }

    companion object {
        const val RATE = 16000
        private const val FRAME = 2048
        private const val MAX_MS = 15_000
        private const val END_SILENCE_MS = 1_100  // 0.8 s cut people off mid-thought; 1.4 s felt slow
        private const val MIN_SPEECH_MS = 350  // shorter bursts are clicks and rustles, not words
        private const val TAG = "MicStream"

        private fun rms(frame: ShortArray, n: Int): Double {
            var sum = 0.0
            for (i in 0 until n) sum += frame[i].toDouble() * frame[i]
            return sqrt(sum / maxOf(n, 1))
        }

        private fun toBytes(frame: ShortArray): ByteArray =
            ByteBuffer.allocate(frame.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { asShortBuffer().put(frame) }.array()

        /** 16 kHz mono 16-bit PCM in a WAV container, which Gemini accepts as audio/wav. */
        private fun wav(pcm: ByteArray): ByteArray = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(RATE); putInt(RATE * 2)
            putShort(2); putShort(16)
            put("data".toByteArray()); putInt(pcm.size); put(pcm)
        }.array()
    }
}
