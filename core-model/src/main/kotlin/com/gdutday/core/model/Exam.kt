package com.gdutday.core.model

import java.time.LocalDate
import java.time.LocalTime

/**
 * 考试安排。
 *
 * 数据源：`POST https://jxfw.gdut.edu.cn/xsksap!getDataList.action`
 * 表单参数 `xnxqdm`(长码) `page=1` `rows=200` `sort=zc,xq,jcdm2` `order=asc`
 *
 * 原始字段对照（实测）：
 * ```
 * kcmc     课程名称        kcbh     课程编号（用于关联 Course）
 * ksrq     考试日期        kssj     考试时间，形如 "08:30--10:05"
 * kscdmc   考试场地名称    xqmc     校区名称   ← 探测 Campus 的唯一线索
 * kslbmc   考试类别名称    ksaplxmc 考试安排类型名称
 * ```
 *
 * 注意 `kssj` 的分隔符是**两个减号** `--`，不是普通的 `-`，
 * 也不是 `~`。旧小程序用 `split("--")`，这里保持一致但做了容错。
 */
public data class Exam(
    public val id: Long = 0L,
    public val term: Term,
    public val courseName: String,
    public val courseCode: String = "",
    public val date: LocalDate,
    /** 开始时间。接口偶尔不给，所以可空。 */
    public val startTime: LocalTime? = null,
    public val endTime: LocalTime? = null,
    public val classroom: String = "",
    public val campus: Campus = Campus.UNKNOWN,
    /** 考试类别，如 "正常考试"、"重修考试"。 */
    public val category: String = "",
    /** 考试安排类型，如 "集中安排"、"随堂考试"。 */
    public val arrangementType: String = "",
) {
    /** `"08:30--10:05"`；缺时间时返回 `"时间待定"`。 */
    public val timeDisplay: String
        get() {
            val s = startTime ?: return "时间待定"
            val e = endTime ?: return s.toString()
            return "$s--$e"
        }

    /** 距离今天还有几天。负数表示已考完。 */
    public fun daysUntil(today: LocalDate): Long = java.time.temporal.ChronoUnit.DAYS.between(today, date)

    public companion object {
        /**
         * 解析 `kssj`。实测格式为 `"08:30--10:05"`，同时容错 `"08:30-10:05"`、`"8:30~10:05"`。
         *
         * @return (开始, 结束)；解析不出开始时间则返回 (null, null)。
         */
        public fun parseTimeRange(raw: String?): Pair<LocalTime?, LocalTime?> {
            if (raw.isNullOrBlank()) return null to null
            val parts = raw.split("--", "~", "—", "-").map { it.trim() }.filter { it.isNotEmpty() }
            // 用 "-" 切分会把 "08:30" 切成 ["08:30"]（冒号不是分隔符），所以这里安全
            fun parse(t: String?): LocalTime? = t?.let {
                runCatching { LocalTime.parse(it.padTime()) }.getOrNull()
            }
            return parse(parts.getOrNull(0)) to parse(parts.getOrNull(1))
        }

        /** `"8:30"` → `"08:30"`，`LocalTime.parse` 不接受单位数小时。 */
        private fun String.padTime(): String {
            val idx = indexOf(':')
            if (idx < 0) return this
            val h = substring(0, idx)
            return if (h.length == 1) "0$this" else this
        }
    }
}
