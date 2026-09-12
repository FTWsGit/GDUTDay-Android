package com.gdutday.feature.schedule

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 课表左右翻页手势：**页面跟随手指**。
 *
 * 相比"积累位移超过阈值才在抬手时触发"的旧版，这里把累计位移实时写进
 * [drag]（调用方用 `graphicsLayer { translationX = drag.value }` 渲染），
 * 用户能看见页面被自己拖着走，位移不足时也能看见它弹回原位 ——
 * "能不能触发翻页"从猜测变成直接反馈。
 *
 * 松手时：
 * - 位移 < threshold：spring 弹回 0；
 * - 位移 ≥ threshold：先 [onSwipe] 提交翻页（新旧页的滑动过渡由既有
 *   AnimatedContent 完成），同时让 [drag] 快速归零。归零与 AnimatedContent
 *   的页面滑动叠加后整体仍是连续的单向运动，不会"先回弹再滑出"。
 *
 * 归零动画放在 [scope]（rememberCoroutineScope）而非本协程：提交翻页会变更
 * pointerInput 的 key（周次/日期），本协程随即被取消；放进 scope 归零动画
 * 才能活着跑完，页面不会停在半路。
 */
internal suspend fun PointerInputScope.detectHorizontalSwipe(
    drag: Animatable<Float, AnimationVector1D>,
    scope: CoroutineScope,
    touchSlop: Float,
    threshold: Float,
    onSwipe: (Int) -> Unit,
) {
    awaitEachGesture {
        awaitFirstDown()
        var dragged = 0f
        var directionLocked = false
        var isHorizontal = false
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull() ?: break
            if (!change.pressed) {
                if (isHorizontal) {
                    when {
                        dragged > threshold -> { onSwipe(-1); scope.launch { drag.animateTo(0f, tween(120)) } }
                        dragged < -threshold -> { onSwipe(1); scope.launch { drag.animateTo(0f, tween(120)) } }
                        else -> scope.launch { drag.animateTo(0f, spring(dampingRatio = 0.8f)) }
                    }
                }
                break
            }
            val dx = change.positionChange().x
            val dy = change.positionChange().y
            // 未锁定方向前不消费事件：纵向滚动 / 下拉刷新优先。
            if (!directionLocked && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                directionLocked = true
                isHorizontal = abs(dx) > abs(dy)
            }
            if (isHorizontal) {
                change.consume()
                dragged += dx
                scope.launch { drag.snapTo(dragged) }
            }
        }
    }
}
