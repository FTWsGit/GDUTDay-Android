package com.gdutday.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.gdutday.core.model.Course
import com.gdutday.core.model.Term
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * V1→V2 迁移的真实 SQLite 验证（Robolectric，不需要模拟器）。
 *
 * 与 [MigrationContractTest]（纯 JVM，盯常量与映射）互补：这里手工按 `1.json`
 * 导出 schema 建一个 v1 旧库文件、灌入老数据，再通过 [GdutDatabase.build] 打开 ——
 * Room 升级时会执行 [GdutDatabase.MIGRATION_1_2] 并把迁移后的表结构与 v2 实体
 * 逐列比对，迁移 SQL 打错列名/漏索引时这里会直接抛异常。
 *
 * 不用 `MigrationTestHelper`：它在 Windows 上会因 Room 2.8 driver 用 `/` 提取
 * 文件基名而与 `\` 路径不匹配（androidx 已知兼容问题），手工建库 + 正常打开
 * 走的是同一套 Room 升级校验路径，验证强度等价。
 */
// Robolectric 4.16 最高支持 SDK 36，而项目 targetSdk=37，显式钉一个受支持的 SDK。
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {

    private lateinit var context: Context
    private lateinit var dbFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = context.getDatabasePath(GdutDatabase.FILE_NAME)
        dbFile.parentFile?.mkdirs()
        dbFile.delete()
    }

    @After
    fun tearDown() {
        // WAL 模式会留下 -wal/-shm 伴生文件，一起清掉。
        for (f in listOf(dbFile, File(dbFile.path + "-wal"), File(dbFile.path + "-shm"))) {
            f.delete()
        }
    }

    /**
     * 按 v1 导出 schema 建真实旧库（含全部表/索引），并灌入一条老课程。
     *
     * DDL 从 `schemas/…/1.json` 的 createSql 逐字抄录（提交在仓库里的 v1 导出件），
     * 手工建库而不是从 assets 读 JSON：Windows 上 Robolectric 的测试资产合并产物
     * 不稳定，而这段 DDL 被 git 与 v1 schema 的 review 共同锚定，漂移会在
     * `schema 2 的 course 表包含全部五个新列且默认值正确` 一类契约测试里暴露。
     */
    private fun createV1Database() {
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        db.use {
            it.execSQL(
                "CREATE TABLE IF NOT EXISTS `course` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`term_code` TEXT NOT NULL, `name` TEXT NOT NULL, `teacher` TEXT NOT NULL DEFAULT '', " +
                    "`classroom` TEXT NOT NULL DEFAULT '', `day_of_week` INTEGER NOT NULL, " +
                    "`start_section` INTEGER NOT NULL, `section_count` INTEGER NOT NULL, " +
                    "`weeks` TEXT NOT NULL DEFAULT '', `description` TEXT NOT NULL DEFAULT '', " +
                    "`teaching_class` TEXT NOT NULL DEFAULT '', `course_code` TEXT NOT NULL DEFAULT '', " +
                    "`source` TEXT NOT NULL DEFAULT 'SCHOOL', `color_key` TEXT, " +
                    "`class_dates` TEXT NOT NULL DEFAULT '', `updated_at` INTEGER NOT NULL DEFAULT 0)",
            )
            it.execSQL("CREATE INDEX IF NOT EXISTS `index_course_term_code` ON `course` (`term_code`)")
            it.execSQL("CREATE INDEX IF NOT EXISTS `index_course_term_code_day_of_week` ON `course` (`term_code`, `day_of_week`)")
            it.execSQL("CREATE INDEX IF NOT EXISTS `index_course_name` ON `course` (`name`)")
            it.execSQL("CREATE INDEX IF NOT EXISTS `index_course_source` ON `course` (`source`)")
            // 其余 v1 表也要建全：Room 打开升级库后会按 v2 schema 校验全部表，
            // 缺表会被当成 schema 不一致而不是迁移失败。
            it.execSQL(
                "CREATE TABLE IF NOT EXISTS `exam` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`term_code` TEXT NOT NULL, `course_name` TEXT NOT NULL, `course_code` TEXT NOT NULL DEFAULT '', " +
                    "`date` TEXT NOT NULL, `start_time` TEXT, `end_time` TEXT, `classroom` TEXT NOT NULL DEFAULT '', " +
                    "`campus` TEXT NOT NULL DEFAULT 'UNKNOWN', `category` TEXT NOT NULL DEFAULT '', " +
                    "`arrangement_type` TEXT NOT NULL DEFAULT '')",
            )
            it.execSQL("CREATE INDEX IF NOT EXISTS `index_exam_term_code` ON `exam` (`term_code`)")
            it.execSQL("CREATE INDEX IF NOT EXISTS `index_exam_date` ON `exam` (`date`)")
            it.execSQL(
                "CREATE TABLE IF NOT EXISTS `grade` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`term_name` TEXT NOT NULL, `term_code` TEXT, `course_name` TEXT NOT NULL, " +
                    "`course_category` TEXT NOT NULL DEFAULT '', `course_sub_category` TEXT NOT NULL DEFAULT '', " +
                    "`study_mode` TEXT NOT NULL DEFAULT '', `score_text` TEXT NOT NULL DEFAULT '', " +
                    "`score` REAL, `gpa` REAL, `credit` REAL)",
            )
            it.execSQL("CREATE INDEX IF NOT EXISTS `index_grade_term_name` ON `grade` (`term_name`)")
            it.execSQL("CREATE INDEX IF NOT EXISTS `index_grade_term_code` ON `grade` (`term_code`)")
            it.execSQL(
                "CREATE TABLE IF NOT EXISTS `term_meta` (`term_code` TEXT NOT NULL, `xnxqdm` TEXT NOT NULL DEFAULT '', " +
                    "`display_name` TEXT NOT NULL DEFAULT '', `is_current` INTEGER NOT NULL DEFAULT 0, " +
                    "`semester_start` TEXT NOT NULL, `start_source` TEXT NOT NULL DEFAULT 'GUESSED', " +
                    "`updated_at` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`term_code`))",
            )
            it.execSQL(
                "CREATE TABLE IF NOT EXISTS `course_color` (`course_name` TEXT NOT NULL, `color_key` TEXT NOT NULL, " +
                    "`is_user_chosen` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`course_name`))",
            )
            it.execSQL(
                "CREATE TABLE IF NOT EXISTS `sync_state` (`id` INTEGER NOT NULL, `last_sync_at` INTEGER, " +
                    "`last_term_code` TEXT, `last_success` INTEGER NOT NULL DEFAULT 1, " +
                    "`last_error` TEXT NOT NULL DEFAULT '', `last_schedule_source` TEXT NOT NULL DEFAULT '', " +
                    "`last_warnings` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`id`))",
            )
            it.execSQL("PRAGMA user_version = 1")
            it.execSQL(
                """
                INSERT INTO course (
                    term_code, name, teacher, classroom, day_of_week,
                    start_section, section_count, weeks, description,
                    teaching_class, course_code, source, color_key,
                    class_dates, updated_at
                ) VALUES (
                    '20251', '高等数学', '张三', '教5-301', 1,
                    1, 2, '3,4,5', '',
                    '', 'MA101', 'SCHOOL', NULL,
                    '', 1700000000000
                )
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `迁移1到2保留全部旧数据且新列取默认值`() = runTest {
        createV1Database()

        // release 安全配置（不允许破坏性回退）打开：迁移失败会抛异常而不是清库，
        // Room 升级完成后还会按 v2 schema 逐列校验。
        val db = GdutDatabase.build(context, destructiveMigrationFallback = false)
        try {
            val migrated = db.courseDao().getByTerm("20251")
            assertThat(migrated).hasSize(1)
            val c = migrated.single()
            // 旧数据逐字段保留
            assertThat(c.name).isEqualTo("高等数学")
            assertThat(c.teacher).isEqualTo("张三")
            assertThat(c.classroom).isEqualTo("教5-301")
            assertThat(c.dayOfWeek).isEqualTo(1)
            assertThat(c.startSection).isEqualTo(1)
            assertThat(c.sectionCount).isEqualTo(2)
            // Entity 的 weeks 是逗号分隔字符串（v1 数据原样保留）
            assertThat(c.weeks).isEqualTo("3,4,5")
            // 新列回填默认值：绝对时间未知(-1)，补丁列 NULL
            assertThat(c.startMinute).isEqualTo(-1)
            assertThat(c.endMinute).isEqualTo(-1)
            assertThat(c.overrideScope).isNull()
            assertThat(c.overrideTargetNaturalKey).isNull()
            assertThat(c.overrideWeeks).isNull()
        } finally {
            db.close()
        }
    }

    @Test
    fun `迁移后旧库能被当前版本的Room正常读写`() = runTest {
        createV1Database()

        val db = GdutDatabase.build(context, destructiveMigrationFallback = false)
        try {
            val course = Course(
                term = Term(2025, 1),
                name = "数据结构",
                dayOfWeek = 2,
                startSection = 3,
                sectionCount = 2,
            )
            db.courseDao().insertAll(listOf(with(Mappers) { course.toEntity() }))
            assertThat(db.courseDao().countByTerm("20251")).isEqualTo(2)
            assertThat(db.courseDao().getByTerm("20251").map { it.name })
                .isEqualTo(listOf("高等数学", "数据结构"))
        } finally {
            db.close()
        }
    }
}
