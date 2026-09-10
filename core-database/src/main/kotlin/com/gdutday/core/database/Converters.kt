package com.gdutday.core.database

import androidx.room.TypeConverter
import java.time.Instant

/**
 * Room 类型转换器。
 *
 * 只放**Room 必须**的转换（它不认识 `Instant`）。
 * 领域模型 ↔ 实体的映射不在这里，在 [Mappers] —— 那些转换带有业务判断
 * （周次集合的排序、日期列表与周次列表的长度校验），
 * 塞进 TypeConverter 会让 Room 在看不见的地方执行业务逻辑，出问题很难查。
 */
public class Converters {

    /**
     * [Instant] ↔ epoch 毫秒。
     *
     * null 存成 0（Room 的 `@ColumnInfo(defaultValue)` 与可空列混用时，
     * 用哨兵值比让列可空更省心，也方便 SQL 里直接比较）。
     */
    @TypeConverter
    public fun instantToMillis(value: Instant?): Long = value?.toEpochMilli() ?: 0L

    @TypeConverter
    public fun millisToInstant(value: Long): Instant? =
        if (value <= 0L) null else Instant.ofEpochMilli(value)

    @TypeConverter
    public fun booleanToInt(value: Boolean): Int = if (value) 1 else 0

    @TypeConverter
    public fun intToBoolean(value: Int): Boolean = value != 0
}
