package com.holeintimes.vbrowser.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.holeintimes.vbrowser.data.download.DownloadManager

class NetworkMonitor(
    context: Context,
    private val downloadManager: DownloadManager
) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (hasValidatedInternet()) {
                downloadManager.onNetworkAvailable(true)
            }
        }

        override fun onLost(network: Network) {
            if (!hasValidatedInternet()) {
                downloadManager.onNetworkAvailable(false)
            }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            downloadManager.onNetworkAvailable(
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            )
        }
    }

    fun start() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, callback)
        downloadManager.onNetworkAvailable(hasValidatedInternet())
    }

    fun stop() {
        runCatching { cm.unregisterNetworkCallback(callback) }
    }

    private fun hasValidatedInternet(): Boolean {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
