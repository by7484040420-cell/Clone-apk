package com.nextvm.core.services.gms

import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import timber.log.Timber
import java.util.concurrent.Executor

/**
 * GMS bind helper.
 *
 * 1) MAIN thread se bind: skip (turant fail -> null). Wait karne par deadlock + ANR hota tha.
 * 2) BACKGROUND thread se bind: callback (onServiceConnected) ab ALAG thread par aata hai.
 *    Pehle callback main thread par aata tha, aur Porter ka main thread kai second busy rehta hai
 *    (translations load), isliye GMS connect hone me 8s se zyada lag jata tha, timeout ho jata tha
 *    aur GoogleApiClient / Firebase / Location fail ho jate the (report me GAC_Executor,
 *    GoogleApiHandler, Firebase-Messaging-Init sab waitForBinder par atke the).
 */
internal object GmsBindUtil {
    private const val TAG = "GmsBindUtil"

    private val bindThread: HandlerThread by lazy {
        HandlerThread("nextvm-gms-bind").apply { start() }
    }
    private val bindExecutor = Executor { r -> Handler(bindThread.looper).post(r) }

    fun bind(context: Context, intent: Intent, conn: ServiceConnection): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Timber.tag(TAG).w("Main thread par GMS bind skip: ${intent.action ?: intent.component}")
            return false
        }
        return if (Build.VERSION.SDK_INT >= 29) {
            context.bindService(intent, Context.BIND_AUTO_CREATE, bindExecutor, conn)
        } else {
            context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        }
    }

    fun safeTimeout(requestedMs: Long): Long =
        if (Looper.myLooper() == Looper.getMainLooper()) 0L else requestedMs
}
