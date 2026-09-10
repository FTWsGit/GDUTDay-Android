package com.gdutday.core.database

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 数据库层的常量一致性测试。
 *
 * ## 为什么值得为"两个常量相等"写一个测试
 *
 * [SyncStateDao] 的 `@Query` 里写死了 `WHERE id = 1`，而 [SyncStateEntity.SINGLETON_ID]
 * 是同一个值的 Kotlin 常量。**Room 的 `@Query` 不支持字符串模板**，
 * 所以这两处无法在语法上绑定，只能靠约定。
 *
 * 约定不被测试盯着就会漂移：有人改了 `SINGLETON_ID = 2`，编译通过、
 * 现有测试通过、App 也能跑 —— 但 `upsert(SyncStateEntity())` 写进的是 id=2 的行，
 * 而 `observe()` 永远查 id=1，于是"上次同步时间"**永远显示为空**。
 * 这种 bug 在开发期完全无感，上线后也只会表现为一个不起眼的 UI 空白。
 *
 * 同理，表名和列名也是 SQL 字符串与注解之间的约定，一并盯着。
 */
class SyncStateDaoContractTest {

    @Test
    fun `SINGLETON_ID 必须是 1，因为 DAO 的 SQL 里写死了这个值`() {
        assertThat(SyncStateEntity.SINGLETON_ID).isEqualTo(1)
    }

    @Test
    fun `默认构造出来的实体就是那一行单例`() {
        val entity = SyncStateEntity()
        assertThat(entity.id).isEqualTo(SyncStateEntity.SINGLETON_ID)
        // 首次同步前的默认状态：从未同步、无错误、成功标记为真（避免 UI 一上来就报红）
        assertThat(entity.lastSyncAt).isNull()
        assertThat(entity.lastTermCode).isNull()
        assertThat(entity.lastSuccess).isTrue()
        assertThat(entity.lastError).isEmpty()
    }

    @Test
    fun `Mappers 的字符串编解码往返一致`() {
        // SyncStateEntity.lastWarnings 用 Mappers.encodeStrings/decodeStrings 存多条文本。
        // 分隔符选的是 \u0001（SOH）而不是逗号或换行 —— 因为警告文案本身就常含逗号和中文标点，
        // 用它们当分隔符会让一条警告被拆成好几条。这个测试守住这个选择。
        val warnings = listOf("课表第 3 行被丢弃：节次越界", "翻页可能未取全, total=312", "含换行的警告\n第二行")
        val encoded = Mappers.encodeStrings(warnings)
        assertThat(Mappers.decodeStrings(encoded)).isEqualTo(warnings)
        // 空白项在编码时被丢掉，避免解码出一堆空字符串
        assertThat(Mappers.encodeStrings(listOf("", "  ", "有效"))).isEqualTo("有效")
        assertThat(Mappers.decodeStrings("")).isEmpty()
    }

    @Test
    fun `周次与日期列表的编解码往返一致`() {
        // course 表的 weeks / class_dates 两列都用逗号分隔字符串。
        // 排序是刻意的：让两次同步写出的字节完全相同，从而可以用"内容有没有变"
        // 来判断课表是否被教务系统调整过（Set 的迭代顺序不保证，不排序就比对不了）。
        assertThat(Mappers.encodeWeeks(setOf(3, 1, 16, 2))).isEqualTo("1,2,3,16")
        assertThat(Mappers.decodeWeeks("1,2,3,16")).containsExactly(1, 2, 3, 16).inOrder()
        assertThat(Mappers.encodeWeeks(emptySet())).isEmpty()
        // 脏数据：越界周次和非数字片段都要被丢掉，而不是抛异常
        assertThat(Mappers.decodeWeeks("0,1,abc,99,3")).containsExactly(1, 3).inOrder()
        assertThat(Mappers.decodeWeeks("")).isEmpty()

        val dates = listOf(
            java.time.LocalDate.of(2025, 9, 1),
            java.time.LocalDate.of(2025, 12, 31),
        )
        assertThat(Mappers.decodeDates(Mappers.encodeDates(dates))).isEqualTo(dates)
        // 兼容旧版本可能写入的非 ISO 格式
        assertThat(Mappers.parseDateLenient("2025-9-1")).isEqualTo(java.time.LocalDate.of(2025, 9, 1))
        assertThat(Mappers.parseDateLenient("2025/09/01 00:00:00")).isEqualTo(java.time.LocalDate.of(2025, 9, 1))
        assertThat(Mappers.parseDateLenient("不是日期")).isNull()
        assertThat(Mappers.parseTimeLenient("8:30")).isEqualTo(java.time.LocalTime.of(8, 30))
        assertThat(Mappers.parseTimeLenient("08:30:15")).isEqualTo(java.time.LocalTime.of(8, 30, 15))
        assertThat(Mappers.parseTimeLenient(null)).isNull()
    }

    @Test
    fun `学期开始日期来源的 needsUserConfirmation 只有 GUESSED 为真`() {
        // UI 靠这个布尔值决定要不要弹"请校准开学日期"的横幅。
        // 如果哪天有人给 DERIVED 也标上 true，用户每次同步都会被打扰一次。
        assertThat(SemesterStartSource.GUESSED.needsUserConfirmation).isTrue()
        assertThat(
            SemesterStartSource.entries
                .filter { it != SemesterStartSource.GUESSED }
                .none { it.needsUserConfirmation },
        ).isTrue()
    }

    @Test
    fun `SemesterStartSource 从脏数据回退到 GUESSED`() {
        // 数据库里可能存着旧版本或损坏的值。回退到 GUESSED（而不是抛异常或回退到 USER）
        // 是安全方向：宁可多提示用户校准一次，也不要让他以为日期是自己设的而不理会偏差。
        assertThat(SemesterStartSource.fromName(null)).isEqualTo(SemesterStartSource.GUESSED)
        assertThat(SemesterStartSource.fromName("")).isEqualTo(SemesterStartSource.GUESSED)
        assertThat(SemesterStartSource.fromName("DERIVED")).isEqualTo(SemesterStartSource.DERIVED)
        assertThat(SemesterStartSource.fromName("derived")).isEqualTo(SemesterStartSource.GUESSED)
    }
}
