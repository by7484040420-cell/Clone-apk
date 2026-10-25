package com.nextvm.app

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Har process (host + clone :p0..:p9) me chalta hai.
 * Main thread agar 2 second se zyada atka rahe, to turant main thread ka STACK TRACE
 * file me save kar deta hai. Isse pata chalta hai ki ANR/"app khul kar back" ke time
 * main thread KIS code me atka tha. Report app me dikhti hai (CrashReporter ke zariye).
 */
object AnrWatchdog {

    private const val FILE_NAME = "nextvm_anr_watch.txt"
    private const val STALL_MS = 2000L
    private const val MAX_CHARS = 4_500

    @Volatile private var installed = false

    fun install(app: Application) {
        if (installed) return
        installed = true
        val main = Handler(Looper.getMainLooper())
        val proc = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else "unknown"

        Thread({
            while (true) {
                val tick = intArrayOf(0)
                main.post { tick[0] = 1 }
                try { Thread.sleep(STALL_MS) } catch (_: InterruptedException) { return@Thread }
                if (tick[0] == 0) {
                    // Main thread 2s se bandh hai -> dump
                    dump(app, proc, "main thread ${STALL_MS}ms se atka hai")
                    // Ek aur dump 2s baad (agar ab bhi atka ho) taaki sahi jagah pakdi jaye
                    try { Thread.sleep(STALL_MS) } catch (_: InterruptedException) { return@Thread }
                    if (tick[0] == 0) dump(app, proc, "main thread ab bhi atka hai (4s+)")
                    // jab tak free na ho, wait
                    var guard = 0
                    while (tick[0] == 0 && guard++ < 60) {
                        try { Thread.sleep(500) } catch (_: InterruptedException) { return@Thread }
                    }
                }
            }
        }, "nextvm-anr-watchdog").apply { isDaemon = true; start() }
    }

    private fun dump(ctx: Context, proc: String, why: String) {
        try {
            val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            val sb = StringBuilder()
            sb.append("[$time] process=$proc  $why\n")
            val mainThread = Looper.getMainLooper().thread
            sb.append("MAIN STACK:\n")
            mainThread.stackTrace.take(30).forEach { sb.append("  at ").append(it).append('\n') }

            // Main thread kisi lock/binder ka wait kar raha ho to doosre BLOCKED threads bhi dikhao
            Thread.getAllStackTraces().entries
                .filter { it.key != mainThread && it.key.name != "nextvm-anr-watchdog" }
                .filter { e -> e.value.any { f -> f.className.startsWith("com.nextvm") || f.className.startsWith("com.theporter") } }
                .take(3)
                .forEach { (t, st) ->
                    sb.append("THREAD ${t.name} (${t.state}):\n")
                    st.take(6).forEach { sb.append("  at ").append(it).append('\n') }
                }
            sb.append('\n')

            val f = File(ctx.filesDir, FILE_NAME)
            val old = if (f.exists()) f.readText() else ""
            f.writeText((old + sb.toString()).takeLast(MAX_CHARS))
        } catch (_: Throwable) {
        }
    }

    /** Saved ANR-watch report ek baar do aur delete kar do. */
    fun consume(ctx: Context): String? {
        val f = File(ctx.filesDir, FILE_NAME)
        if (!f.exists()) return null
        return try { f.readText().also { f.delete() } } catch (_: Throwable) { null }
    }
}
