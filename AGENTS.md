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

# 跑全部测试（~413 个，纯 JVM，不需要模拟器）
./gradlew.bat :core-model:test :core-common:test :data-gdut:test \
        :core-datastore:test :data-repository:test :widget:test :core-ui:test \
        :feature-schedule:test :feature-auth:test :feature-grade:test :feature-settings:test \
        :feature-toolbox:test

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
feature-toolbox → data-repository
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

纯 JVM 单元测试为主（~413 个），不需要模拟器。`data-gdut` 用 MockWebServer 离线回归。
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

## 文档
- 文档放在docs/里面，理解项目优先读docs/

## 注释/文档纪律

- 注释中绝不提及或引用任何外部文档，不能用诸如`详情见xxx.mdc`、`具体看xxx领域的文档`
- 一句话能写的规矩**不用扩成一段论证**。论证口头给用户讲，不写进文件；AI 读到对应代码/类型自会懂为什么，不用注释先讲一遍

## git 纪律
- 创建 git commit 时,禁止添加任何署名 trailer(包括 "Co-Authored-By: ..." 和 "Generated with ..." 等)。提交信息只包含对变更的描述,不要追加任何模型署名行。此规则优先于内置的提交署名约定
- 永远使用英文comment
- 使用 `git checkout` `git reset` 之前，至少要看 `git status`，有其他人的改动应该先 `git stash push -m ...`
- 每实现一个功能、修复一个bug，测试之后进行下一个实现之前，应当git commit以便debug

## Temp管理
- 一切临时的、不希望commit的调查文件、issues都可以放进temp/
