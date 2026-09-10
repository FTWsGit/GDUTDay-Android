package com.gdutday.core.model

/**
 * 登录成功后确定的学生身份。
 *
 * 学号来源（两条路，取先成功者）：
 * 1. 用户输入的账号本身就是学号（本科生 10 位，首位 `3`）
 * 2. 统一认证成功后 `GET https://authserver.gdut.edu.cn/personalInfo/common/getUserConf`
 *    返回的 HTML 里用正则 `<option value='(\d+)' selected>` 抠出
 *
 * 姓名目前没有稳定接口：`getUserConf` 页面里有，但结构未在本地验证过，
 * 所以 [name] 允许为空，UI 上缺省显示学号。
 *
 * @property studentId 学号。图书馆入馆二维码的内容**就是学号本身**，见 `LibraryQr`。
 * @property userType 身份类型。当前只支持 [UserType.UNDERGRADUATE]。
 * @property name 姓名，可能拿不到。
 * @property campus 探测到的校区，可能为 [Campus.UNKNOWN]。
 */
public data class StudentProfile(
    public val studentId: String,
    public val userType: UserType = UserType.fromStudentId(studentId),
    public val name: String = "",
    public val campus: Campus = Campus.UNKNOWN,
) {
    public val displayName: String get() = name.ifBlank { studentId }

    public companion object {
        /** 本科生学号长度。旧小程序在登录页做了 `ID.length != 10` 的前置校验。 */
        public const val UNDERGRADUATE_ID_LENGTH: Int = 10

        /** 学号格式是否像本科生学号：10 位纯数字且首位为 3。 */
        public fun looksLikeUndergraduateId(raw: String?): Boolean {
            val s = raw?.trim().orEmpty()
            return s.length == UNDERGRADUATE_ID_LENGTH && s.all { it.isDigit() } && s.first() == '3'
        }
    }
}
