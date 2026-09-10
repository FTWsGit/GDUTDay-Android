package com.gdutday.core.model

/**
 * 用户身份类型。
 *
 * 判定方式（实测）：统一认证成功后请求
 * `https://authserver.gdut.edu.cn/personalInfo/common/getUserConf`，
 * 从返回的 HTML 里用正则 `<option value='(\d+)' selected>` 抠出学号，
 * **学号首位数字**即为身份：
 * - `3` → 本科生
 * - `2` → 研究生
 * - `0` → 教师
 *
 * 本项目当前只实现 [UNDERGRADUATE]。研究生走的是完全不同的 yjsxt/ehall 体系
 * （每个子应用要单独 POST 授权、返回的是具体时刻而非节次、需要连堂课合并），
 * 见 docs/01-gdut-protocol.md 的"研究生（未实现）"一节。
 */
public enum class UserType(
    /** 与旧 Java 后端 RoleConstant 对齐的数值，便于将来若要对接后端时直接复用。 */
    public val code: Int,
    /** 学号首位数字。 */
    public val studentIdPrefix: Char?,
) {
    UNDERGRADUATE(1, '3'),
    GRADUATE(2, '2'),
    TEACHER(3, '0'),

    /** 无法判定。 */
    UNKNOWN(-1, null),
    ;

    public companion object {
        /** 根据学号首位数字推断身份。 */
        public fun fromStudentId(studentId: String?): UserType {
            val first = studentId?.trim()?.firstOrNull() ?: return UNKNOWN
            return entries.firstOrNull { it.studentIdPrefix == first } ?: UNKNOWN
        }

        public fun fromCode(code: Int?): UserType =
            entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}
