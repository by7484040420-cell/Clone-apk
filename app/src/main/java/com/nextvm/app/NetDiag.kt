package com.nextvm.app

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Clone process me internet sach me chal raha hai ya nahi, ye check karta hai
 * ("Resolving country" par atakne ka kaaran). Result report me "NETWORK TEST" ke naam se aata hai.
 * Background thread me chalta hai, app ko slow nahi karta.
 */
object NetDiag {
    private const val FILE_NAME = "nextvm_net_diag.txt"
    private val HOSTS = listOf(
        "www.google.com",
        "firebaseinstallations.googleapis.com",
        "firebase.googleapis.com",
        "sdk-03.moengage.com",
        "theporter.in"
    )

    fun start(app: Application) {
        Thread({
            try {
                Thread.sleep(6000) // app ko pehle khulne do
                val proc = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else "?"
                val sb = StringBuilder()
                val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                sb.append("[$t] process=$proc\n")

                // 1. Android ke hisaab se network
                try {
                    val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    val n = cm.activeNetwork
                    val caps = cm.getNetworkCapabilities(n)
                    val lp = cm.getLinkProperties(n)
                    sb.append("activeNetwork=").append(n != null).append('\n')
                    sb.append("hasInternet=").append(caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                        .append(" validated=").append(caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                        .append(" vpn=").append(caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)).append('\n')
                    if (Build.VERSION.SDK_INT >= 28) {
                        sb.append("privateDnsActive=").append(lp?.isPrivateDnsActive)
                            .append(" privateDnsServer=").append(lp?.privateDnsServerName).append('\n')
                    }
                    sb.append("dnsServers=").append(lp?.dnsServers).append('\n')
                } catch (e: Throwable) {
                    sb.append("connectivity check failed: ").append(e).append('\n')
                }

                // 2. DNS
                for (h in HOSTS) {
                    val r = try {
                        InetAddress.getAllByName(h).joinToString { it.hostAddress ?: "?" }
                    } catch (e: Throwable) {
                        "FAIL ${e.javaClass.simpleName}: ${e.message}"
                    }
                    sb.append("DNS $h -> $r\n")
                }

                // 3. Asli HTTPS
                for (u in listOf("https://www.google.com/generate_204", "https://firebaseinstallations.googleapis.com/")) {
                    val r = try {
                        val c = URL(u).openConnection() as HttpURLConnection
                        c.connectTimeout = 6000; c.readTimeout = 6000
                        val code = c.responseCode
                        c.disconnect()
                        "HTTP $code"
                    } catch (e: Throwable) {
                        "FAIL ${e.javaClass.simpleName}: ${e.message}"
                    }
                    sb.append("HTTPS $u -> $r\n")
                }
                sb.append('\n')

                val f = File(app.filesDir, FILE_NAME)
                val old = if (f.exists()) f.readText() else ""
                f.writeText((old + sb).takeLast(3_500))
            } catch (_: Throwable) {
            }
        }, "nextvm-net-diag").apply { isDaemon = true; start() }
    }

    fun consume(ctx: Context): String? {
        val f = File(ctx.filesDir, FILE_NAME)
        if (!f.exists()) return null
        return try { f.readText().also { f.delete() } } catch (_: Throwable) { null }
    }
}
