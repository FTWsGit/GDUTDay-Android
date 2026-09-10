package com.gdutday.data.gdut.jxfw

/**
 * 教务系统图形验证码。
 *
 * 实测（2026-09-10）：`GET https://jxfw.gdut.edu.cn/yzm?d=<毫秒时间戳>`
 * 返回 `200`、`Content-Type: image/jpeg;charset=UTF-8`、JPEG **140×60**、
 * 并下发 `Set-Cookie: JSESSIONID=...; Path=/; Secure; HttpOnly`。
 *
 * @property bytes JPEG 原始字节。UI 用 `BitmapFactory.decodeByteArray` 直接渲染，
 *   不需要 Base64 中转（旧小程序因为 `uni.request` 的限制才转成 base64 传给前端）。
 * @property cookieHeader
 *   **必须在提交登录时原样回传**的 Cookie 头值。
 *
 *   验证码图片与 JSESSIONID 绑定：服务端把答案存在那个会话里。
 *   不回传的话服务端找不到会话，无论填什么都返回"验证码不正确"。
 *   旧小程序的做法是把响应的整个 `Set-Cookie` 头存下来，登录时作为 `jSessionId` 传回后端；
 *   本项目直接把它作为一个标准 Cookie 头用，语义更清楚。
 * @property contentType 服务端给的类型，用于诊断（万一哪天改成 PNG）。
 */
public data class CaptchaImage(
    public val bytes: ByteArray,
    public val cookieHeader: String?,
    public val contentType: String = "image/jpeg",
) {
    public val sizeBytes: Int get() = bytes.size

    /** 实测宽度，UI 可以据此设定图片控件的宽高比（140:60 ≈ 7:3）。 */
    public companion object {
        public const val EXPECTED_WIDTH: Int = 140
        public const val EXPECTED_HEIGHT: Int = 60
        public const val EXPECTED_ASPECT_RATIO: Float = EXPECTED_WIDTH.toFloat() / EXPECTED_HEIGHT
    }

    // ByteArray 是引用相等，必须手写 equals/hashCode，否则 data class 的相等性判断是错的
    override fun equals(other: Any?): Boolean =
        other is CaptchaImage &&
            bytes.contentEquals(other.bytes) &&
            cookieHeader == other.cookieHeader &&
            contentType == other.contentType

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + (cookieHeader?.hashCode() ?: 0)
        result = 31 * result + contentType.hashCode()
        return result
    }

    override fun toString(): String =
        "CaptchaImage(${bytes.size} bytes, $contentType, cookie=${cookieHeader?.take(24)}…)"
}
