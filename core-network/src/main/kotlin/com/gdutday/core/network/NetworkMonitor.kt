package com.gdutday.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

/** 网络可用性。区分类型是为了在 UI 上给出准确的提示（"仅移动网络"时要提醒流量）。 */
public enum class NetworkAvailability {
    /** 无网络。 */
    OFFLINE,

    /** WiFi。 */
    WIFI,

    /** 蜂窝网络。 */
    CELLULAR,

    /** 其它（以太网等）。 */
    OTHER,
    ;

    public val isOnline: Boolean get() = this != OFFLINE
}

/**
 * 连接状态监听。
 *
 * ## 用途
 *
 * 课表 App 的核心诉求是**离线可用**：首屏永远读 Room，网络只在后台同步。
 * 但用户点了"刷新"却发现转半天圈，体验很差 —— 这时应该立刻告诉他"当前无网络，
 * 显示的是 X 天前的数据"。这个类就是为那句话服务的。
 *
 * ## 实现选择
 *
 * 用 `registerNetworkCallback` 而不是 `registerDefaultNetworkCallback`：
 * 后者在部分厂商 ROM 上首次注册时不会立刻回调当前状态，
 * 导致 Flow 的第一个值迟迟不来。前者配合下面的 [current] 主动查询，
 * 能保证订阅后**立即**拿到一个值。
 */
public class NetworkMonitor(context: Context) {

    private val cm: ConnectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * 当前状态（一次性查询）。
     *
     * 不需要持续观察时用这个，比订阅 [changes] 便宜得多 ——
     * 同步课表前问一句就够了，没必要常驻一个回调。
     */
    public val current: NetworkAvailability
        get() {
            val network = runCatching { cm.activeNetwork }.getOrNull() ?: return NetworkAvailability.OFFLINE
            val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull()
                ?: return NetworkAvailability.OFFLINE
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return NetworkAvailability.OFFLINE
            return when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkAvailability.WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkAvailability.CELLULAR
                else -> NetworkAvailability.OTHER
            }
        }

    /** 是否有网。同步课表前的快速判断。 */
    public val isOnline: Boolean get() = current.isOnline

    /**
     * 状态变化流。**订阅时立即发出当前值**，之后只在变化时发出。
     *
     * `conflate()` 是必要的：网络抖动时回调可能密集触发，
     * 而 UI 只关心最新状态，没必要把中间值都渲染一遍。
     */
    public val changes: Flow<NetworkAvailability> = callbackFlow {
        trySend(current)

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(current)
            }

            override fun onLost(network: Network) {
                trySend(current)
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(current)
            }
        }

        runCatching { cm.registerNetworkCallback(request, callback) }
            .onFailure { trySend(current) }

        awaitClose { runCatching { cm.unregisterNetworkCallback(callback) } }
    }.conflate().distinctUntilChanged()
}
