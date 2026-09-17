package com.gdutday.feature.toolbox

import com.gdutday.data.gdut.freeroom.FreeRoomBuilding
import com.gdutday.data.gdut.freeroom.RoomOccupancy
import com.gdutday.data.gdut.freeroom.UsageType

/**
 * 空闲教室页的纯逻辑层。
 *
 * 全部是输入 → 输出的纯函数，不碰网络与 Compose，跑 JVM 单测
 * （与 `LoginLogic` / `ExamLogic` 同一分工：ViewModel 只编排，计算在这里）。
 */
public object FreeRoomLogic {

    // ------------------------------------------------------------------ 楼栋分类

    /**
     * 楼栋类型。学校接口没有楼型字段，按名称关键词归类（60 栋实测名称见协议文档 §4.8）。
     * [OTHER] 兜底：名字对不上任何关键词的楼单独归一类，**不混进教学楼**。
     */
    public enum class BuildingKind(val displayName: String) {
        TEACHING("教学楼"),
        LAB("实验楼"),
        COLLEGE("学院楼"),
        PUBLIC("公共设施"),
        OTHER("其他"),
    }

    /**
     * 楼名 → 楼型。关键词按出现顺序匹配，一条楼名只归一类
     * （如"实验二号楼(城)"命中"实验"归实验楼，不再看"号"字）。
     */
    public fun buildingKind(name: String): BuildingKind = when {
        // "学院"/"馆"类（工学X号馆、理学馆、文法楼、设计工场、创新中心）
        listOf("工学", "理学", "文法", "设计", "创新") .any { it in name } -> BuildingKind.COLLEGE
        listOf("实验", "实验室").any { it in name } -> BuildingKind.LAB
        listOf("教学楼", "教学", "独立大课室", "综合楼").any { it in name } -> BuildingKind.TEACHING
        listOf("图书馆", "活动中心", "行政", "运动场", "体育", "舞蹈房", "公共场地", "停车场", "廊道").any { it in name } ->
            BuildingKind.PUBLIC
        else -> BuildingKind.OTHER
    }

    /**
     * 按楼型分组，组内按名称排序。顺序：教学楼 → 实验楼 → 学院楼 → 公共设施 → 其他。
     */
    public fun groupBuildings(
        buildings: List<FreeRoomBuilding>,
    ): List<Pair<BuildingKind, List<FreeRoomBuilding>>> =
        BuildingKind.entries
            .map { kind -> kind to buildings.filter { buildingKind(it.name) == kind }.sortedBy { it.name } }
            .filter { it.second.isNotEmpty() }

    // ------------------------------------------------------------------ 节次展开

    /** 一间教室某节课的状态。 */
    public enum class SlotState { FREE, CLASS, BORROWED }

    /** 一间教室一天的时间轴格。 */
    public data class Slot(
        /** 起始节（1-based，含）。 */
        public val startSection: Int,
        /** 结束节（含）。 */
        public val endSection: Int,
        public val state: SlotState,
        /** 该格对应的占用行（FREE 为 null）。 */
        public val occupancy: RoomOccupancy?,
    )

    /**
     * 把两位拼接节次的占用行展开成 1..12 节的状态数组，
     * 再合并相邻同状态格为连续段（`[1,1,2,2,0,0,…]` → `1-2节上课、3-4节上课、5-6节空闲`）。
     *
     * 占用行覆盖范围之外的节 = 空闲。同一节出现多行占用时**取先出现的行**
     * （实测一节最多一条排课；借用与排课冲突时以排课优先展示，数据层保证不重叠）。
     */
    public fun expandSlots(
        rows: List<RoomOccupancy>,
        totalSections: Int = 12,
    ): List<Slot> {
        val stateBySection = arrayOfNulls<Pair<SlotState, RoomOccupancy>>(totalSections)
        for (row in rows) {
            val state = when (row.usageType) {
                UsageType.CLASS -> SlotState.CLASS
                UsageType.BORROWED -> SlotState.BORROWED
                UsageType.UNKNOWN -> SlotState.CLASS   // 未知类型按占用展示，避免误报"空闲"
            }
            for ((idx, sec) in sectionNumbers(row.sectionCode).withIndex()) {
                if (sec in 1..totalSections && stateBySection[sec - 1] == null) {
                    stateBySection[sec - 1] = state to row
                }
            }
        }
        // 合并相邻同状态的节为连续段（占用行取该段首个，相邻两行是不同实例不能按引用判等）
        val slots = mutableListOf<Slot>()
        var i = 0
        while (i < totalSections) {
            val cur = stateBySection[i]
            var j = i
            while (j + 1 < totalSections && stateBySection[j + 1]?.first == cur?.first) j++
            slots += Slot(
                startSection = i + 1,
                endSection = j + 1,
                state = cur?.first ?: SlotState.FREE,
                occupancy = cur?.second,
            )
            i = j + 1
        }
        return slots
    }

    /**
     * 解析两位拼接节次（`"0607"` → [6,7]）。
     * 与协议文档 §4.7 一致：必须按两位一组切，`"01020304"` = 第 1,2,3,4 节。
     * 非法串返回空列表。
     */
    public fun sectionNumbers(code: String): List<Int> {
        if (code.length % 2 != 0) return emptyList()
        return code.chunked(2).mapNotNull { it.toIntOrNull() }
    }

    // ------------------------------------------------------------------ 展示辅助

    /** 节次段的中文标签：`6-7` / `10`。 */
    public fun sectionRangeLabel(start: Int, end: Int): String =
        if (start == end) "$start" else "$start-$end"

    /**
     * 把一次占用查询结果整理成"每间教室一行"的时间轴展示模型。
     *
     * - 教室按名称排序（数字结尾的按编号比较，`教3-9` < `教3-101`）；
     * - 每行 = 教室名 + 该教室的节次状态段。
     */
    public fun roomTimeline(result: com.gdutday.data.gdut.freeroom.RoomUsageResult): List<Pair<String, List<Slot>>> =
        result.rows
            .groupBy { it.room }
            .map { (room, rows) -> room to expandSlots(rows) }
            .sortedWith(compareBy({ it.first.length }, { it.first }))

    /** 状态 → 图标语义名（UI 层映射到具体 Icon）。 */
    public fun slotStateName(state: SlotState): String = when (state) {
        SlotState.FREE -> "free"
        SlotState.CLASS -> "class"
        SlotState.BORROWED -> "borrowed"
    }
}
