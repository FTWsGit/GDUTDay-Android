package com.gdutday.core.common

/**
 * 一段**连续**的节次。
 *
 * @property start 起始节，1-based
 * @property count 连续节数
 */
public data class SectionRun(
    public val start: Int,
    public val count: Int,
) {
    init {
        require(start >= 1) { "起始节必须 ≥ 1，收到 $start" }
        require(count >= 1) { "节数必须 ≥ 1，收到 $count" }
    }

    /** 结束节（含）。 */
    public val end: Int get() = start + count - 1

    /** 覆盖的节次集合。 */
    public val sections: Set<Int> get() = (start..end).toSet()

    override fun toString(): String = if (count == 1) "$start" else "$start-$end"
}

/**
 * 节次解析与连续段切分。
 *
 * ## 教务系统给了两种节次格式，都要处理
 *
 * | 字段 | 出现位置 | 格式 | 例子 | 含义 |
 * |---|---|---|---|---|
 * | `jcdm`  | `xsgrkbcx!getDataList.action` | **两位一组**拼接 | `"0102"` / `"01020506"` | 第 1,2 节 / 第 1,2,5,6 节 |
 * | `jcdm2` | `xsgrkbcx!xsAllKbList.action` | 逗号分隔整数 | `"1,2"` / `"1,2,5,6"` | 同上 |
 *
 * 旧小程序把它们统一编码成 `courseTime = "0102"` 这种两位拼接串存本地，
 * 再靠 `parseInt(time[0]==0 ? time[1] : time[0]+time[1])` 这种字符串戏法取起始节
 * （见 `schedule_store.js` 的 `MakeClass`），可读性极差且无法表达第 10 节以上。
 * 本项目在解析阶段就转成 [SectionRun]，之后全链路只用强类型。
 *
 * ## 为什么必须切分成"连续段"
 *
 * `{1,2,5,6}` 在课表网格上是**两个不相邻的色块**（上午两节 + 下午两节），
 * 不能画成一个从第 1 节拉到第 6 节的矩形 —— 那会盖住中午和下午前两节。
 * 旧 Java 后端没做切分（直接把 `"0102"` 转成 `"1,2"` 字符串），
 * 遇到这种"一天两段"的课就会画错。
 */
public object SectionRunSplitter {

    /** 节次上限。超过这个值的节次会被丢弃并记入 [ParseOutcome.dropped]。 */
    public const val MAX_SECTION: Int = 14

    /**
     * 解析结果。带上被丢弃的原始片段，便于诊断接口改版。
     */
    public data class ParseOutcome(
        public val sections: Set<Int>,
        public val dropped: List<String> = emptyList(),
    )

    /**
     * 解析 `jcdm` 的两位拼接格式：`"01020506"` → `{1,2,5,6}`。
     *
     * 规则：
     * - 长度必须是偶数；奇数长度时按两位切分，末尾落单的一位单独作为一个节次
     *   （容错，例如 `"01025"` 更可能是 `"01","02","5"` 而不是丢弃）。
     * - 每组转成整数，`"08"` → 8。
     * - 超出 [MAX_SECTION] 或 ≤ 0 的组被丢弃。
     */
    public fun parsePaired(raw: String?): ParseOutcome {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return ParseOutcome(emptySet())
        if (s.any { !it.isDigit() }) return parseCommaSeparated(raw) // 不是纯数字，退回逗号格式
        val dropped = mutableListOf<String>()
        val out = LinkedHashSet<Int>()
        var i = 0
        while (i < s.length) {
            val token = if (i + 2 <= s.length) s.substring(i, i + 2) else s.substring(i)
            val n = token.toIntOrNull()
            if (n == null || n < 1 || n > MAX_SECTION) {
                dropped += token
            } else {
                out += n
            }
            i += token.length
        }
        return ParseOutcome(out, dropped)
    }

    /**
     * 解析 `jcdm2` 的逗号分隔格式：`"1,2,5,6"` → `{1,2,5,6}`。
     *
     * 同时容错中文逗号、分号、空格分隔，以及 `"1-2"` 这种区间写法。
     */
    public fun parseCommaSeparated(raw: String?): ParseOutcome {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return ParseOutcome(emptySet())
        val dropped = mutableListOf<String>()
        val out = LinkedHashSet<Int>()
        for (token in s.split(',', '，', ';', '；', ' ', '\u3000').filter { it.isNotBlank() }) {
            val t = token.trim()
            if (t.contains('-')) {
                val parts = t.split('-')
                val from = parts.getOrNull(0)?.trim()?.toIntOrNull()
                val to = parts.getOrNull(1)?.trim()?.toIntOrNull()
                if (from != null && to != null && to >= from) {
                    (from..to).filter { it in 1..MAX_SECTION }.forEach { out += it }
                    continue
                }
                dropped += t
                continue
            }
            val n = t.toIntOrNull()
            if (n == null || n < 1 || n > MAX_SECTION) dropped += t else out += n
        }
        return ParseOutcome(out, dropped)
    }

    /**
     * 自动判别格式后解析。
     *
     * 判据：含分隔符 → 逗号格式；纯数字且长度为偶数 → 两位拼接格式；
     * 纯数字但长度为奇数 → 更可能是逗号格式漏了分隔符，仍按拼接格式容错处理。
     */
    public fun parseAuto(raw: String?): ParseOutcome {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return ParseOutcome(emptySet())
        val hasSeparator = s.any { it == ',' || it == '，' || it == ';' || it == '；' || it == '-' || it == ' ' }
        return if (hasSeparator) parseCommaSeparated(s) else parsePaired(s)
    }

    /**
     * 把节次集合切分成连续段，按起始节升序。
     *
     * `{1,2,5,6}` → `[(1,2), (5,2)]`
     * `{3}`       → `[(3,1)]`
     * `{}`        → `[]`
     */
    public fun splitIntoRuns(sections: Collection<Int>): List<SectionRun> {
        if (sections.isEmpty()) return emptyList()
        val sorted = sections.toSortedSet()
        val runs = mutableListOf<SectionRun>()
        var start = sorted.first()
        var prev = start
        for (s in sorted) {
            if (s == prev) continue
            if (s == prev + 1) {
                prev = s
                continue
            }
            runs += SectionRun(start, prev - start + 1)
            start = s
            prev = s
        }
        runs += SectionRun(start, prev - start + 1)
        return runs
    }

    /** 解析 + 切分一步到位。 */
    public fun parseAndSplit(raw: String?): List<SectionRun> = splitIntoRuns(parseAuto(raw).sections)
}
