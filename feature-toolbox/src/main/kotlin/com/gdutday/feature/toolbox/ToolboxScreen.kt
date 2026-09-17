package com.gdutday.feature.toolbox

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import com.gdutday.data.repository.AppContainer
import kotlinx.coroutines.flow.first

/**
 * 工具箱页。
 *
 * ## ⚠ 契约文件：签名被 `app` 的 NavHost 直接调用，不要改
 *
 * 页面结构为工具列表，每个工具是一个可点击的 [ListItem]。
 * 当前只有一个工具（图书馆二维码），后续新增工具只需在列表中追加条目。
 *
 * ## 无 ViewModel 的设计
 *
 * 本页不持有跨页面共享状态——二维码内容完全由 [AppContainer.libraryRepository]
 * 在对话框打开时按需生成，对话框关闭后状态随之释放。
 * 按项目约定，ViewModel 仅用于需要跨屏幕数据流的页面，
 * 这种"一次性工具宿主"用 composable-local 状态即可。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolboxScreen(
    container: AppContainer,
    modifier: Modifier = Modifier,
) {
    var showQrDialog by remember { mutableStateOf(false) }
    var showFreeRoomDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.toolbox_title)) },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxWidth(),
        ) {
            item(key = "qr_entry") {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.toolbox_qr_title)) },
                    supportingContent = { Text(stringResource(R.string.toolbox_qr_subtitle)) },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Filled.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    trailingContent = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    modifier = Modifier.clickable { showQrDialog = true },
                )
            }
            item(key = "free_room_entry") {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.toolbox_free_room_title)) },
                    supportingContent = { Text(stringResource(R.string.toolbox_free_room_subtitle)) },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Filled.MeetingRoom,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    trailingContent = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    modifier = Modifier.clickable { showFreeRoomDialog = true },
                )
            }
        }
    }

    if (showQrDialog) {
        LibraryQrDialog(
            container = container,
            onDismiss = { showQrDialog = false },
        )
    }

    if (showFreeRoomDialog) {
        FreeRoomDialog(
            container = container,
            onDismiss = { showFreeRoomDialog = false },
        )
    }
}

/**
 * 图书馆入馆二维码对话框。
 *
 * 打开时通过 [composableLocalState] 按需调用 [com.gdutday.data.repository.LibraryRepository.renderEntryQr]，
 * 将返回的 ARGB 像素数组转为 [androidx.compose.ui.graphics.ImageBitmap] 渲染。
 *
 * ## 亮度提升
 *
 * 闸机光学传感器对屏幕背光敏感，系统默认低背光下二维码识读率显著下降。
 * 进入 composition 时把宿主 Activity 窗口与 Dialog 自身窗口的
 * `screenBrightness` 都设为 [WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL]，
 * 离开时恢复 [WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE]。
 * 这是窗口级瞬时提亮，不改系统设置，也无需任何权限。
 *
 * ## 像素格式
 *
 * [com.gdutday.data.gdut.library.LibraryQr.renderArgb] 输出的 IntArray
 * 与 `Bitmap.Config.ARGB_8888` 的 32 位打包格式完全一致（黑点 = 0xFF000000，白底 = 0xFFFFFFFF），
 * 可直接喂给 `Bitmap.createBitmap(pixels, size, size, ARGB_8888)`，无需任何通道转换。
 * 返回的数组长度恒为 `sizePx × sizePx`（即请求时传入的 sizePx 参数的平方）。
 */
@Composable
private fun LibraryQrDialog(
    container: AppContainer,
    onDismiss: () -> Unit,
) {
    val qrState by produceState<QrState>(initialValue = QrState.Loading) {
        value = try {
            val customId = container.settingsStore.settings.first().libraryQrStudentId
            val pixels = container.libraryRepository.renderEntryQr(sizePx = 480, studentId = customId)
            if (pixels != null) {
                val bitmap = Bitmap.createBitmap(pixels, 480, 480, Bitmap.Config.ARGB_8888)
                QrState.Success(bitmap.asImageBitmap())
            } else {
                QrState.NotLoggedIn
            }
        } catch (_: Exception) {
            QrState.Error
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        // 必须在 Dialog 内容里调用：Dialog 窗口要通过 DialogWindowProvider 获取。
        BrightnessOverride()
        Card(
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // 标题行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.toolbox_qr_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    IconButton(onClick = onDismiss) {
                        Text(
                            text = stringResource(R.string.toolbox_close),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }

                // 二维码区域
                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .background(Color.White, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    when (val state = qrState) {
                        is QrState.Loading -> {
                            CircularProgressIndicator()
                        }
                        is QrState.Success -> {
                            androidx.compose.foundation.Image(
                                bitmap = state.imageBitmap,
                                contentDescription = stringResource(R.string.toolbox_qr_title),
                                modifier = Modifier.size(220.dp),
                            )
                        }
                        is QrState.NotLoggedIn -> {
                            Text(
                                text = stringResource(R.string.toolbox_qr_not_logged_in),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                        is QrState.Error -> {
                            Text(
                                text = stringResource(R.string.toolbox_qr_generate_failed),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }

                // 说明文字
                Text(
                    text = stringResource(R.string.toolbox_qr_caption),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // 关闭按钮
                TextButton(onClick = onDismiss) {
                    Text(text = stringResource(R.string.toolbox_close))
                }
            }
        }
    }
}

/** 二维码生成状态。 */
private sealed interface QrState {
    /** 正在生成。 */
    data object Loading : QrState

    /** 生成成功。 */
    data class Success(val imageBitmap: androidx.compose.ui.graphics.ImageBitmap) : QrState

    /** 未登录。 */
    data object NotLoggedIn : QrState

    /** 生成失败。 */
    data object Error : QrState
}

/**
 * composition 期间把宿主 Activity 窗口与 Dialog 自身窗口的亮度压到最大，
 * 离开时恢复原值。
 *
 * [Dialog] 在 Android 上是独立窗口，只提亮 Activity 窗口时弹窗自身仍按系统
 * 背光渲染，暗环境下二维码照旧灰暗 —— 所以两个窗口都要设置。
 * 本组件必须放在 Dialog 内容里：Dialog 的窗口要通过
 * `LocalView.parent as? DialogWindowProvider` 获取，出了 Dialog 就拿不到。
 * 找不到宿主窗口（预览环境等）时静默跳过。
 */
@Composable
private fun BrightnessOverride() {
    val view = androidx.compose.ui.platform.LocalView.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val activityWindow = remember(context) { context.findActivity()?.window }

    DisposableEffect(view, activityWindow) {
        val dialogWindow = (view.parent as? DialogWindowProvider)?.window
        val targets = listOfNotNull(activityWindow, dialogWindow)
        val previous = targets.map { it.attributes.screenBrightness }
        targets.forEach {
            it.attributes.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
        }
        onDispose {
            targets.forEachIndexed { index, window ->
                window.attributes.screenBrightness = previous[index]
            }
        }
    }
}

/** 逐层解开 ContextWrapper 找到宿主 Activity；找不到返回 null。 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
