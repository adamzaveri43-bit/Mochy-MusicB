package com.mochyy.mochymusic.spotify.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** What every layer asks before spending time on a request that cannot succeed. */
internal object NetworkMonitor {
    private val mutable = MutableStateFlow(true)
    val online: StateFlow<Boolean> = mutable.asStateFlow()
    private val changeListeners = CopyOnWriteArraySet<() -> Unit>()
    private var started = false
    @Volatile private var connected = false
    @Volatile private var userOffline = false
    @Volatile private var testOffline = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun publish(invalidate: Boolean = false) {
        val previous = mutable.value
        mutable.value = connected && !userOffline && !testOffline
        if (invalidate || previous != mutable.value) changeListeners.forEach { it() }
    }

    @Synchronized fun start(context: Context) {
        if (started) return
        started = true
        userOffline = com.mochyy.mochymusic.utils.OfflineMode.isOn
        // Mochy's offline switch has no flow of its own: it is read again on every network change and once a few seconds
        scope.launch {
            while (true) {
                val now = com.mochyy.mochymusic.utils.OfflineMode.isOn
                if (now != userOffline) { userOffline = now; publish() }
                kotlinx.coroutines.delay(3_000)
            }
        }
        val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        connected = manager.getNetworkCapabilities(manager.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        publish()
        manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            private var current: Network? = manager.activeNetwork
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val switched = current != network
                current = network
                connected = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                // Sockets of the previous network would otherwise stall the first requests of the new one.
                publish(invalidate = switched)
            }
            override fun onLost(network: Network) {
                if (current != network) return
                current = null
                connected = false
                publish(invalidate = true)
            }
        })
    }

    /** Instrumented tests only. */
    fun setOfflineForTest(offline: Boolean) { testOffline = offline; publish() }

    /** Called whenever open connections stop being usable. */
    fun onConnectionsInvalidated(listener: () -> Unit): AutoCloseable {
        changeListeners.add(listener)
        return AutoCloseable { changeListeners.remove(listener) }
    }
}
