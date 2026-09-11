package com.gdutday.data.repository

import com.gdutday.core.database.GradeDao
import com.gdutday.core.database.Mappers
import com.gdutday.core.database.SyncStateDao
import com.gdutday.core.database.SyncStateEntity
import com.gdutday.core.datastore.SessionStore
import com.gdutday.core.model.GdutException
import com.gdutday.core.model.Grade
import com.gdutday.core.model.TermGradeSummary
import com.gdutday.data.gdut.jxfw.JxfwClient
import com.gdutday.data.gdut.jxfw.JxfwGradeParser
import com.gdutday.data.gdut.session.GdutSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * [GradeRepository] 的实现。
 *
 * ## 为什么成绩同步和课表同步共用一张 `sync_state`
 *
 * 两者都只有一个"上次刷新时间"，分开两张表对用户没有任何可见收益。成绩同步只更新
 * 时间戳与成功标志，**不碰** `last_term_code` / `last_schedule_source` ——
 * 那些属于课表，不能被一次成绩刷新清掉。
 *
 * ## 劳动教育 bug 放在 data-gdut
 *
 * 以 `xnxqdm=""` 查全部学期时「劳动教育」的 `zcj`/`cjjd` 为空，需要带具体学期重查。
 * 这段兜底在 [JxfwClient.fetchGrades] 内部完成，本类只拿最终结果。
 */
public class GradeRepositoryImpl(
    private val gradeDao: GradeDao,
    private val syncStateDao: SyncStateDao,
    private val sessionStore: SessionStore,
    private val authRepository: AuthRepository,
    private val jxfwClientFactory: (GdutSession) -> JxfwClient,
) : GradeRepository {

    override fun observeGrades(): Flow<List<Grade>> =
        gradeDao.observeAll().map { rows -> with(Mappers) { rows.map { it.toDomain() } } }

    override fun observeSummaries(): Flow<List<TermGradeSummary>> =
        observeGrades().map { JxfwGradeParser.summarize(it) }

    override fun observeTermNames(): Flow<List<String>> = gradeDao.observeTermNames()

    override suspend fun sync(): SyncInfo = withContext(Dispatchers.IO) {
        var session = sessionStore.current()
            ?: throw GdutException.SessionExpired("本地没有可用会话，请先登录")
        var client = jxfwClientFactory(session)
        if (!client.isSessionValid()) {
            session = authRepository.reloginSilently()
                ?: throw GdutException.SessionExpired("登录状态已失效，请重新登录")
            client = jxfwClientFactory(session)
        }

        try {
            // term = null：一次拉全部学期。旧后端的默认行为，也是成绩页想要的。
            val outcome = client.fetchGrades(term = null)
            gradeDao.replaceAll(with(Mappers) { outcome.grades.map { it.toEntity() } })

            // 会话可能在请求过程中被服务端轮换 cookie。
            sessionStore.save(session.copy(cookies = client.currentCookies()))

            val now = Instant.now()
            val prev = syncStateDao.get()
            syncStateDao.upsert(
                (prev ?: SyncStateEntity()).copy(lastSyncAt = now, lastSuccess = true, lastError = ""),
            )
            SyncInfo(at = now, success = true, courseCount = outcome.grades.size)
        } catch (e: GdutException) {
            runCatching {
                val prev = syncStateDao.get()
                syncStateDao.upsert(
                    (prev ?: SyncStateEntity()).copy(
                        lastSyncAt = Instant.now(),
                        lastSuccess = false,
                        // 成绩与课表共用 sync_state 单行，并发时 lastError 后写胜：
                        // 带上来源前缀，避免错误信息张冠李戴（诊断页能看到是谁失败的）。
                        lastError = "成绩同步失败：${e.userMessage}",
                    ),
                )
            }
            throw e
        }
    }
}
