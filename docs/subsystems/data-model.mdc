---
name: "data-model"
kind: "subsystem"
description: "Core model, Room database entities/DAOs, DataStore, semester start resolution"
alwaysApply: false
---


# 数据模型

覆盖 `core-model`（领域模型）、`core-database`（Room 实体/DAO/映射）、
`core-datastore`（偏好与会话）、以及 `data-repository` 里的开学日期解析与配色持久化。

相关文档：[架构](../architecture/architecture.md) · [协议](../reference/spec/gdut-protocol.md) · [UI 规格](./ui.md)

---

## 1. Room 实体

命名约定：表名用**单数蛇形**（`course` 而不是 `courses`），列名同样蛇形，
与领域模型字段一一对应。Room 不直接支持 `LocalDate` / `Set<Int>` / 枚举 / data class，
所以全部存成基础类型（String / Int / Long），转换集中在 `Mappers.kt`。

### 1.1 `course`（课程表）

一行 = 一个 `Course` = 某学期 · 某教学班 · 某星期 · 某段连续节次 · 一组周次。

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | Long PK autoGenerate | 0 表示尚未入库 |
| `term_code` | String | 学期**短码**，如 `"20251"` |
| `name` | String | 课程名称 |
| `teacher` | String | 教师，多值用顿号连接 |
| `classroom` | String | 教室，多值用顿号连接 |
| `day_of_week` | Int | 1=周一 … 7=周日（与 `java.time.DayOfWeek.value` 一致） |
| `start_section` | Int | 起始节次 |
| `section_count` | Int | 连续节数（已按连续段切分） |
| `weeks` | String | 逗号分隔升序，如 `"1,2,3"` |
| `description` | String | `sknrjj` / 备注 |
| `teaching_class` | String | 教学班名称 |
| `course_code` | String | 课程编号 |
| `source` | String | `CourseSource` 的名字 |
| `color_key` | String? | 颜色 key，null 表示未分配 |
| `class_dates` | String | 逗号分隔 ISO 日期，与 `weeks` **等长且同序** |
| `updated_at` | Long | 最后写入时刻 |

索引：

| 索引 | 理由 |
|---|---|
| `(term_code)` | 切换学期时整批换数据，最主要查询 |
| `(term_code, day_of_week)` | 日视图 / Widget 的"今天有什么课" |
| `(name)` | 按课程名聚合配色 |
| `(source)` | 同步时 `DELETE ... WHERE source='SCHOOL'` |

**为什么 `weeks` 存成逗号分隔字符串而不是单独开一张周次关联表**：
周次永远跟着课程整体读写，从不单独查询。拆开只会让每次同步多几十次 INSERT。

**同步策略：删了重插，但只删 `source=SCHOOL` 的。**
用户手动加的课（`CourseSource.CUSTOM`）必须在同步中存活。
`CourseDao.replaceSchoolCourses` 把"删旧 + 插新"包在一个 `@Transaction` 里，
避免"课表闪一下变空"，也避免进程被杀后课表永久变空。

### 1.2 `exam`（考试）

独立成表而不塞进 `course`：考试有日期、时段、校区、类别等课程没有的属性，
且渲染时才需要把它投影成课表上的色块（`ScheduleGridBuilder`）。

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | Long PK | |
| `term_code` | String | 短码 |
| `course_name` / `course_code` | String | |
| `date` | String | ISO 日期，建索引（"最近的考试"按日期排序取第一条） |
| `start_time` / `end_time` | String? | `HH:mm`，可空 |
| `classroom` | String | |
| `campus` | String | `Campus` 名字 |
| `category` / `arrangement_type` | String | |

索引：`(term_code)`、`(date)`。

考试安排每次同步**整体替换**（没有"用户手动加的考试"这种东西），
`ExamDao.replaceByTerm` 同样是事务。

### 1.3 `grade`（成绩）

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | Long PK | |
| `term_name` | String | 教务系统给的中文名，**分组键** |
| `term_code` | String? | 短码，可能缺失 |
| `course_name` | String | |
| `course_category` / `course_sub_category` / `study_mode` | String | |
| `score_text` | String | 原始文本（`"87"` / `"优秀"` / `""`） |
| `score` / `gpa` / `credit` | Double? | |

索引：`(term_name)`、`(term_code)`。

**没有唯一索引**：同一门课可能重修多次，产生多行同名记录，都是合法的。

成绩**整体替换、不按学期**：默认查询是 `xnxqdm=""`（全部学期），一次拿到全量；
按学期增量替换反而会在"某学期成绩被教务处撤掉"时留下幽灵数据。

### 1.4 `term_meta`（学期元信息）

一行一个学期，主键是学期短码。

| 列 | 类型 | 说明 |
|---|---|---|
| `term_code` | String PK | 短码 |
| `xnxqdm` | String | 长码（冗余存储，免换算） |
| `display_name` | String | 教务系统中文名 |
| `is_current` | Boolean | 教务系统标记的当前学期 |
| `semester_start` | String | 第 1 周周一，ISO 日期 |
| `start_source` | String | `SemesterStartSource`：日期来自哪一级优先级 |
| `updated_at` | Long | |

**为什么单独一张表**：学期开始日期**不在教务系统的课表接口里**，但周次换算离不开它。
它的来源有四级（见第 4 节），反推和猜测的结果需要落盘，
"用户手填"也必须有个地方存。存 `start_source` 是为了在 UI 上告诉用户
"这个日期是自动推算的，可能不准，建议校准"。

`TermMetaDao.setCurrentTerm` 用事务保证"没有当前学期 / 两个当前学期"的中间态不出现。

### 1.5 `course_color`（课程名 → 颜色 key）

| 列 | 类型 | 说明 |
|---|---|---|
| `course_name` | String PK | |
| `color_key` | String | |
| `is_user_chosen` | Boolean | true 表示用户手动指定，自动配色不得改动 |

见第 5 节。

### 1.6 `sync_state`（同步状态，单行）

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | Int PK = 1 | |
| `last_sync_at` | Instant? | |
| `last_term_code` | String? | |
| `last_success` | Boolean | 失败时 UI 显示"数据可能过期" |
| `last_error` | String | 用户可读原因 |
| `last_schedule_source` | String | 实际命中的课表接口 |
| `last_warnings` | String | `\u0001` 分隔的多值文本 |

**只有一行（`id = 1`）。** Room 的 `@Query` 不支持 Kotlin 字符串模板，
所以 SQL 里只能字面量写 `1` 而无法引用 `SyncStateEntity.SINGLETON_ID`。

- 成绩同步和课表同步**共用同一张表**：两者都只有一个"上次刷新时间"，分开没有可见收益。
  成绩同步只更新时间戳与成功标志，**不碰** `last_term_code` / `last_schedule_source`。

---

## 2. 领域模型 ↔ 实体映射

**手写，不靠 Room 自动映射。** 原因：领域模型里有三类东西自动映射处理不了：

1. **强类型值对象**：`Term` 有短码/长码两种表示，`Campus` / `CourseSource` 是枚举；
2. **派生属性**：`Course.endSection`、`Course.weeksDisplay`、`Grade.countsTowardsGpa`；
3. **集合**：`weeks: Set<Int>`、`classDates: List<LocalDate>`。

自动映射会在这些地方悄悄出错（比如把 `endSection` 当成可写字段、
把 `Set` 序列化成 `toString()` 的 `[1, 2, 3]` 再解析失败）。
手写多花几十行，换来的是**每条转换都有单元测试盯着**。

### 一条贯穿全局的原则：读库时永不抛异常

数据库里可能有旧版本写入的脏数据（升级、降级、中途崩溃）。所有 `fromEntity` 方法遇到
解析不了的字段都**降级为默认值**，最多丢掉那一门课，绝不让整个课表页崩掉：

- `CourseEntity.toDomain()` 在学期码非法、星期/节次越界时返回 null（该行被跳过）；
- `ExamEntity.toDomain()` 在学期码或日期非法时返回 null；
- `GradeEntity.toDomain()` 从不失败（`term` 解析失败为 null，其余字段原样）。

### `classDates` 的一致性是硬约束

`Course.classDates` 与 `weeks` 必须**等长同序**，`TermCalendar.samplesFrom` 才能用它反推开学日期。
映射层和归一化层都做了校验：

- `CourseNormalizer`：`sortedDates.size == sortedWeeks.size` 才采用，否则整体丢弃并记 warning；
- `Mappers.CourseEntity.toDomain()`：`decodeDates(classDates).size == decodeWeeks(weeks).size` 才采用。

**不硬凑**：宁可丢日期，也不给出一组错位的日期去污染开学日期的众数。

### 编码格式

| 领域类型 | 存储格式 | 编 / 解码 |
|---|---|---|
| `Term` | 短码字符串 `"20251"` | `Term.parse` / `shortCode` |
| `Set<Int>`（周次） | 逗号分隔升序 `"1,2,3"` | `Mappers.encodeWeeks` / `decodeWeeks`（越界跳过） |
| `List<LocalDate>`（上课日期） | 逗号分隔 ISO `"2025-09-01,…"` | `Mappers.encodeDates` / `decodeDates`（宽松解析） |
| `Campus` / `CourseSource` / `SemesterStartSource` | 枚举名 | 各自的 `fromName` / `decodeXxx` |
| `LocalTime` | `"HH:mm"` | `parseTimeLenient` |
| `Instant` | epoch millis（null 存 0） | `Converters` |
| `List<String>`（warnings） | `\u0001` 分隔（不是逗号，warnings 里可能含逗号） | `encodeStrings` / `decodeStrings` |

排序是刻意的：让两次同步的写入结果可比对，避免同一条数据因集合顺序不同而产生无意义的差异。

---

## 3. 学期开始日期的四级解析优先级

开学日期是整个课表日期换算的锚点，但它**不在教务系统的任何课表接口里**。
`SemesterStartResolver.resolve` 按可信度从高到低取：

| 优先级 | 来源 | `SemesterStartSource` | 是否持久有效 | 是否需要 UI 提示校准 |
|---|---|---|---|---|
| 1 | **用户手填** | `USER` | 是（唯一跨同步存活的级别） | 否 |
| 2 | **从 `pkrq` 反推** | `DERIVED` | 否（每次同步可重算） | 否 |
| 3 | **内置已知表** | `KNOWN_TABLE` | 否 | 否 |
| 4 | **粗略猜测** | `GUESSED` | 否 | **是** |

```kotlin
// data-repository/SemesterStartResolver.kt
if (existing != null && SemesterStartSource.fromName(existing.startSource) == SemesterStartSource.USER) {
    Mappers.parseDateLenient(existing.semesterStart)?.let {
        return SemesterStartResult(it, SemesterStartSource.USER)
    }
    // 标记为 USER 但日期不可解析（旧版本脏数据）：落回自动流程
}
val samples = courses.flatMap { TermCalendar.samplesFrom(it) }
TermCalendar.deriveSemesterStart(samples)?.let { return SemesterStartResult(it, SemesterStartSource.DERIVED) }
knownStart?.let { return SemesterStartResult(it, SemesterStartSource.KNOWN_TABLE) }
return SemesterStartResult(TermCalendar.guessSemesterStart(term.year, term.semester), SemesterStartSource.GUESSED)
```

为什么是这四级：

1. **用户手填**：用户已经明确表达过日期，任何自动值都不得覆盖。判断依据是
   `start_source == USER`，而不是"数据库里有值"（自动反推的值也会落盘）。
2. **从 `pkrq` 反推**：`getDataList` 的每行都带具体上课日期与周次，
   `weekOneMonday = pkrq - (zc-1) 周 - (xq-1) 天`，多行给出多个候选，取**众数**抗单条脏数据。
   这是最可靠的自动来源。
3. **内置已知表** `KnownSemesterStarts`：当课表走 `xsAllKbList`（不返回 `pkrq`）时反推不可用。
   这张表收录条目很少（见 `KnownSemesterStarts`），每学期需要人工补一行。
4. **瞎猜**：第一学期猜 9 月第一个周一、第二学期猜 2 月下旬，误差可达两周。
   这条分支必须返回 `GUESSED` 让 UI 提示校准。

### 注意 2 与 1 的顺序

**即使用户之前手填过，只要当前来源不是 `USER`，就重新反推。**
因为自动值可能来自上个学期或一次失败同步的兜底猜测，重算没有坏处。

### 第 1 周对齐到周一

`TermCalendar.weekOneMonday = semesterStart.previousOrSame(MONDAY)`：如果开学日期不是周一，
"第 1 周"必须对齐到周一，否则周三之前的课会显示成上一周。

`weekOf` 必须用 `Math.floorDiv`：Kotlin/Java 整数除法向零截断，
开学前一天（`days = -1`）会算成 `-1/7 = 0`，于是"开学前那个周日"被判定为第 1 周。
`floorDiv(-1, 7) = -1`，+1 得 0 才是对的。

### 维护方式

`KnownSemesterStarts` 是本项目**唯一需要人工周期性维护**的数据。补充方法：

1. 用 `getDataList` 拿一学期课表，任取一行的 `pkrq` 和 `zc`，反推；或
2. 直接看校历（教务处每年发布）。

**不要凭印象编造日期**，宁可留空让 `guessSemesterStart` 兜底并在 UI 提示校准。

---

## 4. 配色持久化策略

自动配色是"课程名排序后按位次分配"（`CourseColors.assign`）。

### 为什么按课程名排序而不是遍历顺序

按遍历顺序递增分配的话，数组顺序来自服务端返回顺序，一旦教务系统调整排序，
或用户手动加了一门课插在中间，**全部课程的颜色都会重新洗牌**。
把去重后的课程名**排序**后按位次分配，结果对服务端返回顺序完全免疫。

### 为什么要把结果存下来

排序分配仍有残留问题：新增一门排序靠前的课时，它之后的课仍会顺移。
所以首次分配的结果持久化到 `course_color` 表（`is_user_chosen = false`），
之后**只有全新课程名才参与分配**，老课程颜色永久固定。

`CourseColorPolicy.plan` 的策略：

- 已持久化的课程名（无论自动还是用户手选）**原样保留**；
- 只有**全新的**课程名才参与分配并落盘；
- 用户手选的条目（`setCourseColor` 写入 `is_user_chosen = true`）永远不会被自动流程改写。

"重置配色"（`resetColors`）只删 `is_user_chosen = 0` 的条目，用户手选的保留。

### 内置调色板

调色板与旧版保持一致（11 色，顺序不变）：
red, olive, green, hiwamoegi, cyan, grey, blue, pink, yellow, mauve, purple。
`hiwamoegi`（若草色）是一个日语色名，保留以维持 key 稳定。
`CourseColor.key` **一旦发布就不能改** —— 改了会让所有已保存的自定义配色失效。

### `course.color_key` 与 `course_color` 表

同步时配色分配结果**同时**写进 `course_color` 表与 `course.color_key` 列；
UI 走 `CourseColors.assign(courses, colorKeys)`，以 `course_color` 表为准。

---

## 5. 数据库迁移策略

### 当前状态

`GdutDatabase.VERSION = 1`，**尚未发布**，所以 debug 构建直接用
`fallbackToDestructiveMigration(dropAllTables = true)`（改 schema 就重建，开发期最省事）。

```kotlin
val builder = Room.databaseBuilder(context, GdutDatabase::class.java, FILE_NAME)
    .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
if (destructiveMigrationFallback) {
    builder.fallbackToDestructiveMigration(dropAllTables = true)
}
```

`destructiveMigrationFallback` 由 `DefaultAppContainer` 按 `BuildConfig.DEBUG` 决定，
**release 绝不允许** —— 那会在一次 schema 变更后静默清空用户的整学期课表和成绩。

### schema 导出

`core-database/build.gradle.kts` 配置了 `room.schemaLocation = $projectDir/schemas`，
导出的 JSON 在 `core-database/schemas/com.gdutday.core.database.GdutDatabase/1.json`。

**schema JSON 必须提交进仓库**：它是写 `MigrationTest` 的唯一依据，
也是将来 review 迁移正确性的凭据。Room 只在配置了 `room.schemaLocation` 时才导出；
等发布后再补，中间几个版本的 schema 就永远丢了。

### 发布前必须做的事

1. 把 debug 的 destructive 回退去掉，改成 release 只允许显式 `Migration`
   （例如按 `BuildConfig.DEBUG` 分成两条构建路径，release 分支不调用
   `fallbackToDestructiveMigration`）。
2. 每次改 schema 都 `VERSION++` 并写一个 `Migration`。
3. `schemas/` 目录随每次 schema 变更提交。
4. `MigrationTest`（Robolectric）已存在：手工按 v1 DDL 建旧库、跑迁移、
   Room 按 v2 schema 逐列校验并断言旧数据保留。每次新增迁移都要照此补一个用例。
5. 不要轻易改 `GdutDatabase.FILE_NAME`（`"gdutday.db"`）—— 改名等于让所有老用户的数据消失。

### 为什么不 `exportSchema = false`

那会让"数据库结构随版本怎么变的"完全没有记录。一个存着用户整学期课表和成绩的库，
迁移出错等于数据全丢，代价太高。

---

## 6. 会话与偏好的存储

### 6.1 会话 cookie → Keystore 加密文件

见 `core-datastore/SessionStore.kt`。为什么 cookie 比密码还敏感：

- `JSESSIONID`（jxfw 域）**就是会话本身**，拿到它无需密码即可访问教务系统；
- 密码泄露用户可以改，**cookie 泄露用户根本不知道**；
- cookie 有效期比一次登录长得多（`rememberMe=true` 时更久）；
- 它同时代表 authserver 的 TGT，能横向访问其它子系统。

措施：

1. 存在 `filesDir` 下的独立文件 `gdutday_session.enc`，**Keystore AES/GCM 加密**，
   不进 SharedPreferences；
2. 日志/异常只允许出现 `GdutSession.toSafeString()`（cookie 只列数量，不列值）；
3. **绝不上传**到任何第三方；
4. 退出登录时 `SecureFile.secureErase()`：写入随机字节 + `fsync` 后删除。
   这不是密码学意义上的可靠擦除（闪存磨损均衡无法保证原地覆盖），但比普通删除强得多。

### 为什么不用 `EncryptedSharedPreferences`

- `androidx.security:security-crypto` 处于维护模式；
- 首次创建 master key 在部分机型上耗时数秒（直接拖慢冷启动）；
- master key 一旦损坏（OTA、备份恢复、多用户切换）就无法恢复，
  整个 SharedPreferences 文件报废，App **每次启动都崩溃**，只能靠用户手动清数据。

自己用 `AndroidKeyStore` + AES/GCM 写 60 行，行为完全可控，
且解密失败时**静默丢弃**（当作未登录）而不是崩溃。见 `AndroidKeystoreCipher`。

关键技术点：

- GCM 的 IV（12 字节）**绝不重复**，每次加密随机生成并前置存放（`IV || 密文+Tag`）；
- 解密失败一律返回 null，覆盖：密钥不存在、GCM Tag 校验失败、密文长度非法、Keystore 内部异常；
- `setUserAuthenticationRequired(false)`：要求用户认证会让后台同步与 Widget 进程无法解密；
- `setIsStrongBoxBacked(true)` 优先启用，**静默降级**（捕获 `ProviderException`，
  不引用 API 28 的 `StrongBoxUnavailableException` 类本身，避免 minSdk 26 上的类加载风险）；
- **不设过期时间**。

`SecureFile.read()` 在解密失败或 JSON 非法时**顺手删文件**：
两种情况下这份密文都永远解不开了，留着只会让每次冷启动重复一次注定失败的解密。

`EncryptedValueStore` 用"懒加载 + 内存缓存"：构造时**不读盘**，首次订阅 `flow` 或调用
`current()` 时才读。这是冷启动优化的一部分。

### 6.2 记住密码（默认关闭）

密码落盘等于把账号的最后一道防线放进文件系统（root 设备、`adb backup`、
各种"数据恢复"工具都能读到），所以本项目的立场：

- `rememberPassword` **默认 false**，勾选处要有风险说明；
- 勾选后密码经 `KeystoreCipher` 加密落盘，硬件级密钥不出 TEE；
- **只用于**会话过期后的静默重登，不用于任何其它用途；
- 更好的替代方案（已实现）：优先靠 cookie 保活（`rememberMe=true`）。

`StoredCredentials.password` 是明文（内存中），**刻意不覆写 `toString()` 隐藏它** ——
因为 `data class` 的 `copy`/解构要用，隐藏了反而让人误以为它安全。
纪律靠 `SessionStore` 的注释和 code review 保证。

### 6.3 `UserSettings` → DataStore Preferences

`UserSettings` 是一个不可变 data class + 单个 `Flow`，而不是几十个独立
`Flow<Boolean>` / `Flow<Int>`：

1. 设置页需要一次性读到全部值来渲染，几十个 Flow 要 `combine`，而 `combine` 参数上限是 5；
2. 设置项之间有依赖（`customTimetableEnabled == true` 时 `customTimetable` 才有意义），
   放在一个类型里可以用计算属性表达；
3. DataStore 本来就是"整个 Preferences 作为一个原子快照"的模型。

代价：改任一设置都会通知所有观察者。设置项二十来个、观察者只有课表页和设置页，
不构成性能问题。

解析策略：**宽松 + 回退默认，绝不崩溃**。枚举用各自的 `fromName`，学期用 `Term.parse`，
透明度钳制到 `ALPHA_RANGE`，自动同步间隔钳制到一周。

自定义作息表存成 **JSON 数组字符串**，而不是 `stringSetPreferencesKey`：
Preferences 的 Set 存取不保证顺序，而 24 项 `HH:mm` 是"第几节"的严格序号，
乱序整张作息表就错了。
