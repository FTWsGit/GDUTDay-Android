package com.gdutday.feature.toolbox

import com.gdutday.data.gdut.freeroom.FreeRoomBuilding
import com.gdutday.data.gdut.freeroom.RoomOccupancy
import com.gdutday.data.gdut.freeroom.UsageType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FreeRoomLogicTest {

    private fun building(name: String, code: String = "0001") =
        FreeRoomBuilding(code = code, name = name, campusCode = "1", campusName = "大学城校区")

    private fun occupancy(
        sectionCode: String,
        usageType: UsageType = UsageType.CLASS,
        room: String = "教3-101",
    ) = RoomOccupancy(
        room = room,
        courseName = "课程",
        teacher = "老师",
        teachingClass = "班级",
        sectionCode = sectionCode,
        dayOfWeek = 4,
        week = 3,
        termCode = "202601",
        attendees = 100,
        capacity = 120,
        usageType = usageType,
    )

    // ------------------------------------------------------------------ 楼型分类

    @Test
    fun `楼名按关键词归类`() {
        assertThat(FreeRoomLogic.buildingKind("教学三号楼(城)")).isEqualTo(FreeRoomLogic.BuildingKind.TEACHING)
        assertThat(FreeRoomLogic.buildingKind("综合楼(城)")).isEqualTo(FreeRoomLogic.BuildingKind.TEACHING)
        assertThat(FreeRoomLogic.buildingKind("独立大课室(城)")).isEqualTo(FreeRoomLogic.BuildingKind.TEACHING)
        assertThat(FreeRoomLogic.buildingKind("实验二号楼(城)")).isEqualTo(FreeRoomLogic.BuildingKind.LAB)
        assertThat(FreeRoomLogic.buildingKind("实验室(城)")).isEqualTo(FreeRoomLogic.BuildingKind.LAB)
        assertThat(FreeRoomLogic.buildingKind("工学三号馆")).isEqualTo(FreeRoomLogic.BuildingKind.COLLEGE)
        assertThat(FreeRoomLogic.buildingKind("理学馆")).isEqualTo(FreeRoomLogic.BuildingKind.COLLEGE)
        assertThat(FreeRoomLogic.buildingKind("文法楼")).isEqualTo(FreeRoomLogic.BuildingKind.COLLEGE)
        assertThat(FreeRoomLogic.buildingKind("设计工场(东)")).isEqualTo(FreeRoomLogic.BuildingKind.COLLEGE)
        assertThat(FreeRoomLogic.buildingKind("图书馆")).isEqualTo(FreeRoomLogic.BuildingKind.PUBLIC)
        assertThat(FreeRoomLogic.buildingKind("学生活动中心")).isEqualTo(FreeRoomLogic.BuildingKind.PUBLIC)
        assertThat(FreeRoomLogic.buildingKind("运动场")).isEqualTo(FreeRoomLogic.BuildingKind.PUBLIC)
        assertThat(FreeRoomLogic.buildingKind("行政楼")).isEqualTo(FreeRoomLogic.BuildingKind.PUBLIC)
    }

    @Test
    fun `学院楼关键词优先于实验`() {
        // "创新中心(揭)" 含"创新"归学院楼；"创新科技楼A座" 同理
        assertThat(FreeRoomLogic.buildingKind("创新中心(揭)")).isEqualTo(FreeRoomLogic.BuildingKind.COLLEGE)
        assertThat(FreeRoomLogic.buildingKind("创新科技楼A座")).isEqualTo(FreeRoomLogic.BuildingKind.COLLEGE)
    }

    @Test
    fun `无法归类的楼进 OTHER 不混入教学楼`() {
        assertThat(FreeRoomLogic.buildingKind("龙洞舞蹈房")).isEqualTo(FreeRoomLogic.BuildingKind.PUBLIC)
        assertThat(FreeRoomLogic.buildingKind("神秘小屋")).isEqualTo(FreeRoomLogic.BuildingKind.OTHER)
    }

    @Test
    fun `分组按固定顺序且组内按名称排序`() {
        val buildings = listOf(
            building("实验B楼", "1"),
            building("教学二号楼(城)", "2"),
            building("图书馆", "3"),
            building("教学楼", "4"),
            building("神秘小屋", "5"),
        )
        val groups = FreeRoomLogic.groupBuildings(buildings)

        assertThat(groups.map { it.first })
            .containsExactly(
                FreeRoomLogic.BuildingKind.TEACHING,
                FreeRoomLogic.BuildingKind.LAB,
                FreeRoomLogic.BuildingKind.PUBLIC,
                FreeRoomLogic.BuildingKind.OTHER,
            ).inOrder()
        assertThat(groups.first().second.map { it.name })
            .containsExactly("教学二号楼(城)", "教学楼").inOrder()
        // OTHER 有自己的组，不空
        assertThat(groups.last().second.map { it.name }).containsExactly("神秘小屋")
    }

    // ------------------------------------------------------------------ 节次解析

    @Test
    fun `两位拼接节次按两位一组解析`() {
        assertThat(FreeRoomLogic.sectionNumbers("0607")).containsExactly(6, 7).inOrder()
        assertThat(FreeRoomLogic.sectionNumbers("01020304")).containsExactly(1, 2, 3, 4).inOrder()
        assertThat(FreeRoomLogic.sectionNumbers("12")).containsExactly(12)
    }

    @Test
    fun `非法节次串返回空列表`() {
        assertThat(FreeRoomLogic.sectionNumbers("")).isEmpty()
        assertThat(FreeRoomLogic.sectionNumbers("123")).isEmpty()
        assertThat(FreeRoomLogic.sectionNumbers("0a")).isEmpty()
    }

    // ------------------------------------------------------------------ 固定 6 列网格

    @Test
    fun `固定6列模型各列状态正确`() {
        val slots = FreeRoomLogic.expandSlots(
            rows = listOf(occupancy("0102"), occupancy("0607", UsageType.BORROWED)),
        )

        // 6 列固定：1-2 上课、3-4/5 空闲、6-7 借用、8-9/10-12 空闲
        assertThat(slots).hasSize(6)
        assertThat(slots[0].period).isEqualTo(FreeRoomLogic.Period.P12)
        assertThat(slots[0].state).isEqualTo(FreeRoomLogic.SlotState.CLASS)
        assertThat(slots[1].state).isEqualTo(FreeRoomLogic.SlotState.FREE)
        assertThat(slots[2].state).isEqualTo(FreeRoomLogic.SlotState.FREE)
        assertThat(slots[3].state).isEqualTo(FreeRoomLogic.SlotState.BORROWED)
        assertThat(slots[3].occupancy).isNotNull()
        assertThat(slots[4].state).isEqualTo(FreeRoomLogic.SlotState.FREE)
        assertThat(slots[5].state).isEqualTo(FreeRoomLogic.SlotState.FREE)
    }

    @Test
    fun `无占用时6列全部空闲`() {
        val slots = FreeRoomLogic.expandSlots(emptyList())

        assertThat(slots).hasSize(6)
        assertThat(slots.all { it.state == FreeRoomLogic.SlotState.FREE }).isTrue()
    }

    @Test
    fun `跨列占用行只命中实际节次所在列`() {
        // "0307" 按两位一组 = 第 3、7 节（实测 jcdm2=03,07 佐证，非 3-7 连续段）
        // → 只命中 3-4 与 6-7 两列，5 节不占
        val slots = FreeRoomLogic.expandSlots(rows = listOf(occupancy("0307")))

        assertThat(slots[0].state).isEqualTo(FreeRoomLogic.SlotState.FREE)
        assertThat(slots[1].state).isEqualTo(FreeRoomLogic.SlotState.CLASS)
        assertThat(slots[2].state).isEqualTo(FreeRoomLogic.SlotState.FREE)
        assertThat(slots[3].state).isEqualTo(FreeRoomLogic.SlotState.CLASS)
        assertThat(slots[4].state).isEqualTo(FreeRoomLogic.SlotState.FREE)
        assertThat(slots[5].state).isEqualTo(FreeRoomLogic.SlotState.FREE)
    }

    @Test
    fun `超出12节的占用行不越界`() {
        val slots = FreeRoomLogic.expandSlots(rows = listOf(occupancy("11121314")))

        // 10-12 列命中（11、12 节在区间内），其余列空闲
        assertThat(slots[5].state).isEqualTo(FreeRoomLogic.SlotState.CLASS)
        assertThat(slots.take(5).all { it.state == FreeRoomLogic.SlotState.FREE }).isTrue()
    }

    @Test
    fun `未知占用类型按占用展示不误报空闲`() {
        val slots = FreeRoomLogic.expandSlots(rows = listOf(occupancy("0304", UsageType.UNKNOWN)))

        assertThat(slots[0].state).isEqualTo(FreeRoomLogic.SlotState.FREE)
        assertThat(slots[1].state).isNotEqualTo(FreeRoomLogic.SlotState.FREE)
        assertThat(slots[2].state).isEqualTo(FreeRoomLogic.SlotState.FREE)
    }

    // ------------------------------------------------------------------ 展示辅助

    @Test
    fun `节次段标签单节与范围`() {
        assertThat(FreeRoomLogic.sectionRangeLabel(6, 7)).isEqualTo("6-7")
        assertThat(FreeRoomLogic.sectionRangeLabel(10, 10)).isEqualTo("10")
    }
}
