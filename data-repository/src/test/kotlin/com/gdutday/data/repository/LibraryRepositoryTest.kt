package com.gdutday.data.repository

import com.gdutday.core.datastore.SessionStore
import com.gdutday.core.datastore.SettingsStore
import com.gdutday.core.datastore.UserSettings
import com.gdutday.core.model.GdutHosts
import com.gdutday.core.model.GdutSession
import com.gdutday.core.model.LoginMethod
import com.gdutday.core.model.StudentProfile
import com.gdutday.core.model.StoredCookie
import com.gdutday.data.gdut.library.LibraryQr
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * 内存假实现：设置保存在一个可变 `MutableStateFlow` 里。
 * [LibraryRepositoryImpl] 只读 `libraryQrStudentId` 一个字段，这足够覆盖回退逻辑。
 */
private class FakeSettingsStore(initialSettings: UserSettings = UserSettings()) : SettingsStore {
    private val state = MutableStateFlow(initialSettings)

    override val settings: Flow<UserSettings> = state

    override suspend fun update(transform: (UserSettings) -> UserSettings) {
        state.value = transform(state.value)
    }

    override suspend fun reset() {
        state.value = UserSettings()
    }
}

/** 返回构造时给定会话的假 [SessionStore]；默认 null（未登录）。 */
private class FakeSessionStore(private val currentSession: GdutSession? = null) : SessionStore {
    override val session: Flow<GdutSession?> = flowOf(currentSession)

    override suspend fun current(): GdutSession? = currentSession

    override suspend fun save(session: GdutSession) = Unit

    override suspend fun clear() = Unit
}

/** [LibraryRepositoryImpl.renderEntryQr] 的学号回退逻辑测试。 */
class LibraryRepositoryTest {

    private fun sessionOf(studentId: String): GdutSession = GdutSession(
        cookies = listOf(
            StoredCookie(
                name = "JSESSIONID",
                value = "x",
                domain = "example.com",
                path = "/",
                expiresAtMillis = Long.MAX_VALUE,
                secure = false,
                httpOnly = false,
                hostOnly = false,
            ),
        ),
        profile = StudentProfile(studentId = studentId),
        method = LoginMethod.UNIFIED_AUTH,
        hosts = GdutHosts.PRODUCTION,
    )

    @Test
    fun `优先使用传入的 studentId 而不是设置与会话`() = runTest {
        val repo = LibraryRepositoryImpl(
            sessionStore = FakeSessionStore(sessionOf("3120009999")),
            settingsStore = FakeSettingsStore(UserSettings(libraryQrStudentId = "3120008888")),
        )

        val pixels = repo.renderEntryQr(sizePx = 64, studentId = "3120001234")

        // 与直接用传入学号渲染的结果逐像素一致，即内容确实是传入的学号。
        assertThat(pixels).isNotNull()
        assertThat(pixels).isEqualTo(LibraryQr.renderArgb(content = "3120001234", width = 64, height = 64))
    }

    @Test
    fun `传入空白 studentId 时回退到设置中的自定义学号而不是会话`() = runTest {
        val repo = LibraryRepositoryImpl(
            sessionStore = FakeSessionStore(sessionOf("3120009999")),
            settingsStore = FakeSettingsStore(UserSettings(libraryQrStudentId = "3120008888")),
        )

        val pixels = repo.renderEntryQr(sizePx = 64, studentId = "  ")

        assertThat(pixels).isNotNull()
        assertThat(pixels).isEqualTo(LibraryQr.renderArgb(content = "3120008888", width = 64, height = 64))
    }

    @Test
    fun `未设置自定义学号时回退到当前登录用户的学号`() = runTest {
        val repo = LibraryRepositoryImpl(
            sessionStore = FakeSessionStore(sessionOf("3120009999")),
            settingsStore = FakeSettingsStore(),
        )

        val pixels = repo.renderEntryQr(sizePx = 64)

        assertThat(pixels).isNotNull()
        assertThat(pixels).isEqualTo(LibraryQr.renderArgb(content = "3120009999", width = 64, height = 64))
    }

    @Test
    fun `未登录且未设置学号时返回 null`() = runTest {
        val repo = LibraryRepositoryImpl(
            sessionStore = FakeSessionStore(),
            settingsStore = FakeSettingsStore(),
        )

        assertThat(repo.renderEntryQr(sizePx = 64)).isNull()
    }
}
