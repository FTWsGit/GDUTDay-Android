package com.gdutday.data.repository

import com.gdutday.core.model.Course
import com.gdutday.core.model.Grade
import com.gdutday.core.model.StudentProfile
import com.gdutday.core.model.Term
import com.gdutday.core.model.TermGradeSummary
import com.gdutday.data.gdut.session.GdutSession
import com.gdutday.data.gdut.session.LoginMethod
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

// ============================================================================
// Repository 契约。
//
// UI 层（feature-*）只依赖这里的接口，不依赖任何实现。
// 这样做的直接收益：Compose 预览和 ViewModel 单元测试可以用假实现，
// 不需要 Robolectric，也不需要网络。
//
// ## 一条贯穿所有 Repository 的错误约定
//
// **不返回 Result<T>，直接抛 [com.gdutday.core.model.GdutException]。**
// 理由：
// - `GdutException` 已经带了 `userMessage`，UI 拿到就能显示，不需要再解一层 Either；
// - Kotlin 的协程有结构化并发，异常会自动取消兄弟任务，用 Result 反而要手动传播；
// - 会话过期这类错误需要**跨 Repository 统一处理**（清 cookie + 跳登录页），
//   用异常 + CoroutineExceptionHandler 比在每个调用点检查 Result 可靠得多。
//
// 唯一例外是"读本地缓存"的方法：它们永不抛异常，读不到就返回空/null。
// 首屏绝不能因为数据库问题而报错。
// ============================================================================

/**
 * 登录 / 登出 / 会话管理。
 *
 * ## 同步还是异步的登录
 *
 * 全部是 `suspend`。登录一次要 3~6 个网络往返（取页面 → 查风控 → 提交 → 跟随 3~5 跳
 * → 取用户信息 → 探活教务系统），耗时 2~8 秒，绝不能阻塞主线程。
 */
public interface AuthRepository {

    /**
     * 当前会话。冷启动时立刻能拿到（解密本地文件），无需等网络。
     *
     * 这是"启动速度"的关键：**首屏渲染不依赖它**，课表页直接读 Room。
     * 会话只在需要同步时才被用到。
     */
    public val session: StateFlow<GdutSession?>

    /** 是否已登录。 */
    public val isLoggedIn: Flow<Boolean>

    /**
     * 统一身份认证登录。
     *
     * @param studentId 学号
     * @param password **明文**密码。加密在 `AuthServerCrypto` 内部完成，调用方不要预加密。
     * @param rememberPassword 是否把密码存进 Keystore 以便会话过期后静默重登。
     *   默认 false —— 见 `CredentialStore` 的注释。
     * @throws com.gdutday.core.model.GdutException.BadCredentials 学号或密码错误
     * @throws com.gdutday.core.model.GdutException.CaptchaRequired 触发滑块风控，
     *   UI 应引导用户改用 [loginViaJxfw]
     * @throws com.gdutday.core.model.GdutException.UnsupportedUserType 研究生/教师账号
     */
    public suspend fun login(
        studentId: String,
        password: String,
        rememberPassword: Boolean = false,
    ): GdutSession

    /**
     * 教务系统直登（图形验证码路径）。
     *
     * 统一认证触发滑块时的**逃生通道**。验证码图片由 [fetchJxfwCaptcha] 提供，
     * 用户看一眼填进来。
     *
     * @param captchaToken [fetchJxfwCaptcha] 返回的 token，**必须原样传回** ——
     *   它绑定了那次请求下发的 JSESSIONID，换了就永远提示"验证码不正确"。
     */
    public suspend fun loginViaJxfw(
        studentId: String,
        password: String,
        captcha: String,
        captchaToken: String,
        rememberPassword: Boolean = false,
    ): GdutSession

    /**
     * 取教务系统的图形验证码。
     *
     * @return (JPEG 字节, token)。UI 用 `BitmapFactory.decodeByteArray` 渲染，
     *   140×60 的图很小，不需要采样。
     */
    public suspend fun fetchJxfwCaptcha(): JxfwCaptcha

    /** 当前账号的档案（学号、身份、姓名）。未登录时为 null。 */
    public val profile: Flow<StudentProfile?>

    /**
     * 检查现有会话是否仍然有效（探活教务系统首页）。
     *
     * 不抛异常，只返回结果 —— "会话失效"是正常状态，不是错误。
     */
    public suspend fun isSessionValid(): Boolean

    /**
     * 会话失效时用记住的凭据静默重登。
     *
     * @return 成功返回新会话；没有记住密码或重登失败返回 null（调用方应引导用户手动登录）。
     */
    public suspend fun reloginSilently(): GdutSession?

    /** 退出登录。清 cookie、清记住的密码，**但保留课表数据**（用户可能只是换账号看看）。 */
    public suspend fun logout(clearLocalData: Boolean = false)
}

/** [AuthRepository.fetchJxfwCaptcha] 的结果。 */
public data class JxfwCaptcha(
    public val imageBytes: ByteArray,
    /** 绑定 JSESSIONID 的不透明令牌，提交时必须原样带回。 */
    public val token: String,
    public val width: Int,
    public val height: Int,
) {
    // ByteArray 参与 equals 会导致 data class 语义混乱，显式覆写
    override fun equals(other: Any?): Boolean =
        other is JxfwCaptcha && token == other.token && imageBytes.contentEquals(other.imageBytes)

    override fun hashCode(): Int = 31 * token.hashCode() + imageBytes.contentHashCode()

    override fun toString(): String = "JxfwCaptcha(token=${token.take(8)}…, ${width}x${height}, ${imageBytes.size}B)"
}

/**
 * 课表：读取、同步、增删自定义课程。
 */
public interface ScheduleRepository {

    /**
     * 课表页的完整状态流。**UI 只需要收集这一个 Flow**。
     *
     * 计算发生在 Repository 内部（见 [ScheduleUiState] 的注释说明为什么）。
     * `distinctUntilChanged()` 是必须的：底层任何一个 DAO 的 Flow 变动都会触发重算，
     * 而重算结果往往相同（比如只是 `updatedAt` 变了），不 distinct 会让 UI 无谓重组。
     */
    public fun observeScheduleUiState(): Flow<ScheduleUiState>

    /** "下一节课"。首页卡片与 Widget 共用。 */
    public fun observeNextClass(): Flow<NextClass?>

    /** 手动同步。会先检查会话，失效则尝试静默重登，再失败抛 [com.gdutday.core.model.GdutException.SessionExpired]。 */
    public suspend fun sync(term: Term? = null): SyncInfo

    /** 切换显示的学期（不改数据库里的"当前学期"标记，只改用户的选择）。 */
    public suspend fun selectTerm(term: Term)

    /** 切换显示的周次。纯内存状态，不落盘（每次打开 App 都回到本周更符合直觉）。 */
    public suspend fun selectWeek(week: Int)

    // ------------------------------------------------------------ 自定义课程

    /**
     * 添加自定义课程。
     *
     * @return 与它冲突的已有课程。**非空表示没有添加** —— 由 UI 决定是提示用户
     *   还是让用户确认后强制添加（`force = true`）。
     *
     * 冲突判定：同一天、节次区间有交集、周次集合有交集。
     * 三个条件缺一不可 —— 只有周次不重叠的两门课是**合法的**（单双周选课），
     * 旧小程序在这一点上判对了，保留。
     */
    public suspend fun addCustomCourse(course: Course, force: Boolean = false): List<Course>

    public suspend fun updateCustomCourse(course: Course): List<Course>

    /** 删除课程。[Course.source] 为 SCHOOL 时同样允许删（用户可能不想看某门课）。 */
    public suspend fun deleteCourse(id: Long)

    public suspend fun deleteAllCustomCourses()

    /** 为某门课指定颜色。会持久化，之后的自动配色不会覆盖它。 */
    public suspend fun setCourseColor(courseName: String, colorKey: String)

    /** 重置全部自动配色（用户手选的保留）。 */
    public suspend fun resetColors()

    // ------------------------------------------------------------ 学期开始日期

    /**
     * 用户手动校准学期开始日期。
     *
     * 这是 [com.gdutday.core.common.TermCalendar] 取值优先级里最高的一级，
     * 设置之后即使反推结果不同也不会被覆盖。
     */
    public suspend fun setSemesterStart(term: Term, startDate: java.time.LocalDate)
}

/**
 * 成绩与绩点。
 */
public interface GradeRepository {

    /** 全部成绩，按学期倒序。 */
    public fun observeGrades(): Flow<List<Grade>>

    /** 按学期分组的汇总（含加权绩点、总学分、挂科数）。 */
    public fun observeSummaries(): Flow<List<TermGradeSummary>>

    /** 可选学期名列表。 */
    public fun observeTermNames(): Flow<List<String>>

    /**
     * 同步成绩。
     *
     * 内部会自动处理**劳动教育 bug**：以 `xnxqdm=""` 查全部学期时该课的 `zcj`/`cjjd`
     * 为空，需要带上具体学期号重查一次。见 [com.gdutday.core.model.Grade] 的注释。
     */
    public suspend fun sync(): SyncInfo
}

/**
 * 图书馆入馆二维码。
 *
 * **纯本地生成，不需要网络，也不需要后端。**
 * 二维码内容就是学号本身（旧后端 `LibQrUtil` 用 zxing + `ErrorCorrectionLevel.H` 生成，
 * 本项目在 `data-gdut` 的 `LibraryQr` 里复刻了同样的参数）。
 *
 * 所以这个 Repository 没有任何同步逻辑，只是一个把学号变成像素的函数。
 */
public interface LibraryRepository {
    /**
     * 生成入馆二维码的 ARGB 像素数组。
     *
     * @param sizePx 边长（像素）。闸机扫码对分辨率不敏感，256~512 都够用。
     * @return `IntArray(sizePx * sizePx)`，可直接喂给 `Bitmap.createBitmap`。
     *   未登录时返回 null。
     */
    public suspend fun renderEntryQr(sizePx: Int = 480): IntArray?

    /**
     * 二维码的模块数（不含静默区），UI 用来决定码点大小与留白。
     *
     * @return 未登录时返回 null。
     *
     * 实算而不是硬编码，理由见 `LibraryQr.moduleCount` 的 KDoc ——
     * 简单说：硬编码 21 隐含了"学号恒为 10 位数字"的假设，
     * 一旦内容变长（支持研究生、或改成动态码）就会静默算错留白。
     */
    public suspend fun moduleCount(): Int?
}
