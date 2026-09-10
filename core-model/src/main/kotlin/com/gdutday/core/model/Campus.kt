package com.gdutday.core.model

/**
 * 广工校区。
 *
 * 校区决定作息时间表（每节课的起止时刻），见 `core-common` 的 [com.gdutday.core.common.CampusTimetable]。
 * 四个校区的第 1~2 节开始时间不同（大学城/番禺 8:30，东风路/龙洞 8:15），第 3 节之后也各有 5~20 分钟差异，
 * 所以显示"下节课几点开始"时必须知道校区。
 *
 * 教务系统的课表接口并不直接返回校区字段，考试安排接口里有 `xqmc`（校区名称）。
 * 因此策略是：**优先从考试安排里探测，探测不到就让用户在设置里手选**，默认 [UNIVERSITY_CITY]（绝大多数本科生）。
 */
public enum class Campus(
    /** 教务系统 / 旧小程序里使用的中文名称，用于与接口返回值做匹配。 */
    public val displayName: String,
) {
    UNIVERSITY_CITY("大学城校区"),
    DONGFENG_ROAD("东风路校区"),
    LONGDONG("龙洞校区"),
    PANYU("番禺校区"),

    /**
     * 未知校区。作息表回退到 [UNIVERSITY_CITY]。
     * 存在这一项是为了让"接口没给校区"这件事在类型上显式可见，而不是悄悄塞一个默认值。
     */
    UNKNOWN(""),
    ;

    public companion object {
        /** 作息表默认使用的校区。 */
        public val DEFAULT: Campus = UNIVERSITY_CITY

        /**
         * 宽松匹配校区名。接口返回的可能是 "大学城"、"大学城校区"、"广州大学城" 等变体。
         * 匹配不到返回 [UNKNOWN]，不抛异常。
         */
        public fun fromRawName(raw: String?): Campus {
            if (raw.isNullOrBlank()) return UNKNOWN
            val normalized = raw.trim()
            // 先精确匹配 displayName
            entries.firstOrNull { it.displayName == normalized }?.let { return it }
            // 再按关键字包含匹配。顺序有讲究：番禺/龙洞/东风路 都是唯一关键字，
            // "大学城" 也是唯一关键字，不会互相误伤。
            return when {
                normalized.contains("大学城") -> UNIVERSITY_CITY
                normalized.contains("东风路") -> DONGFENG_ROAD
                normalized.contains("龙洞") -> LONGDONG
                normalized.contains("番禺") -> PANYU
                else -> UNKNOWN
            }
        }
    }
}
