# 05 · Agent 任务清单

给后续实现者的任务清单。每项包含**现状**、**为什么**、**验收标准**。
优先级：P0 = 阻断（构建/数据安全），P1 = 功能缺失，P2 = 健壮性/体验。

在开始前请先读 [架构](./00-architecture.md) 与 [协议](./01-gdut-protocol.md)。
本轮并行改动较多，动手前先 `gradle test` 确认基线。

---

## P0 · 阻断项

### T0.1 `app` 模块资源链接失败：`Theme.Material3.DayNight.NoActionBar` 不存在

- **现状**：`gradle :app:processDebugResources` 报
  `AAPT: error: resource style/Theme.Material3.DayNight.NoActionBar not found`。
  `app/src/main/res/values/themes.xml` 里 `Theme.GdutDay` 继承该 style，
  但 `app` 只依赖 Compose 的 `androidx.compose.material3`，没有依赖 View 体系的
  `com.google.android.material:material`。整个 `app` 无法构建，`gradle test` 也会被它带崩。
- **可选修复**：
  1. 在 `libs.versions.toml` 加 `com.google.android.material:material` 并让 `app` 依赖它；或
  2. 把父主题改成平台自带的 `android:Theme.Material.Light.NoActionBar` /
     `Theme.AppCompat` 之类；或
  3. 改成一个不依赖 Material Components 的纯 `android:Theme.Material.*`。
- **验收**：`gradle :app:assembleDebug` 成功；`gradle test` 不再因 `:app:processDebugResources` 失败。

### T0.2 `core-model` 单元测试失败

- **现状**：`CoreModelTest.SnapshotAndGradeTest."ScheduleSource 覆盖了两个本科接口"` 断言
  `ScheduleSource.entries.map { it.label }` 至少包含 `"xsAllKbList"`、`"getDataList"`、`"本地"`，
  但 `ScheduleSource` 只有 `ALL_KB_LIST` / `DATA_LIST` / `UNKNOWN`，**没有 "本地"**。
  `"本地"` 实际是 `ScheduleFetchStrategy.LOCAL_ONLY` 的 label。
- **为什么**：要么测试写错了（把配置枚举的项当成结果枚举），要么 `ScheduleSource`
  应补一个 `LOCAL` 值（"仅本地"策略下同步结果确实没有命中任何远程接口）。
- **验收**：`gradle :core-model:test` 全绿；若新增枚举值，同步诊断信息的映射
  （`scheduleSourceFromName`）也要同步处理。

### T0.3 Release 签名配置未设置

- **现状**：`app/build.gradle.kts` 的 `signingConfigs {}` 为空，release 不绑定 `signingConfig`，
  `assembleRelease` 只能产出 unsigned apk。
- **要求**：从 `~/.gradle/gradle.properties` 或 CI secret 读取
  `GDUTDAY_STORE_FILE` / `GDUTDAY_STORE_PASSWORD` / `GDUTDAY_KEY_ALIAS` / `GDUTDAY_KEY_PASSWORD`，
  在项目里**不提交**任何密钥。`*.keystore` 已在 `.gitignore`。
- **验收**：本机配好属性后 `gradle :app:assembleRelease` 产出已签名的 apk，
  且 `git status` 里没有任何密钥文件。

### T0.4 Room 迁移与发布策略

- **现状**：`GdutDatabase.VERSION = 1`，debug 用 `fallbackToDestructiveMigration`，
  release 也不允许破坏性迁移这件事**只在 KDoc 里写了，代码里没有强制**。
- **要求**：
  1. release 构建路径**绝不**调用 `fallbackToDestructiveMigration`；
  2. 每次改 schema → `VERSION++` + 写 `Migration` + 提交 `core-database/schemas/*.json`；
  3. 补 `MigrationTest`（Robolectric 或 androidTest）。
- **验收**：新增一个 V1→V2 的 toy 迁移 + 测试，能建旧库、跑迁移、断言数据保留。

---

## P1 · 功能缺失

### T1.1 ~~`LibraryRepositoryImpl.qrModuleCount` 硬编码成 21~~ ✅ 已完成

- **已修复**：`qrModuleCount` 从 `val = 21` 改为 `suspend fun moduleCount(): Int?`，
  内部调用 `LibraryQr.moduleCount(content)` 实算。`LibraryQr` 新增了 `moduleCount()` 方法，
  用 `Encoder.encode` 只做 Reed-Solomon 编码不生成图像，微秒级。
- **测试**：`GdutHostsTest` 和 `SyncStateDaoContractTest` 已补齐。

### T1.2 配色没有回写 `course.color_key` 列

- **现状**：`CourseColorPolicy.plan` 只把分配结果写进 `course_color` 表；
  `course` 表的 `color_key` 列在同步时是否被赋值需要确认。
- **要求**：明确二选一：
  1. `course.color_key` 作为`course_color` 的冗余缓存，同步时一并写入；或
  2. 删除 `course.color_key` 列（若确实不用），避免"看起来该有值却是 null"的死数据。
- **验收**：写一个同步后的 DAO 测试，断言 `course.color_key` 与 `course_color` 表一致，
  或断言该列已被移除且所有引用同步清理。

### T1.3 ~~Widget 用反射取 `AppContainer`，R8 下有风险~~ ✅ 已完成

- **已修复**：在 `data-repository` 新增 `AppContainerHolder`（进程级 `@Volatile` 静态 holder），
  `GdutDayApplication.onCreate` 调用 `AppContainerHolder.install(container)`，
  widget 的 `WidgetContainerAccess` 直接读 holder，不再用反射。
- **测试**：`assembleRelease` 已通过验证。

### T1.4 ~~同步完成后没有调用 `WidgetUpdateManager.updateAll()`~~ ✅ 已完成

- **已修复**：在 `data-repository` 新增 `SyncListeners` 回调注册表（依赖倒置），
  `ScheduleRepositoryImpl.sync()` 在数据落库后调用 `SyncListeners.notifyCompleted(info)`，
  `GdutDayApplication.onCreate` 注册了一个监听者调用 `WidgetUpdateManager.updateAll()`。
  失败也会通知（插件上的"上次更新"时间需要收敛）。
- **验收**：`ScheduleSyncWorker` 成功同步后（可在日志/测试里观察）插件被刷新；
  没有引入模块环。

### T1.5 研究生支持（`UserType.GRADUATE`）完全未实现

- **现状**：登录后识别出研究生即抛 `UnsupportedUserType`；无任何 yjsxt/ehall 代码。
- **复杂度记录**（见 [协议 §7](./01-gdut-protocol.md#7-研究生yjsxt--ehall体系记录当前不实现)）：
  - 每个子应用单独 POST 授权（`wdcjapp` 成绩、`wdkbapp` 课表）；
  - 课表返回具体时刻 `KSSJ`/`JSSJ` 而非节次，需时刻→节次映射；
  - `ZCBH` 21 位 0/1 串、`ZCMC` 文本周次；
  - 字段全大写、需要连堂课合并；
  - ehall 三层嵌套 `datas.<app>.rows`。
- **要求**：新增 `data-gdut/yjs/` 与对应的 `UserType` 分支；
  `Course.parseWeeks` 已支持单双周文本，可复用。
- **验收**：用一个真实研究生账号（或脱敏 fixture）跑通"登录 → 授权 → 课表解析"。

### T1.6 `KnownSemesterStarts` 只有一条记录

- **现状**：表里只有 `Term(2025, 1) -> 2025-09-01`。
- **要求**：每学期开学后补一行（维护方式见 [数据模型 §3](./02-data-model.md)）。
  后续学期会落到 `guessSemesterStart`，误差可达两周。
- **验收**：新增学期条目，并跑 `TermCalendarTest`/`SemesterStartResolverTest` 覆盖"命中已知表"分支。

### T1.7 设置页是占位实现

- **现状**：`feature-settings/SettingsScreen.kt` 只显示校区/视图三行文字；
  完整的设置项在 KDoc 里已列出（见 [UI 规格 §7](./03-ui-spec.md)）。
- **要求**：按 KDoc 分组实现：学期与校区、课表外观、作息表、数据、隐私、关于/诊断。
  记住密码关闭时必须真的 `CredentialStore.clear()`。
- **验收**：每一项都能读写 `UserSettings` 并即时生效；自定义作息非法输入被拒绝并有提示；
  诊断信息可一键复制且不含密码/cookie 值。

---

## P2 · 健壮性与验证

### T2.1 androidTest / Robolectric 一个都没有

- **现状**：全部是 JVM 单元测试（见 [测试策略](./06-testing-strategy.md)）。
  下列**从未被真实验证**：
  - `AndroidKeystoreCipher` 在真实 KeyStore 上的行为（强盒降级、密钥丢失后的静默丢弃）；
  - Room 迁移（无 `MigrationTest`）；
  - Glance 渲染（`SizeMode.Exact`、`LocalSize`、打码后的布局）；
  - `SecureFile.secureErase` 在真实文件系统上的行为；
  - `WidgetContainerAccess` 在 release/R8 下的行为。
- **要求**：至少补 Keystore 与 Room 迁移的 androidTest；Glance 用 Robolectric 或截图测试。
- **验收**：`gradle connectedAndroidTest`（或 Robolectric）在 CI 上有至少上述几类测试通过。

### T2.2 真实 fixture 需要替换

- **现状**：只有 authserver 的页面/脚本是 `.real` 抓包。jxfw 的 `jxfw_*.json` / `jxfw_*.html`
  是**照着逆向字段手工合成**的，不能证明字段名在学校那边真的存在。
- **要求**：用 [登录验证工具](./07-verify-login.md) 跑一次真实账号，把 dump 出来的响应
  存成 `.real` fixture 替换合成的，并更新解析器（尤其 `kcbh` / `jxbmc` / `pkrq` / `ksrq`）。
- **验收**：`data-gdut/src/test/resources/fixtures/` 下 jxfw 相关文件带 `.real` 后缀；
  解析器测试直接消费真实响应。

### T2.3 教务直登的 `pwd` 是否加密未验证

- **现状**：`JxfwDirectLogin.login` 直接发 `pwd=<明文>`，KDoc 明确标"未验证"。
- **要求**：浏览器抓一次真实直登请求确认；若加密，很可能是 authserver 那套 `encrypt.js`，
  可直接调 `AuthServerCrypto.encryptPassword`。
- **验收**：用验证脚本走一次直登成功（或确认明文确实可用）。

### T2.4 `xsAllKbList` 是否仍存活未验证

- **现状**：`ScheduleEndpoint.AUTO` 先试它、失败回退 `getDataList`，所以即使下线也用户无感，
  但无法据此判断"该接口是否还该保留"。
- **要求**：用验证脚本的真实输出确认；若已下线，考虑默认改用 `DATA_LIST` 或删掉 A 接口。
- **验收**：验证脚本的课表阶段打印出实际命中的接口名。

### T2.5 文档引用了不存在的测试

- `GdutHosts` KDoc 说默认值由 `GdutHostsTest` 交叉校验 → 仓库里没有该文件。
- `Daos.SyncStateDao` KDoc 说 `SyncStateDaoTest` 断言 `SINGLETON_ID == 1` → 没有该文件。
- **要求**：补上这两个测试，或修正 KDoc 删除引用。
- **验收**：KDoc 里出现的测试类名都真实存在。

### T2.6 `NextClassWidget` 未显式设置 `sizeMode`

- 默认 `Single` 对单行插件可接受，但它与 `TodayScheduleWidget` 的显式 `Exact` 不一致。
  若产品希望"拉高后显示更多信息"，需要显式改 `Exact` 并做尺寸分支。
- **验收**：决策记录在插件 KDoc 里。

### T2.7 `ScheduleRepositoryImpl.sync` 未显式切 IO

- **现状**：`sync()` 里调用的是阻塞的 `JxfwClient` 方法，本身不带 `withContext(Dispatchers.IO)`，
  依赖调用方（`ScheduleSyncWorker`）在后台线程。
- **风险**：若将来有人从主线程直接调 `repository.sync()`，会阻塞 UI。
- **要求**：在 `sync` 内部加 `withContext(Dispatchers.IO)`（或至少加注释锁死调用契约）。
- **验收**：`ScheduleSyncWorker` 仍是唯一调用点，或内部已切线程。

---

## 完成标准模板

提交任何一项时请附：

1. 改动文件清单（含行数）；
2. `gradle test` 的模块级测试数（见 [测试策略](./06-testing-strategy.md) 的统计方式）；
3. 若涉及协议：用 [登录验证工具](./07-verify-login.md) 的真实输出片段（**脱敏后**）；
4. 若涉及 UI/插件：真机或 Robolectric 的验证证据。
