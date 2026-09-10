package com.gdutday.feature.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gdutday.core.common.BlockStatus
import com.gdutday.core.common.CourseBlock
import com.gdutday.core.datastore.CourseTextColor
import com.gdutday.core.datastore.UserSettings
import com.gdutday.core.ui.LocalGdutDayColors
import com.gdutday.core.ui.ScheduleBlockText
import com.gdutday.core.ui.autoTextColorFor
import com.gdutday.core.ui.privacyMasked
import com.gdutday.core.ui.toComposeColor

private val BLOCK_SHAPE = RoundedCornerShape(6.dp)

/**
 * 网格里的一个课程/考试/自定义色块。
 *
 * 只承担"画一块"的职责，位置和尺寸由父级 [Layout] 决定（见 [WeekGridView]）。
 *
 * 视觉状态的叠加顺序（从底到顶）：
 * 1. 课程底色 × [UserSettings.courseBlockAlpha]；
 * 2. 考试 / 自定义的语义色罩层 [com.gdutday.core.ui.CourseBlockColors.examTint] /
 *    [com.gdutday.core.ui.CourseBlockColors.customTint]；
 * 3. 已上完且开关打开时的置灰遮罩；
 * 4. 正在上课的描边（描边不随透明度变化，否则高亮会被调淡）。
 *
 * 透明度只作用在背景上，**不影响文字**——把文字也调透明会让它在浅色底上彻底消失。
 */
@Composable
internal fun CourseBlockItem(
    block: CourseBlock,
    settings: UserSettings,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val courseBlockColors = LocalGdutDayColors.current.courseBlock
    val textColor = resolveTextColor(settings, block)

    val background = block.color.toComposeColor().copy(alpha = settings.courseBlockAlpha)
    val ongoingBorder = if (block.status == BlockStatus.ONGOING) {
        Modifier.border(2.dp, courseBlockColors.ongoingBorder, BLOCK_SHAPE)
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .clip(BLOCK_SHAPE)
            .background(background)
            .then(ongoingBorder)
            .clickable(onClick = onClick),
    ) {
        if (block.isExam) {
            Box(Modifier.fillMaxSize().background(courseBlockColors.examTint, BLOCK_SHAPE))
        }
        if (block.isCustom) {
            Box(Modifier.fillMaxSize().background(courseBlockColors.customTint, BLOCK_SHAPE))
        }
        if (block.status == BlockStatus.FINISHED && settings.dimFinishedCourses) {
            Box(Modifier.fillMaxSize().background(courseBlockColors.finishedScrim, BLOCK_SHAPE))
        }

        Column(Modifier.padding(horizontal = 3.dp, vertical = 1.dp)) {
            Text(
                text = block.course.name.maskIfNeeded(settings),
                style = ScheduleBlockText.courseName,
                color = textColor,
                maxLines = ScheduleBlockText.COURSE_NAME_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
            )
            if (settings.showClassroom && block.course.classroom.isNotBlank()) {
                Text(
                    // 教室不打码：打码后仍要能认出上课地点（见 privacyBlurEnabled 的注释）。
                    text = block.course.classroom,
                    style = ScheduleBlockText.detail,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (settings.showTeacher && block.course.teacher.isNotBlank()) {
                Text(
                    text = block.course.teacher.maskIfNeeded(settings),
                    style = ScheduleBlockText.detail,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 按设置决定字体色；`AUTO` 走 WCAG 相对亮度（纯函数在 core-ui）。 */
internal fun resolveTextColor(settings: UserSettings, block: CourseBlock): Color =
    when (settings.courseTextColor) {
        CourseTextColor.AUTO -> autoTextColorFor(block.color.argb)
        CourseTextColor.WHITE -> Color.White
        CourseTextColor.BLACK -> Color.Black
    }

/** 打码只针对姓名类文本；空串保持空串。 */
internal fun String.maskIfNeeded(settings: UserSettings): String =
    if (settings.privacyBlurEnabled) privacyMasked() else this
