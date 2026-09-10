package com.gdutday.data.gdut.library

import com.gdutday.core.model.GdutException
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 图书馆入馆二维码 —— **完全本地生成，不需要任何网络请求**。
 *
 * ## 二维码内容就是学号本身
 *
 * 旧 Java 后端 `GdutDayServiceImpl.getLibQr` 的实现：
 * ```java
 * public String getLibQr(LibQrVO libQrVO) {
 *     return LiUtils.makeQRCode(libQrVO.getStuId(), libQrVO.getWidthStr(), libQrVO.getHeightStr());
 * }
 * // LiUtils.makeQRCode:
 * hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H);
 * hints.put(EncodeHintType.CHARACTER_SET, "utf-8");
 * hints.put(EncodeHintType.MARGIN, 1);
 * new MultiFormatWriter().encode(stuId, BarcodeFormat.QR_CODE, width, height, hints);
 * ```
 * 即：**QR 码编码的内容就是学号字符串**，纠错等级 H，静默区 1 模块。
 * 服务端只是把它渲染成 PNG 再转 Base64 传回来 —— 一次纯粹浪费流量的往返。
 *
 * 本项目直接在端上生成，收益：
 * - **离线可用**：图书馆地下书库经常没信号，这是实打实的体验提升
 * - **秒开**：不用等网络往返，也不用等 Base64 解码
 * - **省流量**：一张 PNG 几 KB，一天刷几次也是白花
 * - **不依赖第三方服务器**：旧后端的 `api.cerbur.top` 是个人服务器，随时可能挂
 *
 * ## 关于 ARGB 像素序
 *
 * 旧后端用 `BufferedImage.TYPE_INT_RGB` + `setRGB(x, y, 0xFF000000 / 0xFFFFFFFF)`，
 * 即**黑点 = 0xFF000000，白底 = 0xFFFFFFFF**。
 * Android 的 `Bitmap.Config.ARGB_8888` 用的是同一个 32 位打包格式，
 * 所以 [renderArgb] 的输出可以直接灌进 `Bitmap.createBitmap` + `setPixels`，
 * 无需任何通道转换。
 */
public object LibraryQr {

    /** 默认边长（像素）。旧小程序传的是从 UI 量出来的宽高，这里给个通用值。 */
    public const val DEFAULT_SIZE: Int = 512

    /** 前景色（黑）。 */
    public const val COLOR_DARK: Int = 0xFF000000.toInt()

    /** 背景色（白）。 */
    public const val COLOR_LIGHT: Int = 0xFFFFFFFF.toInt()

    /**
     * 生成二维码位矩阵。
     *
     * @param content 要编码的内容，图书馆入馆场景下就是学号
     * @param width 输出宽度（像素）
     * @param height 输出高度（像素）
     * @throws IllegalArgumentException 尺寸非正
     * @throws GdutException.Local 编码失败（内容过长或尺寸过小）
     */
    public fun encode(
        content: String,
        width: Int = DEFAULT_SIZE,
        height: Int = width,
    ): BitMatrix {
        require(content.isNotBlank()) { "二维码内容不能为空" }
        require(width > 0 && height > 0) { "尺寸必须为正: ${width}x$height" }

        // enumMap 而非 Hashtable：zxing 的 EncodeHintType 只要求 Map，
        // 旧后端用 Hashtable 是 Java 老习惯，没必要延续
        val hints = mapOf<EncodeHintType, Any>(
            // H 级纠错（30% 恢复能力）。图书馆闸机扫码环境差（屏幕反光、贴膜划痕），
            // 旧后端用的就是 H，沿用。
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            // 静默区 1 模块。标准建议 4，但屏幕显示时 1 已经够用，
            // 而且能在同样尺寸下让码点更大、更容易被闸机识别。旧后端也是 1。
            EncodeHintType.MARGIN to 1,
        )

        return try {
            MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, width, height, hints)
        } catch (e: WriterException) {
            throw GdutException.Local(
                message = "生成图书馆二维码失败",
                detail = "content长度=${content.length}, size=${width}x$height, ${e.message}",
                cause = e,
            )
        } catch (e: IllegalArgumentException) {
            throw GdutException.Local(
                message = "生成图书馆二维码失败",
                detail = "content长度=${content.length}, size=${width}x$height, ${e.message}",
                cause = e,
            )
        }
    }

    /**
     * 生成 ARGB 像素数组，可直接喂给
     * `Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)`
     * 或 `Bitmap.setPixels(...)`。
     *
     * @return 长度为 `matrix.width * matrix.height` 的 IntArray
     */
    public fun renderArgb(
        content: String,
        width: Int = DEFAULT_SIZE,
        height: Int = width,
    ): IntArray {
        val matrix = encode(content, width, height)
        return renderArgb(matrix)
    }

    public fun renderArgb(matrix: BitMatrix): IntArray {
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                pixels[row + x] = if (matrix[x, y]) COLOR_DARK else COLOR_LIGHT
            }
        }
        return pixels
    }

    // 说明：刻意不提供 PNG 编码。
    // 界面显示直接用 renderArgb() 灌进 Bitmap（省一次 PNG 编解码）；
    // 真要落盘/分享，在 Android 层用 Bitmap.compress(PNG) 即可，
    // 没必要让这个纯 JVM 模块背上图像编码的职责。

    /**
     * 给定内容在 H 级纠错下的**模块数**（QR 版本的边长，不含静默区）。
     *
     * ## 为什么要有这个函数
     *
     * UI 需要它来决定"码点该画多大、要不要额外留白"。
     * 之前 `LibraryRepositoryImpl` 里把这个值**硬编码成 21**，
     * 理由是"本科学号恒为 10 位数字，H 级 version 1 能装 17 位数字，所以一定是 21×21"。
     *
     * 那个推理本身没错，但它把一条**关于输入格式的假设**埋进了一个常量里：
     * 哪天研究生也支持了（学号规则不同）、或者图书馆改成扫"学号+日期"的动态码，
     * 内容一超过 17 位数字，version 就跳到 2（25×25）甚至更高，
     * 而那个 21 会静默地变成错的 —— UI 按错误的模块数算留白，
     * 表现为"二维码边距不对"这种没人会联想到根因的 bug。
     *
     * 所以改成实算。开销可以忽略：`Encoder.encode` 只做一次 Reed-Solomon 编码，
     * 不生成图像，微秒级。
     *
     * @return 模块数（21 / 25 / 29 …）；内容无法编码时返回 null，调用方自行兜底。
     */
    public fun moduleCount(content: String): Int? {
        if (content.isBlank()) return null
        return try {
            com.google.zxing.qrcode.encoder.Encoder
                .encode(content, ErrorCorrectionLevel.H, ENCODE_HINTS)
                .matrix
                ?.width
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 与 [encode] 共用的编码参数，抽出来保证 [moduleCount] 与真实编码结果一致。
     *
     * ⚠ 这里的 MARGIN 对 [moduleCount] 没有影响（Encoder 返回的是纯码点矩阵，
     * 静默区是在 `QRCodeWriter.renderResult` 阶段才加的），
     * 但共用同一个 hints 能避免"两处参数漂移"。
     */
    private val ENCODE_HINTS: Map<EncodeHintType, Any> = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
        EncodeHintType.CHARACTER_SET to "UTF-8",
        EncodeHintType.MARGIN to 1,
    )
}
