package com.gdutday.feature.schedule

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
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
 * 拖动过程中把累计位移实时写进 [drag]（调用方用 `graphicsLayer { translationX = ... }` 渲染），
 * 用户能看见页面被自己拖着走。
 *
 * 松手时**不做任何动画**：位移不足阈值直接归零，超过阈值立即提交翻页并把 [drag] 归零。
 * 早先用 spring/tween 做回弹和翻页过渡，动画期间手势协程被占住，连续滑动要等动画跑完
 * 才响应，手感是"滑一次卡一下"。这里改成同步归零后，两次滑动之间没有动画在占用指针序列。
 *
 * 归零放在 [scope]（rememberCoroutineScope）而非本协程：提交翻页会变更 pointerInput 的 key
 * （周次/日期），本协程随即被取消；放进 scope 归零才能跑完，页面不会停在半路。
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
                    val direction = when {
                        dragged > threshold -> -1
                        dragged < -threshold -> 1
                        else -> 0
                    }
                    if (direction != 0) onSwipe(direction)
                    scope.launch { drag.snapTo(0f) }
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
