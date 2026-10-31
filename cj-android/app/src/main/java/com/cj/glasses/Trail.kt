package com.cj.glasses

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A short diary of what CJ's voice and camera did (woke, heard a goodbye, camera attempt 2 failed: …), kept in
 * the app's own files so it survives Android's log rotation. Read it with:
 *   adb shell run-as com.cj.glasses cat files/cj-trail.log
 * Holds the last ~200 KB; the older half is dropped when it grows past that.
 */
object Trail {
    private const val MAX_BYTES = 200_000L
    private var file: File? = null
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        file = File(context.filesDir, "cj-trail.log")
    }

    @Synchronized
    fun log(message: String) {
        Log.i("CJ", message)
        val f = file ?: return
        runCatching {
            if (f.length() > MAX_BYTES) f.writeText(f.readText().takeLast((MAX_BYTES / 2).toInt()))
            f.appendText("${time.format(Date())}  $message\n")
        }
    }
}
