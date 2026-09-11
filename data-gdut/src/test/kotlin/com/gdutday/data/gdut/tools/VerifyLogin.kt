package com.gdutday.data.gdut.tools

import com.gdutday.core.model.GdutException
import com.gdutday.core.model.StudentProfile
import com.gdutday.data.gdut.auth.AuthLoginPageParser
import com.gdutday.data.gdut.auth.AuthServerClient
import com.gdutday.data.gdut.auth.AuthServerConfig
import com.gdutday.data.gdut.auth.AuthServerCrypto
import com.gdutday.data.gdut.http.RedirectFollower
import com.gdutday.data.gdut.http.SessionCookieJar
import com.gdutday.data.gdut.http.asBrowserNavigation
import com.gdutday.data.gdut.jxfw.JxfwClient
import com.gdutday.data.gdut.jxfw.ScheduleEndpoint
import com.gdutday.data.gdut.jxfw.JxfwConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.KeyManagementException
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Properties
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.system.exitProcess

/**
 * 端到端登录验证脚本。**跑在 test source set，凭据相关代码绝不进 main。**
 *
 * ## 用法
 *
 * ```bash
 * # 方式一：环境变量（优先）
 * GDUT_STUDENT_ID=3120xxxxxx GDUT_PASSWORD='...' \
 *   gradle :data-gdut:verifyLogin
 *
 * # 方式二：项目根目录的 secrets.properties
 * #   gdut_student_id=3120xxxxxx
 * #   gdut_password=...
 * gradle :data-gdut:verifyLogin
 * ```
 *
 * ## 安全约定（重要）
 *
 * 1. 密码只作为局部变量存在，**任何情况下都不打印**；
 * 2. cookie **只打印名与域，绝不打印值**（JSESSIONID 的值等同于账号）；
 * 3. 加密后的密文只打印长度与前 8 个字符（密文里含 64 字节随机前缀，前 8 字符
 *    只是前缀的一部分，不泄露密码）；
 * 4. 不把任何凭据写进文件。
 *
 * ## 它做了什么
 *
 * 本脚本刻意**先做一遍「拆解式预检」再调用真正的 [AuthServerClient.login]**：
 * 预检逐步打印登录页解析、盐、加密结果、滑块检查；正式登录则验证整合后的
 * 完整流程真的能跑通。两者用的是同一套公开类，所以预检打印的内容就是
 * `login()` 内部实际看到的东西。
 *
 * 输出末尾永远有一行 `RESULT: PASS/FAIL`，退出码 0/1；参数缺失退出码 2。
 */
public object VerifyLogin {

    private val SECTION = "=".repeat(72)

    // 默认走系统信任链；仅当显式设置 GDUT_VERIFY_INSECURE=1 时才信任所有证书
    //（解决个别环境的 PKIX path building failed）。trust-all 会打开 MITM 窗口，
    // 而本工具要发送真实账号密码，绝不能默认开启。
    private fun createOkHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)

        if (System.getenv("GDUT_VERIFY_INSECURE") == "1") {
            println("⚠️  警告：GDUT_VERIFY_INSECURE=1，TLS 证书验证已禁用，存在 MITM 风险！")
            builder.applyTrustAll()
        }
        return builder.build()
    }

    /** 信任所有证书 + 任意主机名。仅限显式 opt-in（见 [createOkHttpClient]）。 */
    private fun OkHttpClient.Builder.applyTrustAll() {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {}
        })

        val sslContext: SSLContext = try {
            SSLContext.getInstance("TLS").apply {
                init(null, trustAllCerts, SecureRandom())
            }
        } catch (e: NoSuchAlgorithmException) {
            throw RuntimeException(e)
        } catch (e: KeyManagementException) {
            throw RuntimeException(e)
        }

        sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
        hostnameVerifier(HostnameVerifier { _: String, _: SSLSession -> true })
    }

    @JvmStatic
    public fun main(args: Array<String>) {
        val credentials = loadCredentials()
        if (credentials == null) {
            println(SECTION)
            println("缺少凭据，未发出任何网络请求。")
            println("请二选一：")
            println("  1) 环境变量 GDUT_STUDENT_ID / GDUT_PASSWORD")
            println("  2) 项目根目录 secrets.properties 里的 gdut_student_id / gdut_password")
            println(SECTION)
            exitProcess(2)
        }

        val (studentId, password) = credentials
        val summary = VerifySummary()
        val http = buildHttpClient()

        println(SECTION)
        println("广工课表 Android · 登录验证工具")
        println("学号: ${studentId.maskMiddle()}   (共 ${studentId.length} 位)")
        println("密码: 已读取，长度 ${password.length}，内容不予显示")
        println("本科格式检查: ${StudentProfile.looksLikeUndergraduateId(studentId)}")
        println(SECTION)

        // ---------- 阶段 1：拆解式预检 ----------
        runPrecheck(http, studentId, password, summary)

        // ---------- 阶段 2：正式登录 ----------
        val session = runLogin(http, studentId, password, summary) ?: finish(summary)

        // ---------- 阶段 3：教务接口（学期 / 课表 / 考试 / 成绩） ----------
        runJxfwChecks(http, session, summary)

        // ---------- 阶段 4：冷启动模拟（cookie 能否复用） ----------
        runCookieReuseCheck(http, session, summary)

        // ---------- 阶段 5（可选）：教务系统直登逃生通道 ----------
        runJxfwDirectLoginCheck(http, summary)

        finish(summary)
    }

    // ================================================== 冷启动模拟：cookie 复用

    /**
     * 用**已保存会话里的 cookie** 新建一个全新的 [JxfwClient]，再请求一次学期列表。
     */
    private fun runCookieReuseCheck(
        http: OkHttpClient,
        session: com.gdutday.data.gdut.session.GdutSession,
        summary: VerifySummary,
    ) {
        println("\n[8/9] 冷启动模拟：仅凭已保存的 cookie 重建客户端")
        println("  会话里的 cookie: ${session.cookies.size} 个")
        println("  含 jxfw 会话: ${session.hasJxfwSession}   含 authserver 会话: ${session.hasAuthServerSession}")
        if (!session.hasJxfwSession) {
            println("  ⚠ 没有 jxfw 域的 JSESSIONID，冷启动后所有教务接口都会 302 回登录页。")
            summary.record("冷启动 cookie 复用", false)
            return
        }
        try {
            // 全新的 OkHttpClient + 全新的 CookieJar，只灌入保存下来的 cookie
            val freshHttp = http.newBuilder().build()
            val freshClient = JxfwClient(
                freshHttp,
                session,
                JxfwConfig(scheduleEndpoint = ScheduleEndpoint.AUTO),
            )
            val valid = freshClient.isSessionValid()
            println("  会话探活（GET jxfw 首页看是否被 302 回登录页）: $valid")
            val terms = freshClient.fetchTermList()
            println("  重建后取到学期: ${terms.terms.size} 个，当前 = ${terms.current?.shortCode ?: "<无>"}")
            if (!valid || terms.terms.isEmpty()) {
                println("  ⚠ cookie 存下来了但服务端不认。")
            }
            summary.record("冷启动 cookie 复用", valid && terms.terms.isNotEmpty())
        } catch (e: Exception) {
            println("  失败: ${e.javaClass.simpleName}: ${e.message}")
            (e as? GdutException)?.detail?.let { println("  诊断: ${it.take(500)}") }
            summary.record("冷启动 cookie 复用", false)
        }
    }

    // ================================================== 教务直登（可选，交互式）

    /**
     * 验证"统一认证被风控时的逃生通道"：jxfw `/new/login` + 图形验证码。
     */
    private fun runJxfwDirectLoginCheck(http: OkHttpClient, summary: VerifySummary) {
        if (System.getenv("GDUT_VERIFY_JXFW_DIRECT") != "1") {
            println("\n[9/9] 教务系统直登：已跳过（设 GDUT_VERIFY_JXFW_DIRECT=1 可启用，需人工输验证码）")
            return
        }
        println("\n[9/9] 教务系统直登（jxfw /new/login + 图形验证码）")
        try {
            val cap = com.gdutday.data.gdut.jxfw.JxfwDirectLogin.fetchCaptcha(http)
            val ext = if (cap.contentType.contains("png", ignoreCase = true)) "png" else "jpg"
            val out = File("build/verify-captcha.$ext").absoluteFile
            out.parentFile?.mkdirs()
            out.writeBytes(cap.bytes)
            println("  验证码已保存: $out")
            println("  类型=${cap.contentType}  大小=${cap.sizeBytes} 字节")
            println("  cookie 串: ${cap.cookieHeader?.take(24)?.let { "$it…" } ?: "<无>"}  ← login 时必须原样回传")
            print("  请打开图片并输入验证码，然后回车: ")
            System.out.flush()
            val code = readlnOrNull()?.trim().orEmpty()
            if (code.isEmpty()) {
                println("  未输入，跳过。")
                summary.record("教务直登（可选）", true)
                return
            }
            val creds = loadCredentials() ?: return
            val s = com.gdutday.data.gdut.jxfw.JxfwDirectLogin.login(
                httpClient = http,
                studentId = creds.first,
                password = creds.second,
                verifyCode = code,
                captchaCookie = cap.cookieHeader,
            )
            println("  直登成功。cookie: ${s.cookies.size} 个，jxfw 会话=${s.hasJxfwSession}")
            summary.record("教务直登（可选）", s.hasJxfwSession)
        } catch (e: Exception) {
            println("  失败: ${e.javaClass.simpleName}: ${e.message}")
            (e as? GdutException)?.let {
                println("  用户可读: ${it.userMessage}")
                it.detail?.let { d -> println("  诊断: ${d.take(500)}") }
            }
            summary.record("教务直登（可选）", false)
        }
    }

    // ================================================================== 预检

    /**
     * 手动走一遍登录页 + 加密 + 滑块检查，把 `AuthServerClient.login` 内部
     * 看到的关键中间值打印出来。
     */
    private fun runPrecheck(
        http: OkHttpClient,
        studentId: String,
        password: String,
        summary: VerifySummary,
    ) {
        try {
        val jar = SessionCookieJar()
        val client = http.newBuilder()
            .cookieJar(jar)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        val follower = RedirectFollower(client)

        println("\n[1/9] 取统一认证登录页（先访问教务 SSO 入口，观察 302 链）")
        val entry = AuthServerConfig().effectiveEntryUrl
        val pageResult = follower.follow(
            Request.Builder().url(entry).asBrowserNavigation(referer = null, sameSite = "none").build(),
        )
        val pageHtml = pageResult.response.use { it.body.string() }
        println("  入口: $entry")
        println("  跳转链: ${pageResult.chainToString()}")
        println("  登录页落点: ${pageResult.finalUrl}")
        println("  登录页大小: ${pageHtml.length} 字符")

        println("\n[2/9] 解析登录表单（脱敏）")
        val form = AuthLoginPageParser.parse(pageHtml, pageResult.finalUrl)
        println("  action: ${form.action}")
        println("  提交地址: ${form.submitUrl()}")
        println("  service: ${form.service ?: "<无>"}")
        println("  captchaSwitch: ${form.captchaSwitch ?: "<无>"}")
        println("  execution: ${if (form.execution.isNullOrBlank()) "<无>" else "存在，长度 ${form.execution.length}"}")
        println("  salt: ${form.salt?.let { "${it.take(2)}***(${it.length} 字节)" } ?: "<无>"}")
        println("  salt 合法: ${form.hasUsableSalt()}")

        println("\n[3/9] 复刻浏览器加密（只打印长度与前缀，不打印密文）")
        if (form.salt.isNullOrEmpty()) {
            println("  salt 为空，按协议不加密（直接发明文）。")
        } else {
            val cipher = AuthServerCrypto.encryptPassword(password, form.salt)
            println("  明文长度: ${password.length} 字符")
            println("  密文长度: ${cipher.length} 字符（Base64）")
            println("  密文前缀: ${cipher.take(8)}…（随机前缀的一部分，不含密码信息）")
        }

        println("\n[4/9] 询问是否需要人机验证")
        val captcha = AuthServerClient(client, AuthServerConfig(checkCaptchaBeforeLogin = false))
            .checkCaptchaRequired(studentId, client)
        println("  需要验证码: ${captcha.required}  (查询成功=${captcha.known})")
        println("  原始响应: ${captcha.raw?.take(200) ?: "<无>"}")
        if (captcha.required) {
            println("  ⚠ 服务端要求滑块验证，正式登录会抛 CaptchaRequired —— 改用教务系统直登。")
        }
        summary.record("预检（登录页解析 + 加密 + 滑块检查）", true)
        } catch (e: Exception) {
            println("  预检失败: ${e.javaClass.simpleName}: ${e.message}")
            summary.record("预检（登录页解析 + 加密 + 滑块检查）", false)
        }
    }

    // ================================================================== 登录

    private fun runLogin(
        http: OkHttpClient,
        studentId: String,
        password: String,
        summary: VerifySummary,
    ): com.gdutday.data.gdut.session.GdutSession? {
        println("\n[5/9] 调用 AuthServerClient.login() 完成真实登录")
        val config = AuthServerConfig(
            rejectNonUndergraduate = false,
            verifyAfterLogin = true,
        )
        return try {
            val session = AuthServerClient(http, config).login(studentId, password)
            println("  登录成功。用户类型: ${session.userType}")
            println("  登录方式: ${session.method.displayName}")
            println("  cookie（只列名与域，不列值）:")
            session.cookies.forEach { println("    ${it.name} @ ${it.domain} (path=${it.path})") }
            summary.record("登录（AuthServerClient.login）", true)
            session
        } catch (e: GdutException) {
            println("  登录失败: ${e.javaClass.simpleName}: ${e.userMessage}")
            e.detail?.let { println("  诊断: ${it.take(500)}") }
            summary.record("登录（AuthServerClient.login）", false)
            null
        } catch (e: Exception) {
            println("  登录异常: ${e.javaClass.simpleName}: ${e.message}")
            summary.record("登录（AuthServerClient.login）", false)
            null
        }
    }

    // ================================================================== 教务接口

    private fun runJxfwChecks(
        http: OkHttpClient,
        session: com.gdutday.data.gdut.session.GdutSession,
        summary: VerifySummary,
    ) {
        val client = JxfwClient(http, session, JxfwConfig(scheduleEndpoint = ScheduleEndpoint.AUTO))

        println("\n[6/9] 学期列表（xsksap!ksapList.action）")
        val termList = try {
            client.fetchTermList()
        } catch (e: Exception) {
            println("  失败: ${e.javaClass.simpleName}: ${e.message}")
            summary.record("学期列表", false)
            return
        }
        println("  学期数量: ${termList.terms.size}")
        termList.terms.take(5).forEach { println("    ${it.shortCode} / ${termList.nameOf(it)}") }
        val term = termList.current ?: termList.terms.firstOrNull()
        if (term == null) {
            println("  ⚠ 没有任何学期，后续接口无法验证。")
            summary.record("学期列表", false)
            return
        }
        summary.record("学期列表", true)
        println("  当前学期: ${term.shortCode} (xnxqdm=${term.xnxqdm})")

        println("\n[7/9] 课表 / 考试 / 成绩")
        try {
            val schedule = client.fetchSchedule(term)
            println("  课表接口命中: ${schedule.endpoint.displayName}，共 ${schedule.rows.size} 行，${schedule.courses.size} 门课")
            schedule.courses.take(3).forEach { c ->
                println("    · ${c.name} ${c.weeksDisplay} 周${c.dayOfWeek} 第${c.startSection}-${c.endSection}节 " +
                    "${c.classroom.ifBlank { "?" }} ${c.teacher.ifBlank { "?" }}")
            }
            schedule.warnings.forEach { println("    ⚠ $it") }
            summary.record("课表（$term）", true)
        } catch (e: Exception) {
            println("  课表失败: ${e.javaClass.simpleName}: ${e.message}")
            summary.record("课表（$term）", false)
        }

        try {
            val exams = client.fetchExams(term)
            println("  考试条数: ${exams.exams.size}")
            exams.exams.take(3).forEach { e ->
                println("    · ${e.courseName} ${e.date} ${e.timeDisplay} ${e.classroom}")
            }
            summary.record("考试（$term）", true)
        } catch (e: Exception) {
            println("  考试失败: ${e.javaClass.simpleName}: ${e.message}")
            summary.record("考试（$term）", false)
        }

        try {
            val grades = client.fetchGrades(term = null)
            println("  成绩条数: ${grades.grades.size}")
            grades.grades.take(3).forEach { g ->
                println("    · ${g.termName} ${g.courseName} 分数=${g.scoreText.ifBlank { "?" }} 绩点=${g.gpa ?: "?"}")
            }
            summary.record("成绩（全部学期）", true)
        } catch (e: Exception) {
            println("  成绩失败: ${e.javaClass.simpleName}: ${e.message}")
            summary.record("成绩（全部学期）", false)
        }
    }

    // ================================================================== 凭据 / 汇总

    /** 环境变量优先；否则读项目根目录（或上一级）的 secrets.properties。 */
    private fun loadCredentials(): Pair<String, String>? {
        val envId = System.getenv("GDUT_STUDENT_ID")?.trim().orEmpty()
        val envPwd = System.getenv("GDUT_PASSWORD").orEmpty()
        if (envId.isNotEmpty() && envPwd.isNotEmpty()) return envId to envPwd

        val props = readSecretsProperties() ?: return null
        val id = envId.ifEmpty { props.getProperty("gdut_student_id")?.trim().orEmpty() }
        val pwd = envPwd.ifEmpty { props.getProperty("gdut_password").orEmpty() }
        return if (id.isNotEmpty() && pwd.isNotEmpty()) id to pwd else null
    }

    private fun readSecretsProperties(): Properties? {
        val candidates = listOf(
            File("secrets.properties"),
            File("../secrets.properties"),
            File("../../secrets.properties"),
        )
        val file = candidates.firstOrNull { it.isFile } ?: return null
        return try {
            Properties().apply { file.inputStream().use { load(it) } }
        } catch (e: Exception) {
            println("读取 ${file.absolutePath} 失败: ${e.message}")
            null
        }
    }

    private fun buildHttpClient(): OkHttpClient = createOkHttpClient().newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private fun finish(summary: VerifySummary): Nothing {
        println("\n$SECTION")
        println("汇总：")
        summary.entries.forEach { (name, ok) -> println("  [${if (ok) "通过" else "失败"}] $name") }
        val pass = summary.entries.isNotEmpty() && summary.entries.all { it.second }
        println("\nRESULT: ${if (pass) "PASS" else "FAIL"}")
        println(SECTION)
        exitProcess(if (pass) 0 else 1)
    }

    /** 字符串脱敏：保留首尾各 3 位。 */
    private fun String.maskMiddle(keepHead: Int = 3, keepTail: Int = 3): String = when {
        length <= keepHead + keepTail -> "*".repeat(length)
        else -> take(keepHead) + "*".repeat(length - keepHead - keepTail) + takeLast(keepTail)
    }

    private class VerifySummary {
        val entries = mutableListOf<Pair<String, Boolean>>()
        fun record(name: String, ok: Boolean) { entries += name to ok }
    }
}
