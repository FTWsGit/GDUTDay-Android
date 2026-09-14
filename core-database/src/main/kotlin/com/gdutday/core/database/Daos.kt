package com.gdutday.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

// ============================================================================
// DAO。
//
// ## 两条贯穿全局的约定
//
// **1. 读用 Flow，写用 suspend。**
// 课表页、Widget、成绩页都要在数据变化时自动刷新，Flow 是唯一合理的选择。
// Widget 进程也会观察同一份 Flow（通过 `GlanceStateDefinition`），
// 所以"同步完成 → 桌面插件自动更新"不需要任何额外的广播机制。
//
// **2. 同步时只删 source='SCHOOL' 的行。**
// 用户手动加的课（CUSTOM）必须在同步中存活。这是 [CourseDao.replaceSchoolCourses]
// 存在的理由 —— 它把"删旧 + 插新"包在一个事务里，避免出现"课表闪一下变空"。
// ============================================================================

@Dao
public interface CourseDao {

    /** 某学期的全部课程。课表页的主查询。 */
    @Query("SELECT * FROM course WHERE term_code = :termCode ORDER BY day_of_week, start_section, name")
    public fun observeByTerm(termCode: String): Flow<List<CourseEntity>>

    /** 某学期某天某周的课程。Widget 的"今日课程"用。 */
    @Query(
        """
        SELECT * FROM course
        WHERE term_code = :termCode AND day_of_week = :dayOfWeek
        ORDER BY start_section, name
        """,
    )
    public fun observeByTermAndDay(termCode: String, dayOfWeek: Int): Flow<List<CourseEntity>>

    /** 一次性读取（不订阅）。WorkManager 里用，避免为了读一次数据而起一个 Flow 收集协程。 */
    @Query("SELECT * FROM course WHERE term_code = :termCode ORDER BY day_of_week, start_section, name")
    public suspend fun getByTerm(termCode: String): List<CourseEntity>

    @Query("SELECT * FROM course WHERE id = :id")
    public suspend fun getById(id: Long): CourseEntity?

    /** 全部学期里出现过的课程名，用于配色分配。 */
    @Query("SELECT DISTINCT name FROM course ORDER BY name")
    public suspend fun distinctCourseNames(): List<String>

    @Query("SELECT COUNT(*) FROM course WHERE term_code = :termCode")
    public suspend fun countByTerm(termCode: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun insertAll(courses: List<CourseEntity>): List<Long>

    @Upsert
    public suspend fun upsert(course: CourseEntity): Long

    @Query("DELETE FROM course WHERE id = :id")
    public suspend fun deleteById(id: Long)

    /** 只删用户手动加的课（设置页的"清空自定义课程"）。 */
    @Query("DELETE FROM course WHERE source = 'CUSTOM'")
    public suspend fun deleteAllCustom(): Int

    @Query("DELETE FROM course WHERE term_code = :termCode")
    public suspend fun deleteByTerm(termCode: String)

    /**
     * 用一批新课程替换该学期**来自学校**的全部课程，保留用户手动添加的。
     *
     * 必须是一个事务：先删后插之间如果被打断（进程被杀），课表会永久变空。
     *
     * @return 实际插入的行数
     */
    @Transaction
    public suspend fun replaceSchoolCourses(termCode: String, courses: List<CourseEntity>): Int {
        deleteSchoolCourses(termCode)
        if (courses.isEmpty()) return 0
        insertAll(courses)
        return courses.size
    }

    @Query("DELETE FROM course WHERE term_code = :termCode AND source = 'SCHOOL'")
    public suspend fun deleteSchoolCourses(termCode: String)

    /** 某学期的全部用户补丁（OVERRIDE）。同步后逐条重新应用到教务课程上。 */
    @Query("SELECT * FROM course WHERE term_code = :termCode AND source = 'OVERRIDE' ORDER BY id")
    public suspend fun getOverrides(termCode: String): List<CourseEntity>

    /** 某学期的全部教务课程。补丁应用时按 `override_target_nk` 在其中找目标。 */
    @Query("SELECT * FROM course WHERE term_code = :termCode AND source = 'SCHOOL'")
    public suspend fun getSchoolCourses(termCode: String): List<CourseEntity>

    /** 设置页"我添加/修改的课程"列表：用户手动加的 + 对教务课程的补丁。 */
    @Query("SELECT * FROM course WHERE source IN ('CUSTOM', 'OVERRIDE') ORDER BY term_code, name")
    public fun observeCustomAndOverride(): Flow<List<CourseEntity>>

    /** 清空自定义课程时把补丁一并清掉，否则它们会在下次同步时重新写回。 */
    @Query("DELETE FROM course WHERE source = 'OVERRIDE'")
    public suspend fun deleteAllOverrides(): Int
}

@Dao
public interface ExamDao {

    @Query("SELECT * FROM exam WHERE term_code = :termCode ORDER BY date, start_time")
    public fun observeByTerm(termCode: String): Flow<List<ExamEntity>>

    /** 全部考试（跨学期），按日期升序。成绩页"考试安排"分组展示用。 */
    @Query("SELECT * FROM exam ORDER BY date, start_time")
    public fun observeAll(): Flow<List<ExamEntity>>

    /**
     * 从今天起最近的一场考试。Widget 和首页的"距期末还有 N 天"用。
     *
     * `:today` 由调用方传入而不是用 SQLite 的 `date('now')`：
     * 一来 `date('now')` 是 UTC，跨零点时会算错一天；
     * 二来测试里可以传固定日期。
     */
    @Query(
        """
        SELECT * FROM exam
        WHERE date >= :today
        ORDER BY date ASC, start_time ASC
        LIMIT 1
        """,
    )
    public fun observeNextExam(today: String): Flow<ExamEntity?>

    @Query("SELECT * FROM exam WHERE term_code = :termCode ORDER BY date, start_time")
    public suspend fun getByTerm(termCode: String): List<ExamEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun insertAll(exams: List<ExamEntity>)

    /** 考试安排每次同步整体替换（没有"用户手动加的考试"这种东西）。 */
    @Transaction
    public suspend fun replaceByTerm(termCode: String, exams: List<ExamEntity>) {
        deleteByTerm(termCode)
        if (exams.isNotEmpty()) insertAll(exams)
    }

    @Query("DELETE FROM exam WHERE term_code = :termCode")
    public suspend fun deleteByTerm(termCode: String)
}

@Dao
public interface GradeDao {

    @Query("SELECT * FROM grade ORDER BY term_name DESC, course_name ASC")
    public fun observeAll(): Flow<List<GradeEntity>>

    @Query("SELECT * FROM grade WHERE term_name = :termName ORDER BY course_name ASC")
    public fun observeByTermName(termName: String): Flow<List<GradeEntity>>

    @Query("SELECT DISTINCT term_name FROM grade ORDER BY term_name DESC")
    public fun observeTermNames(): Flow<List<String>>

    @Query("SELECT * FROM grade ORDER BY term_name DESC, course_name ASC")
    public suspend fun getAll(): List<GradeEntity>

    /**
     * 成绩**整体替换**，不按学期。
     *
     * 因为默认查询是 `xnxqdm=""`（全部学期），一次就拿到全量；
     * 按学期增量替换反而会在"某学期成绩被教务处撤掉"时留下幽灵数据。
     */
    @Transaction
    public suspend fun replaceAll(grades: List<GradeEntity>) {
        deleteAll()
        if (grades.isNotEmpty()) insertAll(grades)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun insertAll(grades: List<GradeEntity>)

    @Query("DELETE FROM grade")
    public suspend fun deleteAll()
}

@Dao
public interface TermMetaDao {

    /** 全部学期，最新在前。学期切换下拉框用。 */
    @Query("SELECT * FROM term_meta ORDER BY term_code DESC")
    public fun observeAll(): Flow<List<TermMetaEntity>>

    @Query("SELECT * FROM term_meta WHERE is_current = 1 LIMIT 1")
    public fun observeCurrent(): Flow<TermMetaEntity?>

    @Query("SELECT * FROM term_meta WHERE is_current = 1 LIMIT 1")
    public suspend fun getCurrent(): TermMetaEntity?

    @Query("SELECT * FROM term_meta WHERE term_code = :termCode")
    public suspend fun getByCode(termCode: String): TermMetaEntity?

    @Query("SELECT * FROM term_meta WHERE term_code = :termCode")
    public fun observeByCode(termCode: String): Flow<TermMetaEntity?>

    @Upsert
    public suspend fun upsert(term: TermMetaEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsertAll(terms: List<TermMetaEntity>)

    /**
     * 把"当前学期"标记切换到指定学期。
     *
     * 两条语句必须在一个事务里，否则会出现"没有当前学期"或"两个当前学期"的中间态。
     */
    @Transaction
    public suspend fun setCurrentTerm(termCode: String) {
        clearCurrent()
        markCurrent(termCode)
    }

    @Query("UPDATE term_meta SET is_current = 0 WHERE is_current = 1")
    public suspend fun clearCurrent()

    @Query("UPDATE term_meta SET is_current = 1 WHERE term_code = :termCode")
    public suspend fun markCurrent(termCode: String)

    /** 只更新开学日期与来源（用户在设置里校准时用，不该动其它字段）。 */
    @Query(
        """
        UPDATE term_meta
        SET semester_start = :semesterStart, start_source = :source, updated_at = :updatedAt
        WHERE term_code = :termCode
        """,
    )
    public suspend fun updateSemesterStart(
        termCode: String,
        semesterStart: String,
        source: String,
        updatedAt: Long,
    ): Int
}

@Dao
public interface CourseColorDao {

    @Query("SELECT * FROM course_color")
    public suspend fun getAll(): List<CourseColorEntity>

    @Query("SELECT color_key FROM course_color WHERE course_name = :courseName")
    public suspend fun colorKeyOf(courseName: String): String?

    @Upsert
    public suspend fun upsert(entry: CourseColorEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    public suspend fun insertIfAbsent(entries: List<CourseColorEntity>)

    @Query("DELETE FROM course_color WHERE course_name = :courseName")
    public suspend fun delete(courseName: String)

    /** 清掉所有**非用户手选**的配色，让自动配色重新来一遍（设置页的"重置配色"）。 */
    @Query("DELETE FROM course_color WHERE is_user_chosen = 0")
    public suspend fun deleteAutoAssigned(): Int
}

/**
 * 同步状态。表里只有一行（`id = 1`），所以查询都写死了这个常量。
 *
 * Room 的 `@Query` 不支持 Kotlin 字符串模板，因此这里没法引用
 * [SyncStateEntity.SINGLETON_ID]，只能字面量写 `1`。
 * 两者的不一致由 `SyncStateDaoContractTest` 盯着（它断言 `SINGLETON_ID == 1`）。
 */
@Dao
public interface SyncStateDao {

    @Query("SELECT * FROM sync_state WHERE id = 1 LIMIT 1")
    public fun observe(): Flow<SyncStateEntity?>

    @Query("SELECT * FROM sync_state WHERE id = 1 LIMIT 1")
    public suspend fun get(): SyncStateEntity?

    @Upsert
    public suspend fun upsert(state: SyncStateEntity)

    @Query("DELETE FROM sync_state")
    public suspend fun clear()
}
