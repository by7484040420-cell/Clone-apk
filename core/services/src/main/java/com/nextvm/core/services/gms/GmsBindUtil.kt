package com.nextvm.core.services.gms

import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import java.util.concurrent.Executor

/**
 * FIX (ANR): Android onServiceConnected() hamesha caller ke MAIN looper par aata hai.
 * Guest app jab main thread se bindService() karta tha, hum latch.await() se main thread ko
 * block kar dete the -> callback kabhi deliver nahi hota tha -> 8s tak wait -> 5s par ANR
 * ("Input dispatching timed out") -> process kill -> app khul kar wapas launcher par.
 *
 * Ab callback ek alag background thread par aata hai, to main thread wait kare tab bhi
 * connection turant complete ho jata hai.
 */
internal object GmsBindUtil {
    private val bindThread: HandlerThread by lazy {
        HandlerThread("nextvm-gms-bind").apply { start() }
    }
    private val bindExecutor = Executor { r -> Handler(bindThread.looper).post(r) }

    fun bind(context: Context, intent: Intent, conn: ServiceConnection): Boolean =
        if (Build.VERSION.SDK_INT >= 29) {
            context.bindService(intent, Context.BIND_AUTO_CREATE, bindExecutor, conn)
        } else {
            context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        }

    /** Main thread par kabhi 5s ANR limit ke paas na jao. */
    fun safeTimeout(requestedMs: Long): Long =
        if (Looper.myLooper() == Looper.getMainLooper()) minOf(requestedMs, 2500L) else requestedMs
}
