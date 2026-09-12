package com.gdutday.feature.schedule

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
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
        // requireUnconsumed = false：Down 事件可能已被子级的 clickable 标记过，
        // 不能因为它被消费就放弃整个手势序列。
        awaitFirstDown(requireUnconsumed = false)
        var dragged = 0f
        var directionLocked = false
        var isHorizontal = false
        while (true) {
            // Initial pass：父级在子级（verticalScroll / clickable 都工作在 Main pass）
            // 之前先看到事件。这样横向锁定后在此消费位移，纵向滚动根本收不到 move，
            // 不会出现"先被纵向吞掉再判定横滑失败"的竞态。
            val event = awaitPointerEvent(PointerEventPass.Initial)
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
            // 斜率鉴别：突破 slop 后按 |dx| vs |dy| 一次性锁定方向。
            // 人手横滑必然带纵向偏角，只要横向分量占优就判横滑；
            // 反之立刻退出、不消费任何事件，把整段手势让给纵向滚动/下拉刷新。
            if (!directionLocked && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                directionLocked = true
                isHorizontal = abs(dx) > abs(dy)
                if (!isHorizontal) break
            }
            if (isHorizontal) {
                // 在 Initial pass 消费：子级在 Main pass 看到 isConsumed=true，
                // clickable 取消按压、verticalScroll 不再接管。
                change.consume()
                dragged += dx
                scope.launch { drag.snapTo(dragged) }
            }
        }
    }
}
