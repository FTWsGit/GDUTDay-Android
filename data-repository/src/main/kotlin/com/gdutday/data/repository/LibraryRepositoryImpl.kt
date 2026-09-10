package com.gdutday.data.repository

import com.gdutday.core.datastore.SessionStore
import com.gdutday.data.gdut.library.LibraryQr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [LibraryRepository] 的实现：把学号变成 ARGB 像素。
 *
 * 没有任何网络调用 —— 二维码内容就是学号本身，纠错等级 H、静默区 1 模块，
 * 与旧 Java 后端的 `LiUtils.makeQRCode` 逐参数对齐（见 [LibraryQr] 的 KDoc）。
 * 旧后端要专门起一个个人服务器生成 PNG 再回传，本项目直接在端上算，
 * 图书馆地下书库没信号时也能刷。
 */
public class LibraryRepositoryImpl(
    private val sessionStore: SessionStore,
) : LibraryRepository {

    /**
     * 二维码的模块数（不含静默区）。**实算，不硬编码。**
     *
     * 这里曾经写死 `21`，理由是"本科学号恒为 10 位数字，H 级纠错的 QR version 1
     * 可容纳 17 位数字，所以一定是 21×21"。推理没错，但它把一条**关于输入格式的假设**
     * 埋进了常量：内容一旦超过 17 位数字（支持研究生、或图书馆改成动态码），
     * version 会跳到 2（25×25）甚至更高，而这个 21 会静默变成错的，
     * 表现为"二维码留白不对"这种没人会联想到根因的 bug。
     *
     * 详见 [LibraryQr.moduleCount] 的 KDoc。
     */
    override suspend fun moduleCount(): Int? = withContext(Dispatchers.Default) {
        val studentId = sessionStore.current()?.studentId?.takeIf { it.isNotBlank() }
            ?: return@withContext null
        LibraryQr.moduleCount(studentId)
    }

    override suspend fun renderEntryQr(sizePx: Int): IntArray? = withContext(Dispatchers.Default) {
        val studentId = sessionStore.current()?.studentId?.takeIf { it.isNotBlank() }
            ?: return@withContext null
        LibraryQr.renderArgb(content = studentId, width = sizePx, height = sizePx)
    }
}
