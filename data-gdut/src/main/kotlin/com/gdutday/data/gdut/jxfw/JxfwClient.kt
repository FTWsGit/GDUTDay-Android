package com.gdutday.data.gdut.jxfw

import com.gdutday.core.model.GdutException
import com.gdutday.core.model.StudentProfile
import com.gdutday.core.model.Term
import com.gdutday.core.model.UserType
import com.gdutday.data.gdut.GdutEndpoints
import com.gdutday.data.gdut.GdutHosts
import com.gdutday.data.gdut.http.FormFields
import com.gdutday.data.gdut.http.LenientJson
import com.gdutday.data.gdut.http.RedirectFollower
import com.gdutday.data.gdut.http.SessionCookieJar
import com.gdutday.core.model.StoredCookie
import com.gdutday.data.gdut.http.asBrowserNavigation
import com.gdutday.data.gdut.http.asBrowserXhr
import com.gdutday.data.gdut.http.originOf
import com.gdutday.data.gdut.http.int
import com.gdutday.data.gdut.http.str
import com.gdutday.data.gdut.session.GdutSession
import com.gdutday.data.gdut.session.LoginMethod
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/** 课表接口选择策略。 */
public enum class ScheduleEndpoint(
    public val displayName: String,
) {
    /** 先试 `getDataList`（JSON，按周返回），失败或返回空则回退 `xsAllKbList`（HTML）。默认。 */
    AUTO("自动"),

    /** 只用 `xsAllKbList`。 */
    ALL_KB_LIST("xsAllKbList（按教学班聚合）"),

    /** 只用 `getDataList`。 */
    DATA_LIST("getDataList（按周炸开）"),

    /** 班级课表主接口 `getKbRq`。 */
    CLASS_SCHEDULE_DATA_LIST("getKbRq（班级课表）"),

    /** 班级课表备接口 `xsAllKbList`。 */
    CLASS_SCHEDULE_ALL_KB_LIST("xsAllKbList（班级课表）"),
}

/**
 * [JxfwClient] 的可调参数。
 *
 * @property rowsPerPage 分页接口每页条数。
 *   旧 Java 后端课表用 300、成绩/考试用 200，且**都不翻页**。
 *   一学期 20 周 × 每周 20 条 = 400 条，课多的学生会被静默截断、丢掉半个学期的课。
 *   这里默认 200 并配合 [maxPages] 循环取满。
 * @property maxPages 翻页上限，防止服务端 `total` 异常导致无限翻页。
 * @property scheduleEndpoint 课表接口策略。
 * @property patchLaborEducation 是否执行「劳动教育」成绩兜底重查，见 [JxfwGradeParser]。
 */
public data class JxfwConfig(
    /**
     * 各系统的基础地址。默认生产环境；测试里换成 [GdutHosts.forTestServer] 指向 MockWebServer。
     */
    public val hosts: GdutHosts = GdutHosts.PRODUCTION,
    public val rowsPerPage: Int = 200,
    public val maxPages: Int = 20,
    public val scheduleEndpoint: ScheduleEndpoint = ScheduleEndpoint.AUTO,
    public val patchLaborEducation: Boolean = true,
    /**
     * 班级课表的班级代码（`bjdm`）。非空时 [JxfwClient.fetchSchedule] 走班级课表接口，
     * 替代个人课表。null / 空 = 个人课表。
     */
    public val classCode: String? = null,
)

/** 课表抓取结果。 */
public data class ScheduleFetchResult(
    /** 归一化前的原始行。保留是为了诊断 —— 用户报"某门课不见了"时能直接看原始数据。 */
    public val rows: List<RawScheduleRow>,
    /** 实际命中的接口。AUTO 策略下这个信息很有价值。 */
    public val endpoint: ScheduleEndpoint,
    public val pagesFetched: Int,
    public val normalization: CourseNormalizer.Outcome,
    public val warnings: List<String> = emptyList(),
    /**
     * 班级课表 `getKbRq` 返回的周日期（`rows[1]`）。个人课表恒为 null。
     * 周一的 `rq` 即该周开学日，可作为 `KnownSemesterStarts` 的动态来源。
     */
    public val classWeekDates: List<ClassWeekDate>? = null,
) {
    public val courses: List<com.gdutday.core.model.Course> get() = normalization.courses
}

/**
 * 本科教务系统（jxfw.gdut.edu.cn）客户端。
 *
 * 一个实例绑定**一个会话**：构造时用 [GdutSession.cookies] 还原 [SessionCookieJar]，
 * 之后所有请求自动带 cookie，服务端下发的新 cookie 也会自动收下。
 * 会话更新后（重新登录）应当**新建实例**，而不是复用。
 *
 * 调用方在一批请求结束后应该用 [currentCookies] 取出最新 cookie 回写持久化存储 ——
 * jxfw 会轮换 JSESSIONID，不回写的话下次冷启动就得重新登录。
 *
 * ## 线程模型
 *
 * 全部方法阻塞，必须在 IO 线程调用。
 *
 * @param httpClient 外部注入。**不要在内部 new** —— Android 侧要统一配置超时/TLS/DNS/日志，
 *   测试侧要能换成 MockWebServer 的 client。
 */
public class JxfwClient(
    httpClient: OkHttpClient,
    session: GdutSession,
    private val config: JxfwConfig = JxfwConfig(),
) {

    /** 从会话还原的 cookie jar，请求过程中会被服务端更新。 */
    public val cookieJar: SessionCookieJar = SessionCookieJar(session.cookies)

    /** 端点地址来源。测试里指向 MockWebServer。 */
    private val hosts: GdutHosts = config.hosts

    private val client: OkHttpClient = httpClient.newBuilder()
        .cookieJar(cookieJar)
        // 业务接口不应该自动跟着 302 跑：被导回登录页正是"会话失效"的信号，
        // 自动跟随会把它变成一坨登录页 HTML，反而难以判断。
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** 取出当前全部 cookie（含请求过程中服务端新下发的），用于回写持久化。 */
    public fun currentCookies(): List<StoredCookie> = cookieJar.snapshot()

    // ------------------------------------------------------------------ 会话探测

    /**
     * 检查会话是否仍然有效。
     *
     * 判据：`GET https://jxfw.gdut.edu.cn/` 不被 302 回统一认证。
     *
     * 这是一个**廉价**探测 —— 实测未登录时返回 `302` 且 `Content-Length: 0`，只有一个响应头。
     * 适合冷启动时先问一句，避免直接拉课表拿到一坨登录页 HTML 再回头判断。
     *
     * 网络异常一律返回 false（"不确定"按"不可用"处理，让上层去重新登录，比乐观放行安全）。
     */
    public fun isSessionValid(): Boolean = try {
        val request = Request.Builder()
            .url(hosts.jxfwHome)
            .asBrowserNavigation(referer = hosts.jxfwHome, sameSite = "none")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            when {
                response.code == 200 -> true
                response.isRedirect -> {
                    val location = response.header("Location").orEmpty()
                    !location.contains(hosts.authserverHost, ignoreCase = true)
                }
                else -> false
            }
        }
    } catch (e: IOException) {
        false
    }

    // ------------------------------------------------------------------ 学期

    /**
     * 取学期列表与当前学期。
     *
     * `GET https://jxfw.gdut.edu.cn/xsksap!ksapList.action`
     *
     * @throws GdutException.SessionExpired 会话失效
     * @throws GdutException.Parse 页面结构变了
     */
    public fun fetchTermList(): JxfwTermParser.TermList {
        val body = get(
            url = hosts.jxfwTermList,
            referer = hosts.jxfwDefaultReferer,
            what = "学期列表",
            allowHtml = true,
        )
        return JxfwTermParser.parse(body)
    }

    // ------------------------------------------------------------------ 课表

    /**
     * 抓取某学期的课表。
     *
     * [ScheduleEndpoint.AUTO] 策略：
     * 1. 先试 `getDataList`（按周返回，能给出"每周对应的教室"与授课内容）
     * 2. 它抛异常或返回空 → 回退 `xsAllKbList`（一次请求拿全，但只有整学期的教室列表）
     * 3. 两个都失败 → 抛 [GdutException.Parse]，detail 里**同时附上两次的失败原因**
     *
     * 之所以优先 `getDataList`：只有它按周返回 `jxcdmc`，才能把"某门课第几周在哪个教室"
     * 还原出来；`xsAllKbList` 的 `jxcdmcs` 是整个学期的教室列表，无法对应到具体周次。
     * 它同时提供 `sknrjj`（授课内容），详情页缺的也是这个字段。
     *
     * 代价是分页要多发几次请求；但 [JxfwClient] 会读 `total` 并翻页取满，
     * 不会像旧后端那样固定 `rows=300` 静默截断。设置里可以强制指定其中一个，便于排查。
     *
     * @throws GdutException.SessionExpired 会话失效
     * @throws GdutException.EmptySchedule 两个接口都返回空
     * @throws GdutException.Parse 两个接口都失败
     */
    public fun fetchSchedule(term: Term): ScheduleFetchResult {
        // 班级课表同步源：直接走班级接口，不走个人课表回退链
        val classCode = config.classCode
        if (!classCode.isNullOrBlank()) {
            val preferred = when (config.scheduleEndpoint) {
                ScheduleEndpoint.ALL_KB_LIST -> ScheduleEndpoint.CLASS_SCHEDULE_ALL_KB_LIST
                ScheduleEndpoint.DATA_LIST -> ScheduleEndpoint.CLASS_SCHEDULE_DATA_LIST
                else -> ScheduleEndpoint.AUTO
            }
            return fetchClassSchedule(term, classCode, preferred)
        }

        var firstError: Throwable? = null

        if (config.scheduleEndpoint != ScheduleEndpoint.ALL_KB_LIST) {
            try {
                val result = fetchViaDataList(term)
                if (result.rows.isNotEmpty()) return result
                firstError = GdutException.EmptySchedule(term)
            } catch (e: GdutException) {
                // 会话失效不该被回退逻辑吞掉 —— 换接口也一样会失效，直接抛出去让上层重登
                if (e.shouldRetryLogin) throw e
                firstError = e
            }
        }

        if (config.scheduleEndpoint != ScheduleEndpoint.DATA_LIST) {
            try {
                return fetchViaAllKbList(term)
            } catch (e: GdutException) {
                if (e.shouldRetryLogin) throw e
                if (firstError != null) {
                    throw GdutException.Parse(
                        what = "课表",
                        snippet = "两个接口都失败了。\n" +
                            "[getDataList] ${firstError.message}\n" +
                            "[xsAllKbList] ${e.message}",
                        cause = e,
                    )
                }
                throw e
            }
        }

        throw firstError ?: GdutException.EmptySchedule(term)
    }

    private fun fetchViaAllKbList(term: Term): ScheduleFetchResult {
        val url = buildUrl(hosts.jxfwScheduleAllKbList, JxfwScheduleParser.allKbListQuery(term))
        val body = get(
            url = url,
            // ⚠ 这个 Referer 是必须的，F# 源码里专门标了 "// TODO: 重要！需要记录"
            referer = JxfwScheduleParser.allKbListReferer(hosts),
            what = "课表（xsAllKbList）",
            allowHtml = true,
        )
        val rows = JxfwScheduleParser.parseAllKbList(body, term)
        val outcome = CourseNormalizer.normalize(rows, term)
        return ScheduleFetchResult(
            rows = rows,
            endpoint = ScheduleEndpoint.ALL_KB_LIST,
            pagesFetched = 1,
            normalization = outcome,
            warnings = buildList {
                addAll(outcome.warnings)
                if (outcome.hasDropped) add("xsAllKbList 丢弃了 ${outcome.droppedRows} 行: ${outcome.dropReasons}")
            },
        )
    }

    private fun fetchViaDataList(term: Term): ScheduleFetchResult {
        val all = mutableListOf<RawScheduleRow>()
        var page = 1
        var pagesFetched = 0
        var declaredTotal = -1
        val warnings = mutableListOf<String>()

        while (page <= config.maxPages) {
            val body = postForm(
                url = hosts.jxfwScheduleDataList,
                params = JxfwScheduleParser.dataListForm(term, page, config.rowsPerPage),
                referer = JxfwScheduleParser.dataListReferer(hosts),
                what = "课表（getDataList 第 $page 页）",
            )
            val parsed = JxfwScheduleParser.parseDataListPage(body, term)
            pagesFetched++
            all += parsed.rows
            if (declaredTotal < 0) declaredTotal = parsed.total

            val done = when {
                parsed.rawRowCount == 0 -> true                  // 本页一条都没有 → 到底了
                declaredTotal in 0..all.size -> true             // 已取够 total
                parsed.rawRowCount < config.rowsPerPage -> true  // 本页不满 → 最后一页
                else -> false
            }
            if (done) break
            page++
        }

        if (page > config.maxPages) {
            warnings += "课表翻页达到上限 ${config.maxPages} 页（服务端声明 total=$declaredTotal，已取 ${all.size} 条），可能未取全"
        } else if (declaredTotal > all.size) {
            warnings += "课表条数不足：服务端声明 $declaredTotal 条，实际取到 ${all.size} 条"
        }

        if (all.isEmpty()) throw GdutException.EmptySchedule(term)

        val outcome = CourseNormalizer.normalize(all, term)
        warnings += outcome.warnings
        if (outcome.hasDropped) warnings += "getDataList 丢弃了 ${outcome.droppedRows} 行: ${outcome.dropReasons}"

        return ScheduleFetchResult(
            rows = all,
            endpoint = ScheduleEndpoint.DATA_LIST,
            pagesFetched = pagesFetched,
            normalization = outcome,
            warnings = warnings,
        )
    }

    /**
     * 抓取某学期的**班级课表**（同步源为"班级课表"时由 [fetchSchedule] 调用）。
     *
     * 与个人课表的 AUTO 策略同构：
     * 1. 先试 `getKbRq`（JSON，带周日期 `rows[1]`，含 `sknrjj`）；
     * 2. 它抛异常或返回空 → 回退 `xsAllKbList`（HTML 内嵌 `var kbxx`，全学期聚合，无周日期）；
     * 3. 两个都失败 → 抛 [GdutException.Parse]，detail 里同时附上两次的失败原因。
     *
     * @param preferredEndpoint 强制只用其中一个接口（便于排查），默认 AUTO。
     * @throws GdutException.SessionExpired 会话失效
     * @throws GdutException.EmptySchedule 两个接口都返回空
     * @throws GdutException.Parse 两个接口都失败
     */
    public fun fetchClassSchedule(
        term: Term,
        bjdm: String,
        preferredEndpoint: ScheduleEndpoint = ScheduleEndpoint.AUTO,
    ): ScheduleFetchResult {
        require(bjdm.isNotBlank()) { "班级代码 bjdm 不能为空" }
        var firstError: Throwable? = null

        if (preferredEndpoint != ScheduleEndpoint.CLASS_SCHEDULE_ALL_KB_LIST) {
            try {
                val result = fetchClassViaGetKbRq(term, bjdm)
                if (result.rows.isNotEmpty()) return result
                firstError = GdutException.EmptySchedule(term)
            } catch (e: GdutException) {
                // 会话失效不该被回退逻辑吞掉 —— 换接口也一样会失效，直接抛出去让上层重登
                if (e.shouldRetryLogin) throw e
                firstError = e
            }
        }

        if (preferredEndpoint != ScheduleEndpoint.CLASS_SCHEDULE_DATA_LIST) {
            try {
                return fetchClassViaAllKbList(term, bjdm)
            } catch (e: GdutException) {
                if (e.shouldRetryLogin) throw e
                if (firstError != null) {
                    throw GdutException.Parse(
                        what = "班级课表",
                        snippet = "两个接口都失败了。\n" +
                            "[getKbRq] ${firstError.message}\n" +
                            "[xsAllKbList] ${e.message}",
                        cause = e,
                    )
                }
                throw e
            }
        }

        throw firstError ?: GdutException.EmptySchedule(term)
    }

    private fun fetchClassViaGetKbRq(term: Term, bjdm: String): ScheduleFetchResult {
        val url = buildUrl(
            hosts.jxfwClassScheduleGetKbRq,
            JxfwScheduleParser.classScheduleGetKbRqQuery(term, bjdm),
        )
        val body = get(
            url = url,
            referer = JxfwScheduleParser.classScheduleGetKbRqReferer(hosts),
            what = "班级课表（getKbRq）",
            allowHtml = false,
        )
        val parsed = JxfwScheduleParser.parseClassScheduleGetKbRq(body, term)
        val outcome = CourseNormalizer.normalize(parsed.rows, term)
        return ScheduleFetchResult(
            rows = parsed.rows,
            endpoint = ScheduleEndpoint.CLASS_SCHEDULE_DATA_LIST,
            pagesFetched = 1,
            normalization = outcome,
            warnings = buildList {
                addAll(outcome.warnings)
                if (outcome.hasDropped) add("getKbRq 丢弃了 ${outcome.droppedRows} 行: ${outcome.dropReasons}")
            },
            classWeekDates = parsed.weekDates,
        )
    }

    private fun fetchClassViaAllKbList(term: Term, bjdm: String): ScheduleFetchResult {
        val url = buildUrl(
            hosts.jxfwClassScheduleAllKbList,
            JxfwScheduleParser.classScheduleAllKbListQuery(term, bjdm),
        )
        val body = get(
            url = url,
            referer = JxfwScheduleParser.classScheduleAllKbListReferer(hosts),
            what = "班级课表（xsAllKbList）",
            allowHtml = true,
        )
        val rows = JxfwScheduleParser.parseClassScheduleAllKbList(body, term)
        val outcome = CourseNormalizer.normalize(rows, term)
        return ScheduleFetchResult(
            rows = rows,
            endpoint = ScheduleEndpoint.CLASS_SCHEDULE_ALL_KB_LIST,
            pagesFetched = 1,
            normalization = outcome,
            warnings = buildList {
                addAll(outcome.warnings)
                if (outcome.hasDropped) add("xsAllKbList 丢弃了 ${outcome.droppedRows} 行: ${outcome.dropReasons}")
            },
        )
    }

    // ------------------------------------------------------------------ 考试

    /**
     * 抓取考试安排，并顺带探测校区（[JxfwExamParser.Outcome.campusHint]）。
     *
     * 考试安排为空是**正常**情况（大一上学期、非考试周），不抛 [GdutException.EmptySchedule]。
     */
    public fun fetchExams(term: Term): JxfwExamParser.Outcome {
        val body = postForm(
            url = hosts.jxfwExamDataList,
            params = JxfwExamParser.form(term, page = 1, rowsPerPage = config.rowsPerPage),
            referer = hosts.jxfwDefaultReferer,
            what = "考试安排",
        )
        return JxfwExamParser.parse(body, term)
    }

    // ------------------------------------------------------------------ 成绩

    /**
     * 抓取成绩。
     *
     * @param term null = 查全部学期。⚠ 这会触发「劳动教育」bug，
     *   由 [JxfwConfig.patchLaborEducation] 自动兜底，见 [JxfwGradeParser]。
     */
    public fun fetchGrades(term: Term? = null): JxfwGradeParser.Outcome {
        var outcome = requestGrades(term, page = 1)

        // 翻页：每学期约 10 门 × 8 个学期 = 80 行，200/页通常一次就够，但仍留翻页以防万一
        if (outcome.total > outcome.grades.size) {
            val all = outcome.grades.toMutableList()
            var page = 2
            while (all.size < outcome.total && page <= config.maxPages) {
                val more = requestGrades(term, page)
                if (more.grades.isEmpty()) break
                all += more.grades
                page++
            }
            outcome = outcome.copy(grades = all, summaries = JxfwGradeParser.summarize(all))
        }

        // 「劳动教育」兜底重查
        if (config.patchLaborEducation && term == null && outcome.needsLaborEducationPatch.isNotEmpty()) {
            for (termCode in outcome.needsLaborEducationPatch) {
                val patchTerm = Term.parse(termCode) ?: continue
                val patched = runCatching { requestGrades(patchTerm, page = 1) }.getOrNull() ?: continue
                outcome = JxfwGradeParser.mergeLaborEducationPatch(outcome, patched.grades, termCode)
            }
        }

        return outcome
    }

    private fun requestGrades(term: Term?, page: Int): JxfwGradeParser.Outcome {
        val body = postForm(
            url = hosts.jxfwScoreDataList,
            params = JxfwGradeParser.form(term, page, config.rowsPerPage),
            referer = hosts.jxfwDefaultReferer,
            what = "成绩",
        )
        return JxfwGradeParser.parse(body)
    }

    // ------------------------------------------------------------------ HTTP 基础设施

    private fun get(url: String, referer: String, what: String, allowHtml: Boolean): String {
        val builder = Request.Builder().url(url).asBrowserXhr(referer)
        // xsAllKbList / 学期列表返回的是完整 HTML 页面而不是 XHR JSON，
        // Accept 头要能接受 text/html，否则服务端可能回 406
        if (allowHtml) builder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        return execute(builder.get().build(), url, what, allowHtml)
    }

    private fun postForm(url: String, params: Map<String, String>, referer: String, what: String): String {
        val request = Request.Builder()
            .url(url)
            .asBrowserXhr(referer)
            .header("Origin", originOf(url))
            .post(FormFields().addAll(params).toRequestBody())
            .build()
        return execute(request, url, what, allowHtml = false)
    }

    /**
     * 执行请求并做统一的错误归一。
     *
     * 检查顺序很重要：
     * 1. **先看是不是被 3xx 导回登录页** —— 这是会话失效最明确的信号。
     *    不先查这个的话，跟随重定向后会拿到一坨登录页 HTML，被误判成"接口改版"。
     * 2. 再看状态码（401/403 → 会话失效；其它非 200 → Http）。
     * 3. 最后看响应体：200 + HTML 也可能是会话失效（有些接口不 302 而是直接渲染登录页）。
     */
    private fun execute(request: Request, url: String, what: String, allowHtml: Boolean): String {
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw GdutException.Network(
                detail = "请求$what 失败($url): ${e.javaClass.simpleName}: ${e.message}",
                cause = e,
            )
        }

        if (!response.isRedirect) {
            return response.use { finish(it, url, what, allowHtml) }
        }

        val location = response.header("Location").orEmpty()
        response.close()

        if (looksLikeLoginRedirect(location)) {
            throw GdutException.SessionExpired(detail = "请求$what 被 ${response.code} 导到 $location")
        }

        // 非登录类重定向（http→https、路径规整化等）：跟一次就好。
        // 业务接口正常不该有这种跳转，所以限制 5 跳并在超限时抛 TooManyRedirects。
        val resolved = request.url.resolve(location)
            ?: throw GdutException.Parse(what = what, snippet = "无法解析重定向地址 '$location'（来自 $url）")
        // 307/308 要求**原样重放**方法与 body：POST 表单被 307 转走时降级为 GET
        // 会丢掉整个表单，服务端只看到空参数，错误会被误报成 Parse。
        // 301/302/303 维持老约定：降级为 GET。
        val preserveBody = response.code == 307 || response.code == 308
        val followRequest = Request.Builder()
            .url(resolved)
            .asBrowserXhr(request.header("Referer") ?: hosts.jxfwHome)
            .apply {
                val body = request.body
                if (preserveBody && body != null) {
                    header("Origin", originOf(resolved.toString()))
                    post(body)
                } else {
                    get()
                }
            }
            .build()
        val follower = RedirectFollower(client, maxHops = 5)
        val followed = follower.follow(followRequest)
        return followed.response.use { finish(it, url, what, allowHtml) }
    }

    private fun looksLikeLoginRedirect(location: String): Boolean =
        location.contains(hosts.authserverHost, ignoreCase = true) ||
            location.contains("/authserver", ignoreCase = true) ||
            location.contains("ssoLogin", ignoreCase = true) ||
            location.contains("/login", ignoreCase = true)

    private fun finish(response: Response, url: String, what: String, allowHtml: Boolean): String {
        if (response.code == 401 || response.code == 403) {
            throw GdutException.SessionExpired(detail = "请求$what 返回 ${response.code}")
        }
        if (response.code != 200) {
            throw GdutException.Http(response.code, url, detail = "请求$what")
        }
        val body = try {
            response.body.string()
        } catch (e: IOException) {
            throw GdutException.Network(detail = "读取$what 响应体失败: ${e.message}", cause = e)
        }
        if (body.isBlank()) {
            throw GdutException.Parse(what = what, snippet = "响应体为空 ($url)")
        }
        if (!allowHtml && LenientJson.looksLikeHtml(body)) {
            // 200 + HTML：会话失效最常见，其次才是接口改版
            val isAuthPage = body.contains("pwdEncryptSalt", ignoreCase = true) ||
                body.contains(hosts.authserverHost, ignoreCase = true)
            if (isAuthPage) throw GdutException.SessionExpired(detail = "请求$what 返回了登录页 HTML")
            throw GdutException.Parse(
                what = what,
                snippet = "期望 JSON 但收到 HTML。${LenientJson.snippet(body, 300)}",
            )
        }
        return body
    }

    private fun buildUrl(base: String, query: Map<String, String>): String {
        if (query.isEmpty()) return base
        val sb = StringBuilder(base)
        sb.append(if (base.contains('?')) '&' else '?')
        query.entries.forEachIndexed { i, (k, v) ->
            if (i > 0) sb.append('&')
            sb.append(FormFields.encodeComponent(k)).append('=').append(FormFields.encodeComponent(v))
        }
        return sb.toString()
    }
}

/**
 * 教务系统**直登**（绕过统一认证）。
 *
 * 这是统一认证触发滑块验证时的**逃生通道**，也是旧小程序 `login-edu.vue`
 * （页面上写着"20级点这里:使用教务系统登录"）走的路径。
 *
 * ## 流程
 *
 * ```
 * ① GET  https://jxfw.gdut.edu.cn/yzm?d=<毫秒时间戳>
 *        → 200, Content-Type: image/jpeg;charset=UTF-8, JPEG 140×60
 *          Set-Cookie: JSESSIONID=...; Path=/; Secure; HttpOnly
 *        （?d= 是防缓存，每次必须不同）
 * ② 用户看图填验证码
 * ③ POST https://jxfw.gdut.edu.cn/new/login
 *        Cookie: JSESSIONID=<①拿到的>
 *        Content-Type: application/x-www-form-urlencoded
 *        body: account=<学号>&pwd=<密码>&verifycode=<验证码>
 *        → HTTP 200, Content-Type: text/html;charset=utf-8   ⚠ 但 body 是 JSON
 *          成功 {"code":0,...}
 *          失败 {"code":-1,"data":null,"message":"验证码不正确"}
 * ```
 *
 * 三步全部实测过（②除外，那需要真人）：
 * - `GET /new/login` → **405 Method Not Allowed**，说明它是纯 API，没有配套 HTML 页面
 * - 用错误验证码 POST → `{"code":-1,"data":null,"message":"验证码不正确"}`
 *
 * ## ⚠ 未验证项
 *
 * **`pwd` 是否需要加密未经联网验证**（需要真实账号）。
 * 旧 Java 后端 `LoginServiceImpl.jxfwLogin` 直接发明文，本项目沿用。
 * 如果验证脚本报"账号或密码错误"但密码确实正确，
 * **第一个该怀疑的就是这里** —— 需要在浏览器里抓一次真实直登请求，看 `pwd` 字段的形态。
 * 若发现它被加密，加密逻辑很可能复用 `authserver` 那套（`encrypt.js` 是全站共用的），
 * 可以直接调 [com.gdutday.data.gdut.auth.AuthServerCrypto.encryptPassword] 试试。
 */
public object JxfwDirectLogin {

    /**
     * 取图形验证码。
     *
     * @param httpClient 外部注入的 OkHttpClient；本方法会派生一个绑定临时 CookieJar 的副本。
     * @return 图片字节 + **必须在 [login] 时原样回传的 cookie 串**
     */
    public fun fetchCaptcha(
        httpClient: OkHttpClient,
        hosts: GdutHosts = GdutHosts.PRODUCTION,
    ): CaptchaImage {
        val jar = SessionCookieJar()
        val client = httpClient.newBuilder()
            .cookieJar(jar)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        val url = hosts.jxfwCaptcha + "?d=" + System.currentTimeMillis()
        val request = Request.Builder()
            .url(url)
            .asBrowserNavigation(referer = hosts.jxfwHome, sameSite = "same-origin")
            // 浏览器里这是 <img src>，所以 dest=image / mode=no-cors
            .header("sec-fetch-dest", "image")
            .header("sec-fetch-mode", "no-cors")
            .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            .get()
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw GdutException.Network(detail = "取验证码失败: ${e.message}", cause = e)
        }

        return response.use {
            if (it.code != 200) {
                throw GdutException.Http(it.code, url, detail = "取验证码返回 ${it.code}")
            }
            val bytes = try {
                it.body.bytes()
            } catch (e: IOException) {
                throw GdutException.Network(detail = "读取验证码图片失败: ${e.message}", cause = e)
            }
            if (bytes.isEmpty()) throw GdutException.Parse(what = "验证码图片", snippet = "响应体为空")

            CaptchaImage(
                bytes = bytes,
                // 优先用 CookieJar 收到的 JSESSIONID 拼一个规范的 Cookie 头；
                // 拿不到再回退成原始 Set-Cookie 的首段（旧小程序是整个 Set-Cookie 头存下来回传，
                // 服务端通常只按第一个分号前解析，实测可行）
                cookieHeader = jar.snapshot()
                    .filter { c -> c.name.equals("JSESSIONID", ignoreCase = true) }
                    .joinToString("; ") { c -> "${c.name}=${c.value}" }
                    .ifEmpty { it.header("Set-Cookie")?.substringBefore(';') },
                contentType = it.header("Content-Type") ?: "image/jpeg",
            )
        }
    }

    /**
     * 用图形验证码直登教务系统。
     *
     * @param verifyCode 用户填写的验证码
     * @param captchaCookie [fetchCaptcha] 返回的 [CaptchaImage.cookieHeader]
     * @throws GdutException.BadCaptcha 验证码错误（可换一张重试）
     * @throws GdutException.BadCredentials 账号或密码错误
     * @throws GdutException.Parse 响应结构不认识
     */
    public fun login(
        httpClient: OkHttpClient,
        studentId: String,
        password: String,
        verifyCode: String,
        captchaCookie: String?,
        hosts: GdutHosts = GdutHosts.PRODUCTION,
    ): GdutSession {
        require(studentId.isNotBlank()) { "学号不能为空" }
        require(password.isNotEmpty()) { "密码不能为空" }
        require(verifyCode.isNotBlank()) { "验证码不能为空" }

        val jar = SessionCookieJar()
        val client = httpClient.newBuilder()
            .cookieJar(jar)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        val fields = FormFields()
            .add("account", studentId)
            .add("pwd", password)      // ⚠ 明文，见类注释的"未验证项"
            .add("verifycode", verifyCode)

        val builder = Request.Builder()
            .url(hosts.jxfwDirectLogin)
            .asBrowserXhr(referer = hosts.jxfwHome)
            .header("Origin", originOf(hosts.jxfwDirectLogin))
            .post(fields.toRequestBody())
        // 验证码与 JSESSIONID 绑定，必须显式带上（此时 jar 是空的，只能走手动 header）
        if (!captchaCookie.isNullOrBlank()) builder.header("Cookie", captchaCookie)

        val response = try {
            client.newCall(builder.build()).execute()
        } catch (e: IOException) {
            throw GdutException.Network(detail = "教务系统直登请求失败: ${e.message}", cause = e)
        }

        val body = response.use { r ->
            if (r.code != 200) {
                throw GdutException.Http(r.code, hosts.jxfwDirectLogin, detail = "直登返回 ${r.code}")
            }
            try {
                r.body.string()
            } catch (e: IOException) {
                throw GdutException.Network(detail = "读取直登响应失败: ${e.message}", cause = e)
            }
        }

        // ⚠ Content-Type 是 text/html，但 body 是 JSON，所以不能看响应头判断
        val json = LenientJson.parseObjectOrNull(body)
            ?: throw GdutException.Parse(
                what = "教务系统直登响应",
                snippet = LenientJson.snippet(body, 300),
            )

        val code = json.int("code")
        val message = json.str("message").trim()
        if (code != 0) {
            val detail = "code=$code message='$message' body=${LenientJson.snippet(body, 200)}"
            if (message.contains("验证码")) throw GdutException.BadCaptcha(message.ifEmpty { null })
            throw GdutException.BadCredentials(serverMessage = message.ifEmpty { null }, detail = detail)
        }

        val cookies = jar.snapshot()
        if (cookies.none { it.name.equals("JSESSIONID", ignoreCase = true) }) {
            throw GdutException.UnexpectedLoginResult(
                detail = "直登返回 code=0 但没有拿到 JSESSIONID。body=${LenientJson.snippet(body, 200)}",
            )
        }

        return GdutSession(
            cookies = cookies,
            profile = StudentProfile(
                studentId = studentId,
                userType = UserType.fromStudentId(studentId),
            ),
            method = LoginMethod.JXFW_DIRECT,
            hosts = hosts,
            diagnostics = buildString {
                append("登录方式=教务系统直登\n")
                append("响应=").append(LenientJson.snippet(body, 200)).append('\n')
                append("cookie=").append(cookies.joinToString { "${it.name}@${it.domain}" })
            },
        )
    }
}
