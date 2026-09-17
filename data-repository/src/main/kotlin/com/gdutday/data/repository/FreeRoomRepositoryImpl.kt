package com.gdutday.data.repository

import com.gdutday.core.datastore.SessionStore
import com.gdutday.data.gdut.freeroom.FreeClassroomClient
import com.gdutday.data.gdut.freeroom.FreeRoomBuilding
import com.gdutday.data.gdut.freeroom.JwcwxSso
import com.gdutday.data.gdut.freeroom.RoomUsageResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * [FreeRoomRepository] 的实现。
 *
 * ## 会话打通策略
 *
 * jwcwx 与 jxfw 的会话是两个域的 JSESSIONID。第一次访问时用当前会话的
 * authserver TGT 免密打通 jwcwx（[JwcwxSso.ensureSession]），拿到的 cookie
 * **只写回内存副本** —— jwcwx 的 JSESSIONID 不持久化，每次 App 冷启动重新打通。
 * 理由：打通是无交互的（TGT 有效时一次跳转链），成本一个请求；持久化两套会话
 * 会让会话过期判定和登出清理都复杂一倍，收益只有省一个请求。
 *
 * [okHttpClient] 与 [sessionStore] 由容器注入；线程模型遵守项目约定：
 * 阻塞 OkHttp 调用统一收口在 `withContext(Dispatchers.IO)`。
 */
public class FreeRoomRepositoryImpl(
    private val sessionStore: SessionStore,
    private val okHttpClient: OkHttpClient,
    private val reloginSilently: suspend () -> Unit = {},
) : FreeRoomRepository {

    /** 打通过程串行化：并发请求同时打通会产生两份互不知情的 cookie。 */
    private val ensureMutex = Mutex()

    override suspend fun fetchBuildings(): List<FreeRoomBuilding> = withContext(Dispatchers.IO) {
        client().fetchBuildings()
    }

    override suspend fun fetchRoomUsage(buildingCode: String, date: String): RoomUsageResult =
        withContext(Dispatchers.IO) {
            client().fetchRoomUsage(buildingCode, date)
        }

    private suspend fun client(): FreeClassroomClient {
        val session = sessionStore.current()
            ?: throw com.gdutday.core.model.GdutException.SessionExpired(detail = "未登录")
        return ensureMutex.withLock {
            val ensured = JwcwxSso.ensureSession(okHttpClient, session)
            FreeClassroomClient(okHttpClient, ensured)
        }
    }
}
