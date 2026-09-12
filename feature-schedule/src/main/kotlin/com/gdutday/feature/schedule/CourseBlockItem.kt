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
    val textColor = resolveTextColor(settings, block, courseBlockColors.finishedText)

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
            // 普通课程的时间由所占节次唯一决定（作息表固定），格子里的起止时刻是冗余信息，
            // 删掉腾出空间给课程名。考试的时间来自考试安排、未必与作息对齐，仍然保留。
            if (block.isExam) {
                Text(
                    text = "${block.startClock}-${block.endClock}",
                    style = ScheduleBlockText.timeRange,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = block.course.name,
                style = ScheduleBlockText.courseName,
                color = textColor,
                maxLines = ScheduleBlockText.COURSE_NAME_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
            )
            if (settings.showClassroom && block.course.classroom.isNotBlank()) {
                Text(
                    text = block.course.classroom,
                    style = ScheduleBlockText.detail,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (settings.showTeacher && block.course.teacher.isNotBlank()) {
                Text(
                    text = block.course.teacher,
                    style = ScheduleBlockText.detail,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 按设置决定字体色；`AUTO` 走 WCAG 相对亮度（纯函数在 core-ui）。
 *
 * 纯白在浅色色块上对比度过高、刺眼。未上课（UPCOMING）的色块把白色调暗一档，
 * 让"还没上的课"视觉上更柔和，同时不改变黑字与正在上课的语义。
 *
 * 已上完且开了置灰时，背景会被统一的灰色遮罩盖住，字色也统一换成主题里配好的
 * [finishedText]——否则浅色课程原本的黑字/白字落在灰底上对比度都可能不够。
 */
internal fun resolveTextColor(
    settings: UserSettings,
    block: CourseBlock,
    finishedText: Color,
): Color {
    if (block.status == BlockStatus.FINISHED && settings.dimFinishedCourses) {
        return finishedText
    }
    val base = when (settings.courseTextColor) {
        CourseTextColor.AUTO -> autoTextColorFor(block.color.argb)
        CourseTextColor.WHITE -> Color.White
        CourseTextColor.BLACK -> Color.Black
    }
    // 纯白 → 90% 白，仅对未上课的色块生效，避免破坏 AUTO 对深色块选白字的意图。
    if (base == Color.White && block.status == BlockStatus.UPCOMING) {
        return DIMMED_WHITE
    }
    return base
}

/** 未上课色块的柔化白。 */
private val DIMMED_WHITE = Color(0xE6FFFFFF)
