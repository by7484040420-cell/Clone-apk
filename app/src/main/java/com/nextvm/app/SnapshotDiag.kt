package com.nextvm.app

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Looper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Resolving country" jaise gol-gol ghumne wale hang ka kaaran pakadne ke liye.
 *
 * Clone process shuru hone ke 20 aur 40 second baad, saare threads ka snapshot leta hai
 * (sirf wo threads jo kisi cheez ka wait kar rahe hain ya kaam kar rahe hain, idle pool threads nahi).
 * Report me "SNAPSHOT" section me dikhta hai. Isse pata chalega ki app KIS cheez ka wait kar rahi hai:
 *  - Binder call (system service jawab nahi de raha)
 *  - GmsBinderBridge (Google Play Services)
 *  - Network read, lock, ya Porter ka apna code
 */
object SnapshotDiag {
    private const val PREFIX = "nextvm_snap_"

    fun start(app: Application) {
        val proc = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() ?: "?" else "?"
        // sirf clone processes (:p0, :p1 ...) me
        if (!proc.contains(":p")) return

        Thread({
            val started = System.currentTimeMillis()
            val sb = StringBuilder()
            for (afterSec in listOf(20L, 40L)) {
                val wait = afterSec * 1000 - (System.currentTimeMillis() - started)
                if (wait > 0) try { Thread.sleep(wait) } catch (_: InterruptedException) { return@Thread }
                sb.append(snapshot(proc, afterSec))
                try {
                    File(app.filesDir, PREFIX + proc.replace(Regex("[^A-Za-z0-9]"), "_") + ".txt")
                        .writeText(sb.toString().takeLast(7_000))
                } catch (_: Throwable) {}
            }
        }, "nextvm-snapshot").apply { isDaemon = true; start() }
    }

    private fun isIdle(st: Array<StackTraceElement>): Boolean {
        if (st.isEmpty()) return true
        val top = st.take(6).joinToString("|") { "${it.className}.${it.methodName}" }
        return top.contains("MessageQueue.nativePollOnce") ||
            top.contains("ThreadPoolExecutor.getTask") ||
            top.contains("LinkedBlockingQueue.take") ||
            top.contains("DelayedWorkQueue.take") ||
            top.contains("ReferenceQueue.remove") ||
            top.contains("FinalizerDaemon") || top.contains("HeapTaskDaemon") ||
            top.contains("CoroutineScheduler.park") || top.contains("CoroutineScheduler\$Worker.park")
    }

    private fun snapshot(proc: String, afterSec: Long): String {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val sb = StringBuilder("[$t] process=$proc  SNAPSHOT +${afterSec}s\n")
        val mainThread = Looper.getMainLooper().thread
        sb.append("MAIN (${mainThread.state}):\n")
        mainThread.stackTrace.take(8).forEach { sb.append("  at ").append(it).append('\n') }

        var shown = 0
        Thread.getAllStackTraces().entries
            .filter { it.key != mainThread && !it.key.name.startsWith("nextvm-") }
            .filter { !isIdle(it.value) }
            .sortedBy { it.key.name }
            .forEach { (th, st) ->
                if (shown >= 10) return@forEach
                shown++
                sb.append("THREAD ${th.name} (${th.state}):\n")
                st.take(7).forEach { sb.append("  at ").append(it).append('\n') }
            }
        sb.append("(total threads=${Thread.getAllStackTraces().size}, shown=$shown)\n\n")
        return sb.toString()
    }

    fun consume(ctx: Context): String? {
        val files = ctx.filesDir.listFiles { f -> f.name.startsWith(PREFIX) } ?: return null
        if (files.isEmpty()) return null
        return try {
            files.sortedBy { it.name }.joinToString("\n") { f -> f.readText().also { f.delete() } }
        } catch (_: Throwable) { null }
    }
}
