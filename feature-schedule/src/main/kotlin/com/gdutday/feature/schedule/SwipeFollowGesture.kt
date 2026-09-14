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
 * [drag]（调用方用 `graphicsLayer { translationX = ... }` 渲染），
 * 用户能看见页面被自己拖着走，位移不足时也能看见它弹回原位 ——
 * "能不能触发翻页"从猜测变成直接反馈。
 *
 * 松手时分两种情况：
 * - 位移 < threshold：spring 弹回 0；
 * - 位移 ≥ threshold：
 *   - [fullPageCommit] = false（单页模型，如日视图）：直接 [onSwipe] 提交，
 *     再把 [drag] 归零，沿用旧行为；
 *   - [fullPageCommit] = true（三页预渲染模型，如周视图）：**先把 [drag] 动画到
 *     一整页宽**（目标页完整滑入中心），动画结束后才 [onSwipe] 提交 + [drag] 归零。
 *     提交会让三页内容整体平移一个页宽，而"先滑满整页再提交"正好让这两步的位移
 *     抵消，目标页始终停在中心 —— 不会出现"内容先瞬移一个页宽再弹回"的闪现。
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
    fullPageCommit: Boolean = false,
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
                    when {
                        direction == 0 ->
                            scope.launch { drag.animateTo(0f, spring(dampingRatio = 0.8f)) }
                        fullPageCommit -> {
                            val page = size.width.toFloat()
                            scope.launch {
                                drag.animateTo(-direction * page, tween(240))
                                onSwipe(direction)
                                drag.snapTo(0f)
                            }
                        }
                        else -> {
                            onSwipe(direction)
                            scope.launch { drag.animateTo(0f, tween(120)) }
                        }
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
