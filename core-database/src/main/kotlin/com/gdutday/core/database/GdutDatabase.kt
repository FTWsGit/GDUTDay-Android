package com.gdutday.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * 应用数据库。
 *
 * ## 版本与迁移策略
 *
 * [VERSION] = 1，**尚未发布**，所以 debug 构建直接用 `fallbackToDestructiveMigration`
 * （改 schema 就重建，开发期最省事）。
 *
 * ⚠ **正式发布前必须做的事**（见 `docs/02-data-model.md` 的迁移章节）：
 * 1. 把 debug 的 destructive 回退去掉，改成 release 只允许显式 [Migration]
 * 2. 每次改 schema 都 `VERSION++` 并写一个 Migration
 * 3. `schemas/` 目录下的 JSON **必须提交进仓库** —— 它是写 MigrationTest 的唯一依据，
 *    也是将来 review 迁移正确性的凭据
 *
 * 为什么现在就导出 schema：Room 只在配置了 `room.schemaLocation` 时才导出
 * （见 build.gradle.kts 的 ksp 块）。等发布后再补，中间几个版本的 schema 就永远丢了。
 *
 * ## 为什么不用 `exportSchema = false` 图省事
 *
 * 那会让"数据库结构随版本怎么变的"这件事完全没有记录。
 * 一个存着用户整学期课表和成绩的库，迁移出错等于数据全丢，代价太高。
 */
@Database(
    entities = [
        CourseEntity::class,
        ExamEntity::class,
        GradeEntity::class,
        TermMetaEntity::class,
        CourseColorEntity::class,
        SyncStateEntity::class,
    ],
    version = GdutDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
public abstract class GdutDatabase : RoomDatabase() {

    public abstract fun courseDao(): CourseDao
    public abstract fun examDao(): ExamDao
    public abstract fun gradeDao(): GradeDao
    public abstract fun termMetaDao(): TermMetaDao
    public abstract fun courseColorDao(): CourseColorDao
    public abstract fun syncStateDao(): SyncStateDao

    public companion object {
        public const val VERSION: Int = 1

        /** 数据库文件名。改名等于让所有老用户的数据消失，**不要轻易动**。 */
        public const val FILE_NAME: String = "gdutday.db"

        /**
         * 构建数据库实例。
         *
         * @param destructiveMigrationFallback true 时允许"迁移不了就重建"。
         *   **只应在 debug 构建传 true**（由 `AppContainer` 按 BuildConfig 决定）。
         *   release 传 true 会让一次 schema 变更静默清空用户全部数据。
         */
        public fun build(
            context: Context,
            destructiveMigrationFallback: Boolean,
        ): GdutDatabase {
            val builder = Room.databaseBuilder(
                context.applicationContext,
                GdutDatabase::class.java,
                FILE_NAME,
            )
                // 课表查询很频繁且都是小结果集，Room 的查询缓存收益有限，
                // 但默认开启没有额外成本，保留。
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)

            if (destructiveMigrationFallback) {
                @Suppress("DEPRECATION")
                builder.fallbackToDestructiveMigration(dropAllTables = true)
            }

            return builder.build()
        }

        /**
         * 内存数据库，仅供测试。
         *
         * 用真实的 Room + SQLite（跑在 Robolectric 或 androidTest 上）而不是 mock DAO：
         * `@Query` 里的 SQL 写错了，mock 是发现不了的。
         */
        public fun inMemory(context: Context): GdutDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, GdutDatabase::class.java)
                .allowMainThreadQueries()   // 仅测试
                .build()
    }
}
