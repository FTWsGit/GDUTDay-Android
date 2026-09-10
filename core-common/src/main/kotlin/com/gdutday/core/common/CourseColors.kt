package com.gdutday.core.common

/**
 * 课程块配色。
 *
 * @property key 稳定标识，会持久化到 Room 的 `course.color_key` 列。
 *   **一旦发布就不能改** —— 改了会让所有已保存的自定义配色失效。
 * @property displayName 中文名，用于设置页的选色器。
 * @property argb 不透明 ARGB 值。透明度由用户在设置里统一调
 *   （旧小程序的 `courseBlockOpacity`，默认 80），所以这里不带 alpha 语义。
 */
public data class CourseColor(
    public val key: String,
    public val displayName: String,
    public val argb: Int,
)

/**
 * 课程调色板与自动配色。
 *
 * ## 数据来源
 *
 * 逐字抄自旧小程序 `staticData/colors.js` 的 `courseBlockColorListRGBA`，
 * **顺序也保持一致**（`Object.keys` 的插入序）：
 * red, olive, green, hiwamoegi, cyan, grey, blue, pink, yellow, mauve, purple。
 * 这样从旧版迁移过来的用户看到的配色不会有突兀变化。
 *
 * `hiwamoegi`（若草色）是原作者留下的一个日语色名，保留以维持 key 稳定。
 *
 * ## 自动配色为什么要"按课程名排序后分配"
 *
 * 旧实现是 `courseBlockColorList[mark++ % length]`，`mark` 随 `classData` 数组的
 * **遍历顺序**递增。数组顺序来自服务端返回顺序，一旦教务系统调整了排序，
 * 或者用户手动加了一门课插在中间，**全部课程的颜色都会重新洗牌**。
 *
 * 本项目改为：把去重后的课程名**排序**，按排序位次分配颜色。
 * 结果对服务端返回顺序完全免疫。代价是新增一门排序靠前的课时，
 * 它之后的课仍会顺移 —— 因此 Repository 会把首次分配的结果**持久化**到
 * `course.color_key`，之后只有全新课程才参与分配，老课程颜色永久固定。
 *
 * 用户手动指定的颜色（`Course.colorKey != null`）优先级最高，不参与自动分配。
 */
public object CourseColors {

    public val palette: List<CourseColor> = listOf(
        CourseColor("red", "绯红", 0xFFD75455.toInt()),
        CourseColor("olive", "橄榄", 0xFFE4C63F.toInt()),
        CourseColor("green", "青绿", 0xFF81C784.toInt()),
        CourseColor("hiwamoegi", "若草", 0xFF90B44B.toInt()),
        CourseColor("cyan", "天青", 0xFF8BD2D8.toInt()),
        CourseColor("grey", "雾灰", 0xFFA6BAB9.toInt()),
        CourseColor("blue", "浅蓝", 0xFFA4D6F9.toInt()),
        CourseColor("pink", "桃粉", 0xFFE16B8C.toInt()),
        CourseColor("yellow", "明黄", 0xFFF7D94C.toInt()),
        CourseColor("mauve", "藕荷", 0xFFDF94F0.toInt()),
        CourseColor("purple", "紫罗兰", 0xFFE576C3.toInt()),
    )

    /** 调色板大小。 */
    public val size: Int get() = palette.size

    /** 默认主题色。旧小程序 `config.js` 的 `defaultColor: 'pink'`。 */
    public val DEFAULT: CourseColor = palette.first { it.key == "pink" }

    private val byKeyMap: Map<String, CourseColor> = palette.associateBy { it.key }

    /** 按 key 查色；未知 key 返回 [DEFAULT]，不抛异常（数据库里可能有旧版本的 key）。 */
    public fun byKey(key: String?): CourseColor = key?.let { byKeyMap[it] } ?: DEFAULT

    /**
     * 为一组课程名分配颜色。
     *
     * @param courseNames 去重前的课程名集合
     * @param alreadyAssigned 已持久化的分配结果（`课程名 → 颜色 key`），这些不会被重新分配
     * @return `课程名 → CourseColor` 的完整映射，包含 alreadyAssigned 里的条目
     */
    public fun assign(
        courseNames: Collection<String>,
        alreadyAssigned: Map<String, String> = emptyMap(),
    ): Map<String, CourseColor> {
        val result = LinkedHashMap<String, CourseColor>()

        // 1. 已持久化的优先，原样保留
        for ((name, key) in alreadyAssigned) {
            if (name in courseNames || courseNames.isEmpty()) result[name] = byKey(key)
        }

        // 2. 新课程按排序位次分配，避开已占用的颜色（尽可能不重色）
        val pending = courseNames.filterNot { result.containsKey(it) }.distinct().sorted()
        if (pending.isNotEmpty()) {
            val usedKeys = result.values.map { it.key }.toMutableSet()
            // 先从没用过的颜色里挑，用完了再从头轮转
            val free = palette.filter { it.key !in usedKeys }
            pending.forEachIndexed { i, name ->
                result[name] = when {
                    free.isNotEmpty() -> free[i % free.size]
                    else -> palette[i % palette.size]
                }
            }
        }
        return result
    }

    /** 单门课程取名对应颜色的便捷方法。 */
    public fun forName(name: String, assignment: Map<String, CourseColor>): CourseColor =
        assignment[name] ?: DEFAULT
}
