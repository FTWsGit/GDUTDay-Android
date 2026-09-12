package com.gdutday.feature.schedule

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 课表背景图层。`uri == null` 时不渲染任何东西（纯色背景）。
 *
 * URI 来自 Photo Picker，授权已在 [com.gdutday.feature.settings.SettingsViewModel.onBackgroundPicked]
 * 里 `takePersistableUriPermission` 持久化，冷启动后依然可读。
 *
 * 解码是阻塞 I/O，放在 `Dispatchers.IO`；失败（图片被删除、授权被系统回收）时
 * 返回 null、保持纯色背景 —— 这是降级而不是崩溃，用户重新选一张图即可恢复。
 *
 * 模糊用 `Modifier.blur`（RenderEffect）：API < 31 上是静默 no-op，
 * 旧机型退化为不模糊的原图，不需要分支处理。
 */
@Composable
internal fun ScheduleBackground(
    uri: String?,
    blurDp: Int,
    modifier: Modifier = Modifier,
) {
    if (uri == null) return
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
                    BitmapFactory.decodeStream(input)
                }
            }.getOrNull()
        }
    }
    bitmap?.let { bmp ->
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .fillMaxSize()
                .blur(blurDp.coerceAtLeast(0).dp),
        )
    }
}
