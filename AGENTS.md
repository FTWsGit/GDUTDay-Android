# AGENTS.md

## 1. 项目一句话

"广工课表" Android 原生客户端。Kotlin + Jetpack Compose，直连学校接口

---

## 2. 工具链（硬约束，违反即构建失败）

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | **21** | JDK 25 被 Gradle 9.6 拒绝；JDK 17 可用但未实测 |
| Gradle | 9.6.1 | `./gradlew.bat` 或系统 `gradle` |
| AGP | 9.4.0 | **自带 Kotlin 2.2.10** |
| Kotlin | 2.2.10 | 与 AGP 内置版本对齐 |
| KSP | 2.2.10-2.0.2 | 前缀必须等于 Kotlin 版本 |
| compileSdk / targetSdk | 37 | |
| minSdk | 26 | `java.time` 原生可用 |

### ⚠ AGP 9 自带 Kotlin

Android 模块（`com.android.application` / `com.android.library`）**不能**再 apply
`org.jetbrains.kotlin.android`，否则直接构建失败。只 apply 这两个：

```kotlin
plugins {
    alias(libs.plugins.android.application)  // 或 android.library
    alias(libs.plugins.kotlin.compose)       // 用了 Compose 才需要
}
```

**纯 JVM 模块**（`core-model` / `core-common` / `data-gdut`）仍用
`org.jetbrains.kotlin.jvm`，版本 2.2.10。

### ⚠ `gradle.properties` 必须保留

```properties
android.disallowKotlinSourceSets=false
```

这是 Room/KSP 与 AGP 9 共存的必要开关。删除会导致 `kotlin.sourceSets DSL` 报错。

### ⚠ `local.properties` 的 `sdk.dir` 必须用正斜杠

```properties
# ✅ 正确
sdk.dir=C:/Users/Administrator/AppData/Local/Android/Sdk
# ❌ 错误：AGP 9 的 SdkLocator 会抛 IOException
```

---

## 3. 构建 / 测试 / 验证命令

```bash
# 构建
./gradlew.bat :app:assembleDebug
./gradlew.bat :app:assembleRelease 

# 跑全部测试（~403 个，纯 JVM，不需要模拟器）
./gradlew.bat :core-model:test :core-common:test :data-gdut:test \
        :core-datastore:test :data-repository:test :widget:test :core-ui:test \
        :feature-schedule:test :feature-auth:test :feature-grade:test :feature-settings:test

# 登录验证（需要真实凭据，存在于secrets.properties）
export GDUT_STUDENT_ID="3120xxxxxx"
export GDUT_PASSWORD="你的密码"
./gradlew.bat :data-gdut:verifyLogin
```

---

## 4. 模块架构

### 依赖方向（硬规则，违反即编译失败）

```
app → feature-* / widget / data-repository
widget → data-repository          （widget 不得依赖 app，否则 Gradle 项目环）
feature-* → data-repository / core-ui
data-repository → core-database / core-datastore / core-network / data-gdut
core-database → core-model
core-datastore → data-gdut / core-common
core-network → data-gdut
core-ui → core-common / core-model
data-gdut → core-common / core-model
core-common → core-model
```

**两条铁律**：
1. 依赖只从上往下。`core-model` / `core-common` / `data-gdut` 在最底层，不依赖任何模块。
2. **`widget` 不得依赖 `app`**。反向依赖会形成 Gradle 项目环。

### 模块职责速查

| 模块 | 类型 | 一句话 |
|---|---|---|
| `core-model` | 纯 JVM | 领域模型：`Term` `Course` `Grade` `Exam` `Campus`，零第三方依赖 |
| `core-common` | 纯 JVM | 作息表、学期历、节次切分、课表网格、配色（与 Android 无关） |
| `data-gdut` | 纯 JVM | **协议逆向层**：登录加密、表单、重定向、HTML/JSON 解析、图书馆二维码 |
| `core-network` | Android | OkHttpClient 工厂 + 网络状态监听（极薄） |
| `core-database` | Android | Room：实体、DAO、映射 |
| `core-datastore` | Android | DataStore 偏好 + Keystore 加密的会话/凭据 |
| `core-ui` | Android | 主题、通用组件、纯函数（WCAG 对比度、隐私打码） |
| `data-repository` | Android | 编排层：读路径 Flow → UI State、写路径多接口同步、手写 DI 容器 |
| `feature-auth` | Android | 登录页 |
| `feature-schedule` | Android | 课表页（周网格 / 日列表） |
| `feature-grade` | Android | 成绩页（绩点统计 + 趋势） |
| `feature-settings` | Android | 设置页（六个分组已完整实现） |
| `widget` | Android | 两个 Glance 桌面插件 |
| `app` | Android | Application + MainActivity + NavHost（应用壳，代码量控制在几百行内） |

---

## 5. 核心设计决策（Agent 必须遵守）

### 5.1 不用 WebView

原生重写的收益：冷启动、内存、桌面插件、离线。**不要引入任何 WebView 相关代码**。

### 5.2 不用 Hilt / Dagger

手写 DI 容器在 `data-repository/AppContainer.kt`，所有字段 `by lazy`，零启动开销。
**不要引入 Hilt、Dagger 或任何 DI 框架**。容器 < 20 个可注入类型，一个文件搞定。

### 5.3 直连学校，不做后端

App 直连 `authserver.gdut.edu.cn` / `jxfw.gdut.edu.cn`，没有自己的服务器。
**不要引入任何第三方后端 API 调用**。cookie 不出本机。

### 5.4 协议层做纯 JVM

`data-gdut` 模块用 `org.jetbrains.kotlin.jvm`（不是 `com.android.library`），
登录加密、HTML 解析全部与 Android 无关。学校改接口时**只改这一个模块 + 补 fixture**。

### 5.5 冷启动优化

- `Application.onCreate` 只做 O(1) 操作，所有字段 `by lazy`。
- 起始路由是课表页（不是登录页），首屏直接读 Room。
- **不在字段初始化里做 I/O**。`database` 的 lazy 只拿到 `RoomDatabase` 实例，
  真正的 SQLite 打开发生在第一次查询。

### 5.6 线程模型

`data-gdut` 里所有 Client 方法都是**阻塞**的（OkHttp 同步调用），刻意不引入 `suspend`。
切线程的责任在调用方（`withContext(Dispatchers.IO)`）。

### 5.7 测试策略

纯 JVM 单元测试为主（~403 个），不需要模拟器。`data-gdut` 用 MockWebServer 离线回归。
**不要写需要模拟器才能跑的测试**（除非明确要求 androidTest）。

---

## 6. 编码约定

### 命名

- 测试方法名用**反引号中文**（如 `` `完整登录流程能走通并拿到两个域的会话` ``），失败时一目了然。
- 断言用 **Google Truth**（`assertThat`）。
- Room 表名用**单数蛇形**（`course` 而不是 `courses`）。

### 安全

- `LoginScreen` 与 `LoginViewModel` **均无任何日志调用**。
- 密码只作为局部变量短暂存在，提交给 Repository 后不再被读取。
- cookie **绝不打印值**，只列数量/名称/域。
- `secrets.properties` 已被 `.gitignore` 忽略，**永远不要提交**。

### 配置管理

- 所有硬编码 URL 集中在 `GdutEndpoints.kt`，运行时代码不引用它，引用 `GdutHosts.kt`。
- `GdutHosts` 默认值取自 `GdutEndpoints`，测试时指向 MockWebServer。
- 纯 JVM 模块用 `GdutHosts.forTestServer(baseUrl)` 注入测试地址。

### 数据库

- `core-database` 的 schema JSON **必须提交进仓库**（`core-database/schemas/`）。
- release 构建**绝不**调用 `fallbackToDestructiveMigration`。
- 每次改 schema → `VERSION++` + 写 `Migration` + 提交 `schemas/*.json`。

---

## 7. 已知限制

- **研究生不支持**。`UserType.GRADUATE` 登录后会被拒绝。
- **学期开学日期需要人工维护**。`KnownSemesterStarts` 目前只有两条记录。
- **滑块验证无法自动通过**。触发风控时只能改用教务系统图形验证码直登。
- **学校接口随时可能变更**，不保证可用性。
- **测试仅在 JVM 上**。Room 迁移、AndroidKeyStore、Glance 渲染都没有真机验证。

---

## 8. 文档索引

| 文档 | 内容 | Agent 何时读 |
|---|---|---|
| [`docs/00-architecture.md`](docs/00-architecture.md) | 模块依赖、四条核心决策、冷启动、线程模型、发布流程 | 改架构前必读 |
| [`docs/01-gdut-protocol.md`](docs/01-gdut-protocol.md) | 学校接口完整逆向报告 + 已实测/未实测清单 | 改协议层前必读 |
| [`docs/02-data-model.md`](docs/02-data-model.md) | Room 表结构、映射规则、开学日期四级优先级、迁移策略 | 改数据库前必读 |
| [`docs/03-ui-spec.md`](docs/03-ui-spec.md) | 四页面交互、网格渲染、并排布局、WCAG 字色、打码 | 改 UI 前必读 |
| [`docs/04-widget-spec.md`](docs/04-widget-spec.md) | 插件尺寸/内容/空状态、数据驱动、分级刷新、Glance 限制 | 改插件前必读 |
| [`docs/05-agent-task-list.md`](docs/05-agent-task-list.md) | **待办任务清单**（含验收标准） | 找活干时读 |
| [`docs/06-testing-strategy.md`](docs/06-testing-strategy.md) | 逐模块测试统计、MockWebServer、缺失的测试 | 写测试前必读 |
| [`docs/07-verify-login.md`](docs/07-verify-login.md) | 登录验证工具用法 + 诊断决策表 | 排查登录问题时读 |

---

## 9. 常见陷阱

1. **AGP 9 自带 Kotlin**：Android 模块再 apply `org.jetbrains.kotlin.android` → 构建失败。
2. **`local.properties` 用反斜杠**：AGP 9 的 SdkLocator 抛 IOException。
3. **登录 POST 漏了 `?service=`**：CAS 不签票据，登录看似成功却拿不到 jxfw 会话。
4. **`pwdEncryptSalt` 没有 `name` 属性**：提交时变成空名键值对，漏掉会被服务端拒绝。
5. **`passwordText` 绝不发送**：浏览器提交前 disabled 了这个控件。
6. **AES 的 64 字节前缀和 16 字节 IV 是随机的**，不是常量。旧后端误把某次抓包的随机值硬编码了。
7. **Base64 的 `+ / =` 必须百分号编码**：不编码会把密文里的 `+` 变成空格。
8. **`execution` 是一次性、会话绑定的**：每次登录必须新建 `SessionCookieJar`。
9. **课表两个接口的 Referer 不同**：`xsAllKbList` 必须带特定 Referer，其它用首页 `/`。
10. **`jcdm` 的两位拼接格式**：`"0102"` = 第 1、2 节，不是第 12 节。必须按连续段切分。
11. **劳动教育 bug**：`xnxqdm=""` 查询时劳动教育成绩为空，需单独重查。
12. **`app` 模块代码量应始终保持在几百行内** —— 一旦膨胀说明有东西放错地方了。

---
