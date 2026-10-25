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
                // A background thread of a CLONE process failed only because the guest package
                // name does not match the host UID ("Package X does not belong to UID").
                // Let just that thread die; do NOT kill the whole clone.
                val benign = isCloneProcess() &&
                    thread !== android.os.Looper.getMainLooper().thread &&
                    isPackageUidMismatch(throwable)
                if (!benign) {
                    previous?.uncaughtException(thread, throwable)
                }
            }
        )
    }

    private fun isCloneProcess(): Boolean {
        return try {
            Build.VERSION.SDK_INT >= 28 && (Application.getProcessName() ?: "").contains(":p")
        } catch (_: Throwable) {
            false
        }
    }

    private fun isPackageUidMismatch(t: Throwable): Boolean {
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 8) {
            if (cur is SecurityException) {
                val m = cur.message ?: ""
                if (m.contains("does not belong to") || m.contains("not owned by")) return true
            }
            cur = cur.cause
            depth++
        }
        return false
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

    // ------------------------------------------------------------------
    // Diagnostics: WHY did a clone process die? (shown once after it happens)
    // ------------------------------------------------------------------

    private fun reasonName(r: Int): String = when (r) {
        1 -> "EXIT_SELF (app closed itself: System.exit / killProcess / finish)"
        2 -> "SIGNALED (killed by signal)"
        3 -> "LOW_MEMORY"
        4 -> "CRASH (Java exception)"
        5 -> "CRASH_NATIVE (native crash)"
        6 -> "ANR (app not responding)"
        7 -> "INITIALIZATION_FAILURE"
        8 -> "PERMISSION_CHANGE"
        9 -> "EXCESSIVE_RESOURCE_USAGE"
        10 -> "USER_REQUESTED"
        11 -> "USER_STOPPED"
        12 -> "DEPENDENCY_DIED"
        13 -> "OTHER"
        14 -> "FREEZER"
        else -> "UNKNOWN($r)"
    }

    /**
     * Returns a report if a clone process (:p0..:p9) died since the last check
     * (or a Java crash file exists). Null when there is nothing new. Call off the main thread.
     */
    fun collectDiagnostics(ctx: Context): String? {
        val sb = StringBuilder()
        var found = false

        consume(ctx)?.let {
            found = true
            sb.append("== JAVA CRASH ==\n").append(it).append("\n\n")
        }

        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                val prefs = ctx.getSharedPreferences("nextvm_diag", Context.MODE_PRIVATE)
                val lastSeen = prefs.getLong("last_exit_ts", 0L)
                val exits = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 10)
                    .filter { (it.processName ?: "").contains(":p") && it.timestamp > lastSeen }
                if (exits.isNotEmpty()) {
                    found = true
                    prefs.edit().putLong("last_exit_ts", exits.maxOf { it.timestamp }).apply()
                    sb.append("== CLONE PROCESS EXIT ==\n")
                    val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
                    for (e in exits.take(3)) {
                        sb.append("process=").append(e.processName).append('\n')
                        sb.append("reason=").append(reasonName(e.reason)).append('\n')
                        sb.append("status=").append(e.status).append('\n')
                        sb.append("description=").append(e.description).append('\n')
                        sb.append("time=").append(fmt.format(Date(e.timestamp))).append("\n\n")
                    }
                }
            } catch (_: Throwable) {
                // ignore
            }
        }

        AnrWatchdog.consume(ctx)?.let {
            found = true
            sb.append("== MAIN THREAD STUCK (ANR WATCH) ==\n").append(it).append("\n\n")
        }
        NetDiag.consume(ctx)?.let {
            found = true
            sb.append("== NETWORK TEST ==\n").append(it).append("\n\n")
        }

        if (!found) return null

        // Tail of this app's own log (errors + our hook messages)
        try {
            val proc = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "3000")
                .redirectErrorStream(true)
                .start()
            val text = proc.inputStream.bufferedReader().readText()
            val keep = text.lines().filter { l ->
                l.contains(" E ") || l.contains(" F ") || l.contains("Fatal signal") ||
                    l.contains("killProcess") || l.contains("System.exit") ||
                    l.contains("Blocked") || l.contains("NEXTVM") || l.contains("ATHook")
            }.takeLast(60)
            if (keep.isNotEmpty()) {
                sb.append("== LOG (last errors) ==\n").append(keep.joinToString("\n").takeLast(2_500))
            }
        } catch (_: Throwable) {
            // ignore
        }

        return sb.toString().take(10_000)
    }
}
