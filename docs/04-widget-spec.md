# 04 · 桌面插件规格

覆盖两个 Glance 插件（`widget` 模块）、数据驱动方式、刷新策略与 Glance 的实际限制。

相关文档：[架构](./00-architecture.md) · [UI 规格](./03-ui-spec.md) ·
[任务清单](./05-agent-task-list.md)

---

## 1. 总体决策

### 为什么用 Glance 而不是传统 RemoteViews

- Glance 用 Compose 语法写插件 UI，与主 App 写法一致，能复用大量逻辑；
- 它最终仍然编译成 RemoteViews，**没有 WebView、没有额外运行时**，内存与耗电和手写 RemoteViews 相当；
- 传统 RemoteViews 只支持十几种 View，写一个 7 列课表网格要几百行 XML + 适配器，
  维护成本远高于 Glance。

### 为什么插件进程与主 App 同进程

App Widget 运行在**主 App 进程**（没有声明 `android:process`），所以可以直接用
`context.widgetContainer()` 拿到同一个容器、同一个 Room 数据库。

这是刻意的：如果给插件单独开进程，就要处理跨进程的数据库并发
（SQLite 多进程需要 WAL + 仔细的锁），复杂度陡增而收益为零。
代价是插件更新会唤醒主进程，但 Room 的 Flow 只在数据变化时发一次，
插件本身也是低频更新（一天几次），完全可接受。

> ⚠ 因为 `app` 依赖 `widget`，`widget` 不能反向引用 `app`（会形成 Gradle 项目环）。
> 所以插件通过 `data-repository` 的 `AppContainerHolder` 静态 holder 取 `AppContainer`（`WidgetContainer.kt`），
> 详见 [任务清单](./05-agent-task-list.md) 的 R8 风险项。

---

## 2. 「今日课程」插件（4×2）

### 尺寸与配置

`widget/src/main/res/xml/today_schedule_widget_info.xml`：

| 属性 | 值 |
|---|---|
| `minWidth` / `minHeight` | 250dp / 110dp |
| `targetCellWidth` / `targetCellHeight` | 4 / 2（API 31+） |
| `minResize` | 180×110dp |
| `maxResize` | 530×400dp |
| `resizeMode` | `horizontal|vertical` |
| `updatePeriodMillis` | **0**（见 §4） |
| `sizeMode`（代码） | `SizeMode.Exact`（见 §5） |

选择 4×2 的理由：这是能同时显示"日期 + 3～4 条课程"的最小尺寸，再小就只能显示 1 条。
`targetCellWidth/Height` 显式声明，让支持网格的 launcher 直接按格子摆放，
而不是用 `minWidth` 反推 —— 反推结果在不同 launcher 上不一致。

### 内容

```text
┌──────────────────────────────────────┐
│ 9月10日 周三 · 第2周            刷新 │  ← 表头
│ ▌ 08:30  高等数学                    │  ← 时间 / 课程名
│          教5-301 · 张三              │  ← 教室 · 老师
│ ▌ 10:25  大学英语                    │
│          教1-205                     │
└──────────────────────────────────────┘
```

- 表头：`{月}月{日}日 {周几}` + （周次在合法区间内时）` · 第N周`。
  放假 / 未开学时省略周次部分。
- 每行：左侧 3dp 宽的课程色块 + 开始时刻 + 课程名 + 详情（老师 · 教室）。
- 课程色块用课程自己的颜色，**不跟壁纸动态取色** —— 用户期望"高数一直是红色"。
  已完成的课只降低色块 alpha，颜色身份仍保留；课程名用 `outline` 色，正在上的加粗。
- 表头右侧"刷新"是嵌套的第二个点击目标，只做一次 `WidgetUpdateManager.updateAll()`
  然后结束，不启动 Activity。整张插件点击打开 App。

### 尺寸响应

`TodayScheduleContent` 里用 `LocalSize.current.height.value` 调 `WidgetSizing.todayRowCount`：

```kotlin
private const val HEADER_DP = 26f
private const val ROW_DP = 24f
private const val MIN_ROWS = 1
private const val MAX_ROWS = 6

fun todayRowCount(heightDp: Float): Int {
    if (heightDp <= 0f) return DEFAULT_TODAY_ROWS   // 预览/异常给 4×2 的默认值 3
    val usable = heightDp - HEADER_DP
    if (usable <= 0f) return MIN_ROWS
    return (usable / ROW_DP).toInt().coerceIn(MIN_ROWS, MAX_ROWS)
}
```

取 `floor` 而不是 `round` —— 宁可少一行也不能把最后一行切掉一半。
参数是 `Float` 而非 `DpSize`，这样这一层就是纯 JVM 代码，单元测试不需要 Compose/Android 环境。

### 空状态（`TodayPhase`）

| phase | 触发 | 文案 |
|---|---|---|
| `LOADING` | 仓库还没给出第一帧 | 加载中… |
| `NOT_LOGGED_IN` | 未登录**且没有任何本地数据** | 未登录 |
| `NO_CLASS` | 今天没排课 | 今天没有课 |
| `ALL_FINISHED` | 今天本来有课，都上完了 | 今天课程已结束 |
| `HAS_CLASS` | 正常 | —— |

`ALL_FINISHED` 与 `NO_CLASS` 必须分开：前者是"今天本来有课，都上完了"，
用户看到会觉得踏实；后者是"今天就没排课"。两者文案不同。

有本地数据时即使 `isLoggedIn=false` 也照常显示，不要因为会话过期把用户已有的课表藏起来 ——
与 `ScheduleUiState` 的错误处理原则一致。

---

## 3. 「下节课」插件（4×1）

| 属性 | 值 |
|---|---|
| `minWidth` / `minHeight` | 250dp / 40dp |
| `targetCellWidth` / `targetCellHeight` | 4 / 1 |
| `resizeMode` | `horizontal` |
| `updatePeriodMillis` | **0** |

内容：左侧 4dp 宽色条 + 课程名 + 地点（`教室 · 起止时刻`）+ 右侧倒计时。
正在上课时倒计时用 `primary` 色加粗。

倒计时**不走秒**，也不在插件里做任何计时：插件只把仓库算好的 `countdownText` 画出来，
真正的定时刷新交给 WorkManager 的分级任务。这样 launcher 进程不会因为每秒重绘而耗电。

`NextClass` 的 `countdownText` / `locationText` 逻辑与首页卡片**完全一致**
（放在 `ScheduleUiState.kt` 里），Widget 不再抄一遍，否则"文字不一致"的 bug 要两边各修一次。

空状态（`NextPhase`）：

| phase | 触发 | 文案 |
|---|---|---|
| `LOADING` | 首帧 | 加载中… |
| `NOT_LOGGED_IN` | 未登录 | 未登录 |
| `NONE` | 已登录但近期（三周内）无课 | 今天没有课（复用语义最接近的文案） |
| `HAS_CLASS` | 正常 | —— |

生命周期钩子：`onEnabled`（第一个插件被添加）启动自我重排并立即跑一次；
`onDisabled`（最后一个被移除）取消待执行任务，避免空转。

---

## 4. `updatePeriodMillis = 0` 的理由

两个插件的 `appwidget-provider` 都显式设置 `android:updatePeriodMillis="0"`，即
**禁用系统周期刷新**。理由：

1. 系统周期刷新最小间隔是 **30 分钟**，而且会**唤醒设备**，是明确的耗电源；
2. 课表数据一天最多变一次，靠它刷新毫无意义；
3. 真正需要刷新的两个时机由更精确的机制覆盖：
   - **「跨天了」** → WorkManager 在次日 0 点触发一次 `WidgetUpdateManager.updateAll()`；
   - **「数据库变了」** → Room 的 Flow 驱动（`GlanceStateDefinition`）。

两条路都比系统周期刷新精确且省电。

---

## 5. `GlanceStateDefinition` 的数据驱动方式

这是 Glance 相对 RemoteViews 的核心优势。

传统 RemoteViews 想更新插件，必须由 App 主动发广播/设 Alarm，再在 receiver 里读数据、
重建整个 RemoteViews。本实现把数据流接到 `GlanceStateDefinition` 上：

```kotlin
internal class TodayScheduleWidgetStateDefinition : GlanceStateDefinition<TodayScheduleWidgetState> {
    override fun getLocation(context: Context, fileKey: String): File = File(context.cacheDir, fileKey)

    override suspend fun getDataStore(context: Context, fileKey: String): DataStore<TodayScheduleWidgetState> {
        val container = context.widgetContainer()
        return FlowDataStore {
            combine(
                container.scheduleRepository.observeScheduleUiState(),
                container.authRepository.isLoggedIn,
            ) { ui, loggedIn ->
                TodayScheduleMapper.map(ui, loggedIn, LocalDate.now())
            }
        }
    }
}
```

Glance 每次 `update()` 都会通过 `getDataStore(...).data.first()` 重新订阅这段 combine，
于是"插件显示的内容"与"数据库里的真相"永远不会脱节，且不需要任何常驻的广播接收器或定时器。

`FlowDataStore` 是一个**只读**的 `DataStore`，`data` 每次被收集时重新订阅上游 Flow。
它存在的理由：Glance 要求返回 `DataStore<T>`，而标准的持久化 DataStore 不适合
"状态从 Room 实时派生"的场景。

它的 `updateData` **直接抛异常**：插件状态是派生数据，唯一真相在 Room/DataStore。
如果有人调 `updateAppWidgetState` 往这里写，说明状态模型设计跑偏了；
与其静默吞掉写入造成"改了没生效"，不如立刻抛出，把问题暴露在开发阶段。

数据变化的**触发**由 `WidgetUpdateManager` 负责（同步完成 / 跨天 / 手动点刷新）；
`GlanceStateDefinition` 负责的是"触发之后读到的一定是最新数据"。

### 为什么不在这里常驻订阅 Room

最直觉的做法是在 Application 里 `collect` 一遍 Room Flow，一变就 `updateAll`。
代价是**进程会被迫常驻**：插件数据一天只变几次，却要为此让 App 进程一直活着，
违背"省电、少驻留"的目标。所以保留"被动刷新"——有人明确知道数据变了才推一次。

---

## 6. 下节课倒计时的分级刷新策略

### 策略表

| 场景 | 刷新间隔 | 理由 |
|---|---|---|
| 今天还有课，距开始 > 60 分钟 | 60 分钟 | 一小时内分钟数变化对用户不敏感 |
| 今天还有课，距开始 ≤ 60 分钟 | 1 分钟 | 用户开始关注倒计时 |
| 正在上课 | 1 分钟 | 要更新"还剩 X 分钟下课" |
| 今天已无课 / 近期无课 | 次日 0 点一次 | 中间没有任何信息会变 |

```kotlin
fun delayMillis(hasClassToday: Boolean, isOngoing: Boolean, startsInMinutes: Long, now: LocalDateTime): Long {
    if (!hasClassToday) return millisUntilNextMidnight(now)
    if (isOngoing) return MINUTE_MILLIS
    return if (startsInMinutes <= SHORT_RANGE_MINUTES) MINUTE_MILLIS else HOUR_MILLIS
}
```

注意 `hasClassToday` 的定义是"今天是否还有未结束（含正在进行）的课"，
**不是** `next != null`：下一节课可能是明天/下周的，此时仍应睡到次日 0 点。

`millisUntilNextMidnight` 的下限钳到 1 分钟，避免刚好卡在 0 点时算出 0
导致 WorkManager 立即执行 → 数据没变 → 又排到 24 小时后，白白多跑一次。

全部函数把"现在"作为参数传入，不读 `LocalDateTime.now()`：
单元测试可以固化时间，不依赖真实墙钟，也不会在跨天边界抖动。

### 为什么用"一次性任务自我重排"而不是 `PeriodicWorkRequest`

周期任务只能有一个固定间隔，而倒计时的间隔是**动态**的。用固定周期只能取最小间隔
（1 分钟），那在"离上课还有 3 小时"时就是纯粹的电量浪费。

所以每次 `NextClassRefreshWorker` 执行完，都根据**最新**的下一节课距离重新 `enqueue`
下一次任务。WorkManager 在 Doze 下会合并唤醒，比 AlarmManager 省电，
且进程被杀/重启后任务仍能恢复。

避免任务堆积的机制：**唯一工作名** + `ExistingWorkPolicy.REPLACE`。
选 REPLACE 而不是 KEEP：KEEP 会保留第一次排的计划，
导致"刚同步出 10 分钟后的课，却还在用 1 小时前的旧计划"。

Worker 里有一个必须处理的细节：`ExistingWorkPolicy.REPLACE` 会取消**同名**的待执行任务，
而当前正在运行的正是同名任务，于是它会请求取消自己。所以在 `NonCancellable` 上下文里排下一次：

```kotlin
private suspend fun reschedule(next: NextClass?) {
    withContext(NonCancellable) {
        NextClassRefreshScheduler.scheduleNext(applicationContext, next, LocalDateTime.now())
    }
}
```

Worker 失败时（数据库瞬时锁、进程被杀）先按"今天已无课"排到次日 0 点兜底，
再 `Result.retry()`，**绝不让倒计时永久停摆**。

`NextClassRefreshWorker` 不在 `GdutWorkerFactory` 里注册（那个工厂只认 `ScheduleSyncWorker`），
走 WorkManager 默认反射工厂，所以构造函数必须是标准 `(Context, WorkerParameters)`。

---

## 7. Glance 的实际限制清单

写插件 UI 前必须知道这些，否则会踩坑：

| # | 限制 | 表现 / 对策 |
|---|---|---|
| 1 | **不支持 `Canvas`** | 不能用 `androidx.compose.foundation.Canvas` 画自定义图形。课程色块只能用 `Box + background + cornerRadius` 拼。想在插件里画课表网格是做不到的。 |
| 2 | **`Text` 只认 Glance 的 `TextStyle`** | 必须 `import androidx.glance.text.Text / TextStyle / FontWeight`，**不能**用 Material3 的 `Text` 或 Compose 的 `TextStyle`。字号用 `sp`，颜色用 `ColorProvider(GlanceTheme.colors.xxx)`。 |
| 3 | **不能混用 `androidx.compose`** | 插件 `@Composable` 里只能 import `androidx.glance.*`；`androidx.compose.runtime.Composable` 是唯一共用的（注解本身）。不小心 import 了 Material3 组件会编译失败或运行时不渲染。 |
| 4 | **`SizeMode` 默认是 `Single`，拉伸不重组** | 默认只在第一次确定尺寸后渲染一次，用户拉大插件不会重新组合，`LocalSize` 永远停在初始值。必须显式 `override val sizeMode = SizeMode.Exact`，"拉大后显示更多"的承诺才成立。 |
| 5 | **状态是只读派生数据** | `FlowDataStore.updateData` 直接抛异常。所有刷新路径必须是 `update()` / `WidgetUpdateManager.updateAll()`，不能 `updateAppWidgetState` 写入。 |
| 6 | **没有动画 / 每秒重绘** | 倒计时不走秒，也做不了流畅动画。这是 RemoteViews 的本质限制。 |
| 7 | **点击目标是嵌套覆盖** | Glance 里后声明的 `clickable` 覆盖父级，所以表头"刷新"能嵌在整张插件的点击里。`actionStartActivity` 的 Intent 要用 `remember` 缓存，不要每次重组重建。 |
| 8 | **跨模块不能引用 `app` 的 Activity** | 用 `packageManager.getLaunchIntentForPackage(packageName)` 运行时解析启动 Activity，而不是 `actionStartActivity<MainActivity>()`（那样 `widget` 要编译期引用 `app`）。 |
| 9 | **`getLocation` 必须给一个可被系统清理的路径** | Glance 在删除插件实例时会 `delete()` 这个文件。本项目状态不落盘，用 `context.cacheDir`。 |
| 10 | **资源受限** | Glance 的布局能力远弱于 Compose，复杂布局要靠 `Row` / `Column` / `Box` 手工拼。 |

---

## 8. 已知待办

- ~~**`WidgetContainer` 用反射取容器**（R8 风险），建议改为 `data-repository` 里的静态 holder。~~
  **已修复**：改为 `AppContainerHolder`，零反射。
- ~~**同步完成后调用 `WidgetUpdateManager.updateAll()` 的接线未完成**~~
  **已修复**：通过 `SyncListeners` 回调 + `GdutDayApplication.onCreate` 注册接线。
  目前只能靠跨天任务或用户手动点刷新，同步出的新课表不会立刻反映到桌面。
- `NextClassWidget` **没有显式设置 `sizeMode`**（默认 `Single`），
  但它只有一行、不承诺拉伸显示更多，所以可接受；若要支持更高尺寸需补上。
- 所有 Glance 渲染行为**都没有在真机/Robolectric 上验证过**（无 androidTest）。

详见 [任务清单](./05-agent-task-list.md)。
