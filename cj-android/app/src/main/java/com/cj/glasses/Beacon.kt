package com.cj.glasses

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Finds the brain on the Wi-Fi by its Bonjour name (announced by cj-mac/cj/beacon.py), so pairing survives
 * the Mac getting a new address. Returns "http://ip:port", or null if nothing answered in time.
 */
object Beacon {
    private const val TYPE = "_cjbrain._tcp"

    @Suppress("DEPRECATION")  // resolveService is the API that exists on Android 10-13 as well
    suspend fun find(context: Context, timeoutMs: Long = 6_000): String? = withTimeoutOrNull(timeoutMs) {
        val nsd = context.getSystemService(NsdManager::class.java)
        suspendCancellableCoroutine { cont ->
            val discovery = object : NsdManager.DiscoveryListener {
                override fun onServiceFound(service: NsdServiceInfo) {
                    nsd.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val host = info.host?.hostAddress ?: return
                            if (cont.isActive) cont.resume("http://$host:${info.port}")
                        }
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                            Log.w("Beacon", "resolve failed: $errorCode")
                        }
                    })
                }
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onServiceLost(service: NsdServiceInfo) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            }
            nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
            cont.invokeOnCancellation { runCatching { nsd.stopServiceDiscovery(discovery) } }
        }.also { /* stop once found */ }
    }
}
