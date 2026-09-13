package com.gdutday.feature.schedule

import com.gdutday.core.common.ScheduleGridBuilder
import com.gdutday.core.common.WeekGrid
import com.gdutday.data.repository.ScheduleUiState
import java.time.LocalDateTime

/** 周视图三页预渲染用的相邻周网格。 */
internal data class NeighborWeekGrids(
    val prev: WeekGrid?,
    val next: WeekGrid?,
)

/**
 * 生成选中周前/后一周的网格，供周视图滑动手势实时预览相邻周。
 *
 * 与 [ScheduleUiState.grid] 用同一套构建器（同作息表 / 学期历 / 配色），
 * 保证三页的几何（列数、色块尺寸）完全一致，滑动时不会跳变。
 */
internal fun buildNeighborWeekGrids(state: ScheduleUiState, now: LocalDateTime): NeighborWeekGrids =
    NeighborWeekGrids(
        prev = buildNeighborWeekGrid(state, -1, now),
        next = buildNeighborWeekGrid(state, 1, now),
    )

/**
 * 构建选中周 ±[offset] 的网格。
 *
 * 越界（第 1 周之前 / 最后一周之后）或缺学期历 / 作息表时返回 null，UI 渲染空白页占位。
 */
internal fun buildNeighborWeekGrid(
    state: ScheduleUiState,
    offset: Int,
    now: LocalDateTime,
): WeekGrid? {
    val timetable = state.timetable ?: return null
    val calendar = state.calendar ?: return null
    val week = state.selectedWeek + offset
    if (week !in 1..state.totalWeeks) return null
    val builder = ScheduleGridBuilder(timetable, calendar, state.colorAssignment)
    return builder.buildWeek(state.courses, state.exams, week, now)
}
