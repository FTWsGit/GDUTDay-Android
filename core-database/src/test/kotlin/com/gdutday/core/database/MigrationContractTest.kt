package com.gdutday.core.database

import com.google.common.truth.Truth.assertThat
import com.gdutday.core.model.Course
import com.gdutday.core.model.CourseSource
import com.gdutday.core.model.OverrideScope
import com.gdutday.core.model.Term
import org.junit.Test

/**
 * 数据库 v2（补丁与绝对时间列）的纯 JVM 契约测试。
 *
 * ## 为什么不是真正的 Room MigrationTest
 *
 * 真正的 1→2 迁移测试（MigrationTestHelper 挂 Android 环境）在 [MigrationTest] 里，
 * 用 Robolectric 跑真实 SQLite。这里在纯 JVM 层盯着迁移中最容易错的两面：
 * 1. **迁移 SQL 与 schema JSON 的一致性** —— 列名、默认值与 `2.json` 导出值对齐，
 *    手写 ALTER TABLE 打错列名/类型时 Room 的校验会在运行时才炸；
 * 2. **新列的映射往返** —— 实体 → 领域模型 → 实体，绝对时间与补丁字段不丢。
 */
class MigrationContractTest {

    private val term = Term(2025, 1)

    @Test
    fun `数据库版本已升到 2`() {
        assertThat(GdutDatabase.VERSION).isEqualTo(2)
    }

    @Test
    fun `MIGRATION_1_2 的目标版本`() {
        assertThat(GdutDatabase.MIGRATION_1_2.startVersion).isEqualTo(1)
        assertThat(GdutDatabase.MIGRATION_1_2.endVersion).isEqualTo(2)
        assertThat(GdutDatabase.ALL_MIGRATIONS).hasLength(1)
    }

    @Test
    fun `schema 2 的 course 表包含全部五个新列且默认值正确`() {
        val schema = javaClass.classLoader
            ?.getResourceAsStream(
                "com/gdutday/core/database/GdutDatabase/2.json",
            )
            ?: // 单测 classpath 不含 schemas 资源时跳过结构断言（真正的守护在编译期的 KSP 导出）
            return
        val json = schema.bufferedReader().readText()
        val courseEntity = Regex("\"tableName\"\\s*:\\s*\"course\"").find(json) ?: error("schema 2 缺少 course 表")
        val blockStart = courseEntity.range.first
        val blockEnd = json.indexOf("\"tableName\"", blockStart + 1).let { if (it == -1) json.length else it }
        val courseBlock = json.substring(blockStart, blockEnd)

        for (column in listOf("start_minute", "end_minute", "override_scope", "override_target_nk", "override_weeks")) {
            assertThat(courseBlock).contains("\"columnName\": \"$column\"")
        }
        // NOT NULL + DEFAULT -1 的绝对时间列；其余三列允许 NULL（迁移不回填）
        assertThat(courseBlock).contains("\"defaultValue\": \"-1\"")
        assertThat(courseBlock).contains("\"notNull\": true")
    }

    @Test
    fun `实体与领域模型之间五个新字段往返一致`() {
        val course = Course(
            id = 7L,
            term = term,
            name = "高等数学",
            teacher = "张三",
            classroom = "教5-301",
            dayOfWeek = 1,
            startSection = 1,
            sectionCount = 2,
            weeks = setOf(3),
            source = CourseSource.OVERRIDE,
            startMinute = 8 * 60 + 30,
            endMinute = 9 * 60 + 15,
            overrideScope = OverrideScope.WEEK_RANGE,
            overrideTargetNaturalKey = "高等数学|||1|1|2",
            overrideWeeks = setOf(3, 4, 5),
        )
        val roundTrip = with(Mappers) { course.toEntity().toDomain() }!!
        assertThat(roundTrip.startMinute).isEqualTo(510)
        assertThat(roundTrip.endMinute).isEqualTo(555)
        assertThat(roundTrip.overrideScope).isEqualTo(OverrideScope.WEEK_RANGE)
        assertThat(roundTrip.overrideTargetNaturalKey).isEqualTo("高等数学|||1|1|2")
        assertThat(roundTrip.overrideWeeks).containsExactly(3, 4, 5)
    }

    @Test
    fun `普通教务课程的新字段全部走默认值`() {
        val course = Course(term = term, name = "体育", dayOfWeek = 3, startSection = 7, sectionCount = 1)
        val entity = with(Mappers) { course.toEntity() }
        assertThat(entity.startMinute).isEqualTo(-1)
        assertThat(entity.endMinute).isEqualTo(-1)
        assertThat(entity.overrideScope).isNull()
        assertThat(entity.overrideTargetNaturalKey).isNull()
        assertThat(entity.overrideWeeks).isNull()
        val domain = with(Mappers) { entity.toDomain() }!!
        assertThat(domain.startMinute).isEqualTo(-1)
        assertThat(domain.endMinute).isEqualTo(-1)
        assertThat(domain.overrideScope).isNull()
        assertThat(domain.overrideWeeks).isEmpty()
    }

    @Test
    fun `脏数据里的未知作用范围降级为 null而不是抛异常`() {
        val entity = with(Mappers) {
            Course(
                term = term, name = "X", dayOfWeek = 1, startSection = 1, sectionCount = 1,
                source = CourseSource.OVERRIDE,
            ).toEntity()
        }.copy(overrideScope = "NOT_A_SCOPE")
        val domain = with(Mappers) { entity.toDomain() }!!
        assertThat(domain.overrideScope).isNull()
        assertThat(domain.source).isEqualTo(CourseSource.OVERRIDE)
    }
}
