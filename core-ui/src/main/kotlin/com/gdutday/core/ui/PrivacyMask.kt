package com.gdutday.core.ui

/** 打码使用的填充字符。用中文间隔号而不是 `*`，视觉上更像"被隐去"而非"缺字"。 */
private const val MASK_CHAR: Char = '·'

/**
 * 隐私打码：保留首尾字符，中间用 [MASK_CHAR] 填充，**结果与原串等长**。
 *
 * ## 为什么要等长
 *
 * 课表上课程名和老师名的宽度直接影响布局。若打码后长度变化，
 * 开关打码的瞬间整张课表的文字宽度会集体抖动（甚至撑破课程块），
 * 用户凭长度差还能反推出原文有几个字。等长填充是最稳妥的做法。
 *
 * ## 短串的处理
 *
 * 长度 ≤ 2 时无法在"保留首尾"和"隐藏内容"之间两全：
 * 2 个字符的首尾恰好就是全部内容，保留首尾等于没打码。
 * 本函数在这里选择**整串替换为同长度的 [MASK_CHAR]**，
 * 因为它的使用场景是公共场合防止旁人窥屏，隐私优先于可读性。
 *
 * 注意：**只对姓名类信息调用**。时间和教室按产品要求保持可读
 * （见 [com.gdutday.core.datastore.UserSettings.privacyBlurEnabled] 的说明），
 * 不要把这个函数套到教室/时间上。
 */
public fun String.privacyMasked(): String {
    if (isEmpty()) return this
    // 必须先取出原串长度：buildString 的 lambda 里 `length` 会解析成
    // StringBuilder 自身的当前长度，导致填充数量算错（漏掉全部掩码字符）。
    val len = length
    if (len <= 2) return MASK_CHAR.toString().repeat(len)
    return buildString(len) {
        append(this@privacyMasked.first())
        repeat(len - 2) { append(MASK_CHAR) }
        append(this@privacyMasked.last())
    }
}
