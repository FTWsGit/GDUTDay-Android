# 03 · UI 规格

覆盖四个页面（课表 / 登录 / 成绩 / 设置）、课表网格渲染方案、并排冲突布局、
自动字色、隐私打码，以及各种边界情况。

相关文档：[架构](./00-architecture.md) · [数据模型](./02-data-model.md) ·
[插件规格](./04-widget-spec.md) · [任务清单](./05-agent-task-list.md)

---

## 0. 全局导航

- **单 Activity + Compose Navigation**（`MainActivity` + `GdutDayNavHost`）。
  多 Activity 每次切换要走完整生命周期 + 窗口创建（约 100～300ms）；
  单 Activity 内 Compose 导航只是重组（几毫秒），且只需维护一个窗口的主题/insets。
- **起始路由是课表，不是登录页**（理由见 [架构 · 冷启动](./00-architecture.md)）。
- 底部导航只放**课表**和**成绩**两项 + 最右侧的设置入口。
  中文标签在窄屏容易折行，每多一项就挤占一分横向空间；设置页使用频率极低
  （配置一次几个月不动），不值得占一个常驻位置。
- 底部导航只在顶层目的地（课表、成绩）显示。设置页和登录页是"下钻"页面，
  显示导航栏会让返回语义混乱。
- 所有页面用 `enableEdgeToEdge()` + `WindowInsets` 处理状态栏/手势区。
  Android 15（API 35）起 `targetSdk 35` 强制边到边。

---

## 1. 课表页（`feature-schedule`）

### 1.1 数据来源

只收集**一个** Flow：`ScheduleRepository.observeScheduleUiState()`。
课程、考试、学期历、作息表、配色、状态判定全部在 Repository 里算好。
`ScheduleViewModel` **一行计算都不做**，只转发 Flow、持有纯页面交互状态
（打开了哪个详情、哪条横幅被关掉）。

为什么把计算放 Repository 而不是 ViewModel：

1. Widget 需要同样的计算（"今天有什么课"、"下节课几点"），逻辑否则得复制一份；
2. ViewModel 会膨胀到上千行，且每次旋转屏幕都要重算；
3. 单元测试要挂 `Dispatchers.Main` 和 Android 环境，跑不起来。

`ScheduleUiState` 的每一项都对应旧小程序里一段散落在 `.vue` 模板里的逻辑，
集中之后"课表显示不对"只需要查一个地方。

### 1.2 顶栏（`ScheduleTopBar`）

| 元素 | 内容 | 行为 |
|---|---|---|
| 学期名 | `state.term.displayName` + 下拉箭头 | 点击弹出 `DropdownMenu`（`availableTerms`）切换学期 |
| 周次 | `第 12 周（11/17 - 11/23）` | 只读；周历缺失时只显示"第 N 周" |
| "回到本周" | `TextButton` | **只在浏览别的周次时出现**，否则永远没用的按钮白占位 |
| 更多菜单 | `MoreVert` | 周/日视图切换、手动同步、设置入口 |

### 1.3 主内容分支

```text
isInitialLoad && grid == null && isSyncing  → 居中 CircularProgressIndicator（仅此一种全屏 loading）
grid == null && !hasAnyData                → EmptyState「未登录」
grid == null                                → EmptyState「无数据」
scheduleView == DAY                         → DayScheduleView(todayBlocks)
else                                        → WeekGridView(grid)
```

**首屏不等同步**：只有"无任何本地数据且正在同步"才显示居中 loading，
否则立即渲染缓存；同步只驱动顶部 `LinearProgressIndicator` 与下拉刷新指示器。

### 1.4 顶部横幅

| 条件 | 语气 | 动作 | 可关闭 |
|---|---|---|---|
| `semesterStartSource.needsUserConfirmation`（即 `GUESSED`） | WARNING | 打开日期选择器校准 | 是（仅本次进程内） |
| `campus == UNKNOWN` | INFO | 打开设置 | 是 |
| 同步失败（`errorMessage`） | —— | `Snackbar`，非阻塞 | 一次性 |

开学日期错了**整个周次全错**，所以它是全屏里最该被看见的提示。
同步失败时**网格数据原样保留**，只用 Snackbar 提示 —— 这是与旧小程序最大的体验差异之一。

### 1.5 周次切换

在网格上**横向拖动**切换周次（自定义 `detectHorizontalDragGestures`，阈值 64dp）：
`dragged > threshold` → 上一周，`< -threshold` → 下一周。
周次由 ViewModel 钳制到 `[1, totalWeeks]` 后调 `repository.selectWeek`。
选中周是**纯内存状态**，不落盘（每次打开 App 回到"本周"更符合直觉）。

### 1.6 课程详情（`CourseDetailSheet`）

点击任意色块弹出。内容包含完整课程信息、颜色选择器、删除入口。
详情里的色块颜色要**跟随改色实时刷新**：`ScheduleScreen` 用 `resolveLatestBlock`
在最新网格里按 `naturalKey + startMinute` 找回同一条，替换快照。

---

## 2. 课表网格的渲染方案

### 2.1 三层结构（性能关键）

课表是 7 列 × 12 节的矩阵，但**实际有内容的格子通常不到 25 个**。
如果用 `Column { repeat(12) { Row { repeat(7) { ... } } } }` 铺满，
会产生 84 个格子、每格再嵌套若干文字节点，整屏**上千个 Composable 节点**，
纵向滚动时重组与布局开销直接体现为掉帧。

因此 `WeekGridView` 拆成三层：

| 层 | 实现 | 产生的节点数 |
|---|---|---|
| 背景层 | 一个 `Canvas` 画完节次栏底色、今天列高亮、全部网格线、节次文字 | **0 个布局节点**（文字用 `TextMeasurer` 直接绘制） |
| 课程块层 | 自定义 `Layout`，只对真实存在的色块测量/摆放 | 每门课 1 个（通常 ≤ 25 个） |
| 交互 | 色块自身可点击 | 空网格不需要热区 |

节点数从 `84 × 每格文字数 ≈ 上千` 降到 `实际课程数 ≤ 25`。
`CourseBlockLayer` 是整个页面唯一会"按数据量增长"的节点来源，而非按网格尺寸增长。

几何在 `remember(grid, visibleDays, dayWidthPx, ...)` 里预计算成 `PlacedBlock`，
滚动/重组不重新分配；`Layout` 阶段直接把像素坐标 `place` 下去，不在 `measure` 里现算。

### 2.2 为什么纵向按分钟而不是按节次定位

`CourseBlock.startMinute` / `endMinute` 是当天分钟数。按节次整数格摆会画错，因为：

- 考试只给具体时刻（`08:30--10:05`），映射到"第几节"会损失精度；
- 自定义课程可能被用户设成任意时段；
- 用 `[startMinute, endMinute)` 统一表达，UI 用同一套定位逻辑画课程、考试和自定义条目。

`ScheduleGridMath.minuteToY` 按比例线性插值；网格纵向范围
`WeekGrid.verticalRange` = min(作息表首节开始, 本周最早色块) … max(作息表末节结束, 本周最晚色块)，
所以早于第 1 节或晚于第 12 节的条目也能画下。

节次信息仍保留在 `CourseBlock.course` 里，用于"第几节"的文字展示。

`ScheduleGridMath` 全部是纯函数、不依赖 Compose，可跑 JVM 单测：

```kotlin
fun minuteToY(minute: Int, range: IntRange, gridHeightPx: Float): Float {
    val total = totalMinutes(range)
    val offset = (minute - range.first).coerceIn(0, total)
    return offset.toFloat() / total * gridHeightPx
}
```

### 2.3 并排冲突布局算法

同一时段有多门课时，旧版直接叠在一起（后画的盖住先画的），用户看不到被盖住的课。
`ScheduleGridBuilder.layout` 用经典的"区间分列"算法：

1. 按开始时间升序（开始相同则长的在前）排序；
2. 切成"冲突簇"——互相传递重叠的块归为一簇；
3. 簇内贪心分配列：找第一个"上一块的结束时间 ≤ 当前块开始时间"的列，找不到就新开一列；
4. 簇内所有块的 `columnCount` = 该簇用到的列数。

```text
输入（时间轴 →）：
  A: |---------|
  B:      |---------|
  C:                 |------|

簇1 = {A, B}，簇2 = {C}
A.columnIndex=0  B.columnIndex=1  B.columnCount=2（A、B 各占一半宽）
C.columnIndex=0  C.columnCount=1（不冲突的 C 仍占满宽）
```

**按簇而非全局统一 `columnCount`**：避免一门跨全天的课把其它不冲突的课也压成 1/2 宽。

UI 只负责按 `columnIndex / columnCount` 缩窄并右移（`ScheduleGridMath.blockX` /
`blockWidth`），**绝不能再自行判冲突**。

### 2.4 色块的视觉状态叠加顺序

从底到顶（`CourseBlockItem`）：

1. 课程底色 × `UserSettings.courseBlockAlpha`；
2. 考试 / 自定义的语义色罩层（`examTint` / `customTint`）；
3. 已上完且 `dimFinishedCourses` 打开时的置灰遮罩（`finishedScrim`）；
4. 正在上课的描边（`ongoingBorder`，**不随透明度变化**，否则高亮会被调淡）。

**透明度只作用在背景上，不影响文字** —— 把文字也调透明会让它在浅色底上彻底消失。

课程名最多 3 行（`ScheduleBlockText.COURSE_NAME_MAX_LINES`），
11sp / 紧凑行高；教室、老师 9sp 单行省略。这些"块内文字"规格放在
`ScheduleBlockText` 而不是 `GdutDayTypography`，因为它们不是通用排版层级。

---

## 3. 自动字色与 WCAG 相对亮度

`CourseTextColor.AUTO` 时按背景色挑黑字或白字。

公式（WCAG 2.x 相对亮度）：

1. 每个 sRGB 通道归一化到 `[0, 1]`；
2. sRGB 线性化：`c ≤ 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ^ 2.4`；
3. 加权求和 `0.2126 R + 0.7152 G + 0.0722 B`（人眼对绿最敏感、蓝最不敏感）。

**刻意不使用** WCAG 的对比度比值阈值 `(L1+0.05)/(L2+0.05) > 4.5`：
课程块面积小、字号只有 11sp，黑/白两个极值里选哪个本来就只是二选一；
用单一相对亮度阈值 `0.5` 更简单，且与旧小程序 `isLightColor` 的观感一致。

```kotlin
fun isLightBackground(argb: Int): Boolean = relativeLuminance(argb) > 0.5
fun autoTextColor(argb: Int): Int = if (isLightBackground(argb)) BLACK else WHITE
```

透明度被忽略：`AUTO` 判据用调色板里的**不透明** `argb`。
把 alpha 也算进去会让同一门课在不同透明度下字体忽然变色，反倒更令人困惑。

绝对中灰（`#808080`）的线性亮度约 0.216，落在"深色"一侧，
也就是说只有相当明亮的颜色才会被判定为浅色。

抽成纯 JVM 函数的理由：这个判断做错的表现是**特定颜色的课上文字几乎看不见**，
而 UI 测试很难稳定复现 11 种调色板 × 深色/浅色主题的组合。纯函数可以穷举单测。

---

## 4. 隐私打码

### 4.1 打码边界（重要）

需求是"公共场合防偷窥"，不是"让界面变得没用"，所以**只打码课程名和老师名**，
**时间与教室保持可读**。如果连时间和地点都打掉，用户在地铁上想确认"下一节课在哪"
也做不到，功能就失去意义了。

| 文本 | 是否打码 |
|---|---|
| 课程名 | 是 |
| 教师名 | 是 |
| 教室 | **否** |
| 时间（`08:30`） | **否** |
| 节次标签 | **否** |

打码内容用 `·`（中文间隔号）而不是 `*`：`*` 在等宽显示下容易和列表符号混淆，
`·` 更接近"内容被遮住"的观感，且中英文混排宽度稳定。

### 4.2 等长规则

```kotlin
fun String.privacyMasked(): String {
    if (isEmpty()) return this
    val len = length
    if (len <= 2) return "·".repeat(len)   // 短串整串替换
    return buildString(len) { append(first()); repeat(len - 2) { append('·') }; append(last()) }
}
```

- **等长**：课程名和老师名的宽度直接影响布局。若打码后长度变化，开关打码的瞬间
  整张课表的文字宽度会集体抖动（甚至撑破课程块），用户还能凭长度差反推原文有几个字。
- 保留首尾字符；长度 ≤ 2 时无法两全（2 个字符的首尾恰是全部内容），
  此时**整串替换为同长度掩码**，隐私优先于可读性。

> 实现细节：必须先取 `val len = length`。`buildString` 的 lambda 里 `length`
> 会解析成 `StringBuilder` 自身的当前长度，导致掩码数量算错。有单元测试盯着。

### 4.3 两个开关

`privacyBlurEnabled`（总开关，默认 false）与 `privacyBlurInWidget`（插件开关，默认 true）。
两者都开才在插件里打码：默认跟随总开关，但用户也可单独关掉插件打码
（手机只有自己用、桌面想看真名）。

---

## 5. 登录页（`feature-auth`）

### 5.1 交互

| 元素 | 行为 |
|---|---|
| 顶部 `FilterChip` ×2 | 切换"统一身份认证" / "教务系统登录"，默认前者；切到后者时若还没验证码就自动取一张 |
| 学号输入框 | `KeyboardType.Number`；输入即清洗（只留数字、截断 10 位）；非法时内联提示且按钮禁用 |
| 密码输入框 | `PasswordVisualTransformation`，`ImeAction.Done` |
| 验证码区（仅直登） | 140×60 JPEG，点击整块重新获取；旁边输入框 |
| 记住密码 Checkbox | **默认关闭**；勾选后才展开风险说明 `Surface` |
| 登录按钮 | `busy` 时禁用 + 显示进度圈；文案"登录中…" |

### 5.2 学号前置校验

`LoginLogic.validateStudentId` 返回 `EMPTY / TOO_SHORT / TOO_LONG / NOT_DIGITS /
NOT_UNDERGRADUATE / VALID`。`canSubmit` 要求 `VALID`，直登路径还要求验证码与 token 都就绪。

这样做的理由：把注定失败的请求打给学校服务器，每一次**都在累积风控计数**
（见 [协议 · 滑块](./01-gdut-protocol.md)）。所以这些规则值得抽出来单独用 JVM 测试钉死。

### 5.3 滑块降级流程

`LoginViewModel.submit` 捕获 `CaptchaRequired` 后：

1. 把错误映射为 `LoginErrorKind.CAPTCHA_REQUIRED`；
2. **自动把 `method` 切到教务系统直登**并调 `refreshCaptcha()`；
3. UI 不显示"重试"按钮（按钮文案仍是"登录"，但路径已切换）。

研究生 / 教师账号映射为 `UNSUPPORTED_GRADUATE` / `UNSUPPORTED_TEACHER`，
`BadCredentials` 直接沿用 `userMessage`（可能含服务端原文，如"密码错误次数过多，账号已锁定"）。

### 5.4 安全

`LoginScreen` 与 `LoginViewModel` **均无任何日志调用**。密码只作为局部变量短暂存在，
提交给 Repository 后不再被读取。

---

## 6. 成绩页（`feature-grade`）

### 6.1 结构

```text
summaries.isEmpty() && !isLoggedIn          → EmptyState「未登录」
summaries.isEmpty() && lastSync?.at == null → EmptyState「登录了但还没同步」（带同步按钮）
summaries.isEmpty()                          → EmptyState「同步了但没有成绩」（带同步按钮）
else → ScrollableTabRow（学期）+ LazyColumn
```

### 6.2 学期 Tab 与内容

- 默认选中最新学期（`termNames.first()`，已按倒序）。
- `SummaryCard`：加权绩点 / 总学分 / 挂科数三格，等距分布。挂科数 > 0 用 `colorScheme.error`。
- `GpaTrendChart`：Canvas 手绘的绩点趋势，**无第三方图表库**（见 `GradeLogic.trendPoints`）。
- 成绩列表表头：课程 / 学分 / 成绩 / 绩点，列宽 52 / 60 / 50 dp。
- 等级制成绩走 `GradeLogic.scoreDisplay`：`score == null` 时显示 `scoreText`（如"优秀"）。
- 挂科（`score < 60`）成绩用 `colorScheme.error`。
- `countsTowardsGpa == false` 的行有"不计入绩点"入口，点击弹 `AlertDialog` 解释规则。
- 顶部右侧：同步中显示 `CircularProgressIndicator`，否则显示刷新按钮。

---

## 7. 设置页（`feature-settings`）

> ⚠ **当前实现是占位页**：`SettingsScreen` 只显示校区/视图三行文字，并注明
> "占位实现，见 KDoc"。真正的设置在 KDoc 里已经逐项列好，待实现项见
> [任务清单](./05-agent-task-list.md)。

按 KDoc 规划的分组：

### 学期与校区
- 校区选择：4 个选项 + "自动探测"。选番禺时提示"该校区作息表未经核实"。
- 学期开始日期校准：日期选择器。**最重要的设置项**，`GUESSED` 时置顶提醒。

### 课表外观
- 周视图 / 日视图切换；课程块透明度滑杆（钳制在 `ALPHA_RANGE`，不允许拖到 0）；
- 已上课程置灰、字体色（自动/白/黑）、背景图 + 模糊半径；
- 显示老师/教室、显示第 13-14 节、显示周末。

### 作息表
- 自定义开关 + 12 节起止时间编辑；
- 保存前必须用 `CampusTimetable.parseCustom` 校验，返回 null 说明输入非法
  （时间倒挂、格式错），此时**不要保存**并给出具体提示；
- 提供"恢复该校区默认"按钮。

### 数据
- 课表数据源（4 个选项，每项有说明文字）；
- 自动同步开关 + 间隔；手动同步按钮 + `SyncInfo.relativeTime()`；
- 重置配色 / 清空自定义课程 / 清空全部本地数据（后者二次确认）。

### 隐私
- 课表打码、插件打码；
- 记住密码开关：关闭时**必须真的调用** `CredentialStore.clear()` 擦除，文案说明风险。

### 关于 / 诊断
- 版本号、开源许可；
- **诊断信息**（可一键复制）：`GdutSession.toSafeString()`、`SyncInfo.source`、
  当前生效的作息表、学期开始日期及来源、同步警告列表。
  这是用户提 issue 时唯一有用的东西。
- 退出登录。

---

## 8. 边界情况处理表

| 场景 | 表现 | 依据 |
|---|---|---|
| **空课表**（本学期无课） | `grid` 非空但全列 `blocks` 为空 → `WeekGrid.isEmpty`；UI 显示空状态。同步 `success=true, courseCount=0` 时提示"本学期暂无课表" | `EmptySchedule` 只在**接口返回空**时抛；同步成功但 0 门课是正常情况 |
| **未登录** | 课表页 `grid == null && !hasAnyData` → 「未登录」空状态 + 登录入口；底部导航仍在 | `GdutDayNavHost.isLoggedIn` |
| **同步失败** | 顶部 Snackbar 提示；**已有网格数据原样保留**；`sync_state.lastSuccess=false`，下次进入仍提示 | `ScheduleRepositoryImpl.recordFailure` 不碰课程/考试表 |
| **学期日期未校准**（`GUESSED`） | 顶部 WARNING 横幅 + "校准"按钮；可关闭（仅当次进程） | `SemesterStartSource.needsUserConfirmation` |
| **校区未知** | 顶部 INFO 横幅 + "去设置"；作息表回退大学城 | `CampusTimetable.of(UNKNOWN)` |
| **番禺校区** | 允许选择，但附 warning"该校区作息表未经核实，建议自定义" | `CampusTimetable` KDoc |
| **教务系统维护期间** | 用户仍能看到上次同步成功的课表 | 写路径失败不清空读路径数据 |
| **会话过期** | 尝试静默重登；失败则 `SessionExpired` → 清会话 + 跳登录页 | `AuthRepository.reloginSilently` |
| **滑块风控** | 自动切教务直登并取图形验证码 | `LoginViewModel` |
| **研究生 / 教师账号** | 登录阶段拒绝，给明确文案 | `UnsupportedUserType` |
| **开学前 / 放假后** | `todayWeek` **不钳制**（可能 ≤ 0 或 > totalWeeks），UI 自行判断"还没开学/已放假"；周次选择器钳制到 `[1, totalWeeks]` | `TermCalendar.weekOf` 不钳制 |
| **调课 / 补课** | `Course.classDates` 与"周次×星期"推算不一致时给提示 | `Course.classDates` KDoc |
| **课程块被多门课重叠** | 并排分列，每块按 `1/columnCount` 宽 | `ScheduleGridBuilder.layout` |
| **一天两段课**（`{1,2,5,6}`） | 切成两个色块，不跨午休 | `SectionRunSplitter` |
| **周末无课** | `showWeekend=false` 时只渲染 5 列，周六周日**跳过**而不是硬塞 | `ScheduleGridMath.slotOf` |
| **第 13/14 节** | 默认隐藏（`showExtraSections=false`）；内置作息只有 12 节 | `GridBackground` 的 `periods` |
| **数据库脏数据** | 读库永不抛异常，最多丢那一门课 | `Mappers` |
| **解密失败**（恢复出厂等） | 静默当作未登录，删除损坏文件，不崩溃 | `SecureFile.read` |
| **自定义作息输错** | `parseCustom` 返回 null，静默回退内置作息 | `resolveTimetable` |
| **配色 key 未知**（旧版本） | 回退 `CourseColors.DEFAULT`（pink），不抛异常 | `CourseColors.byKey` |
