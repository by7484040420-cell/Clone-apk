package com.nextvm.core.services.gms

import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Looper
import timber.log.Timber

/**
 * ANR FIX (safe version):
 * Android onServiceConnected() hamesha MAIN looper par aata hai. Agar guest app main thread se
 * bindService() kare aur hum latch.await() se main thread block karen, to callback kabhi nahi aata
 * (deadlock) -> 5s par ANR.
 *
 * Pehle ka behaviour: main thread par 8s block hoke aakhir me null milta tha.
 * Ab: main thread par bind hi nahi karte (turant "bind failed" -> null, bina block kiye).
 * Background thread par bilkul pehle jaisa normal bindService (main looper free hai, callback aata hai).
 * Matlab natija purane jaisa hi, bas 8 second ka freeze/ANR nahi.
 */
internal object GmsBindUtil {
    private const val TAG = "GmsBindUtil"

    fun bind(context: Context, intent: Intent, conn: ServiceConnection): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Timber.tag(TAG).w("Main thread par GMS bind skip (deadlock/ANR se bachne ke liye): ${intent.action ?: intent.component}")
            return false
        }
        return context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
    }

    fun safeTimeout(requestedMs: Long): Long =
        if (Looper.myLooper() == Looper.getMainLooper()) 0L else requestedMs
}
