package com.nextvm.app

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves the last crash (from ANY process: host or a clone slot :p0..:p9) to a file,
 * so the real reason a clone closes can be shown inside the app next time it opens.
 */
object CrashReporter {

    private const val FILE_NAME = "nextvm_last_crash.txt"
    private const val MAX_CHARS = 12_000

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(
            Thread.UncaughtExceptionHandler { thread, throwable ->
                try {
                    write(app, thread, throwable)
                } catch (_: Throwable) {
                    // never let the reporter itself crash
                }
                previous?.uncaughtException(thread, throwable)
            }
        )
    }

    private fun write(ctx: Context, thread: Thread, t: Throwable) {
        val proc = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else "unknown"
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val text = "Time: $time\nProcess: $proc\nThread: ${thread.name}\n\n${Log.getStackTraceString(t)}"
        File(ctx.filesDir, FILE_NAME).writeText(text.take(MAX_CHARS))
    }

    /** Returns the saved crash report once, then deletes it. */
    fun consume(ctx: Context): String? {
        val f = File(ctx.filesDir, FILE_NAME)
        if (!f.exists()) return null
        return try {
            val text = f.readText()
            f.delete()
            text
        } catch (_: Throwable) {
            null
        }
    }
}
