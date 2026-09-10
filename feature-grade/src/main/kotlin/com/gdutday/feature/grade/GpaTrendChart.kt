package com.gdutday.feature.grade

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 绩点趋势折线图，用 Compose [Canvas] 手绘。
 *
 * ## 为什么不用图表库
 *
 * 数据点最多十几个，而 MPAndroidChart 会带来约 500KB 包体积，与"内存低、启动快"
 * 的项目目标相悖。手绘代码量不到 100 行，行为完全可控。
 *
 * ## 绘制内容
 *
 * - 坐标轴（左 + 下）；
 * - 数据点、连线、每点的数值标签；
 * - 横轴学期标签，**旋转 -35°** 避免相邻文字重叠；
 *   数据点较多时只标注首、尾与中间点，进一步降低密度。
 *
 * [points] 为空时什么都不画（调用方负责显示空状态）。
 */
@Composable
public fun GpaTrendChart(
    points: List<GpaTrendPoint>,
    modifier: Modifier = Modifier,
) {
    if (points.isEmpty()) return

    val textMeasurer = rememberTextMeasurer()
    val axisColor = MaterialTheme.colorScheme.outline
    val lineColor = MaterialTheme.colorScheme.primary
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val valueColor = MaterialTheme.colorScheme.onSurface
    val pointColor = MaterialTheme.colorScheme.primary

    // 超过 6 个点时只标注首尾与正中间，避免横轴变成一团墨。
    val labelledIndices = remember(points) {
        when {
            points.size <= 6 -> points.indices.toSet()
            else -> setOf(0, points.size / 2, points.lastIndex)
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(210.dp),
    ) {
        val leftPad = 34.dp.toPx()
        val rightPad = 16.dp.toPx()
        val topPad = 22.dp.toPx()
        val bottomPad = 52.dp.toPx()
        val plotWidth = (size.width - leftPad - rightPad).coerceAtLeast(1f)
        val plotHeight = (size.height - topPad - bottomPad).coerceAtLeast(1f)

        val rawMin = points.minOf { it.gpa }
        val rawMax = points.maxOf { it.gpa }
        val lo = (rawMin - 0.3).coerceAtLeast(0.0)
        val hi = rawMax + 0.3
        val span = (hi - lo).takeIf { it > 1e-6 } ?: 1.0

        fun xAt(index: Int): Float =
            leftPad + if (points.size == 1) plotWidth / 2f else plotWidth * index / (points.size - 1)

        fun yAt(value: Double): Float =
            topPad + plotHeight * (1.0 - (value - lo) / span).toFloat()

        // 坐标轴
        drawLine(axisColor, Offset(leftPad, topPad), Offset(leftPad, topPad + plotHeight), 1.dp.toPx())
        drawLine(
            axisColor,
            Offset(leftPad, topPad + plotHeight),
            Offset(leftPad + plotWidth, topPad + plotHeight),
            1.dp.toPx(),
        )

        // 折线
        if (points.size > 1) {
            val path = Path()
            points.forEachIndexed { i, p ->
                val x = xAt(i)
                val y = yAt(p.gpa)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, lineColor, style = Stroke(width = 2.dp.toPx()))
        }

        // 数据点 + 数值 + 学期标签
        points.forEachIndexed { i, p ->
            val x = xAt(i)
            val y = yAt(p.gpa)
            drawCircle(pointColor, radius = 4.dp.toPx(), center = Offset(x, y))

            val valueLayout = textMeasurer.measure(
                text = GradeLogic.formatGpa(p.gpa),
                style = TextStyle(fontSize = 10.sp, color = valueColor),
            )
            // 数值标签压在数据点上方，避免盖住折线。最顶部的点往下放一点。
            val valueY = (y - valueLayout.size.height - 4.dp.toPx()).coerceAtLeast(0f)
            drawText(
                textLayoutResult = valueLayout,
                topLeft = Offset(x - valueLayout.size.width / 2f, valueY),
            )

            if (i in labelledIndices) {
                val termLayout = textMeasurer.measure(
                    text = GradeLogic.shortTermLabel(p.termName),
                    style = TextStyle(fontSize = 9.sp, color = labelColor),
                )
                rotate(degrees = -35f, pivot = Offset(x, topPad + plotHeight)) {
                    drawText(
                        textLayoutResult = termLayout,
                        topLeft = Offset(x, topPad + plotHeight + 6.dp.toPx()),
                    )
                }
            }
        }
    }
}
