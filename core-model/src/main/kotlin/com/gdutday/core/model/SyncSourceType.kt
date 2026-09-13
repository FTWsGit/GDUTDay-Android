package com.gdutday.core.model

/**
 * 课表同步源。用户在"同步源"里选择，同步时按它决定调个人还是班级接口。
 *
 * 切换是**替换**而不是合并：选班级课表后，个人课表数据不再同步进来；
 * 切回 [PERSONAL] 后的下一次同步恢复个人数据。
 */
public enum class SyncSourceType(public val displayName: String) {
    PERSONAL("个人课表"),
    CLASS_SCHEDULE("班级课表"),
    ;

    public companion object {
        /** 非法/缺失一律回退 [PERSONAL]，与字段默认值一致。 */
        public fun fromName(raw: String?): SyncSourceType =
            entries.firstOrNull { it.name == raw } ?: PERSONAL
    }
}
