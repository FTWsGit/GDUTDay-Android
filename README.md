# 广工课表 · Android 原生版

"广工课表"微信小程序（uni-app）的 **Android 原生重写**。
用 Kotlin + Jetpack Compose 重写，直连学校接口，**没有自己的服务器**。

> 学生自制的非官方客户端，与广东工业大学无隶属关系。详见 [法律声明](#法律声明)。

---

## 项目是什么 / 为什么重写

旧版是 uni-app 小程序，本质是 WebView。原生重写的主要动机（完整论证见
[`docs/00-architecture.md`](docs/00-architecture.md)）：

- **冷启动**：WebView 首次初始化通常要 300ms～1s；原生首屏直接读 SQLite。
- **内存**：WebView 常驻 50～150MB，中低端机负担明显。
- **桌面插件**：App Widget 沙箱里**根本放不下 WebView**，主体若是 WebView，插件就得另写一套。
- **离线**：原生首屏读本地数据库，教务系统维护、图书馆地下没信号时依然可用。

同时**不复用旧后端**：App 直连学校接口，不需要 `GDUTDAYS_SECRET`、不依赖第三方服务器、
少一跳、cookie 不出本机。

## 当前完成度

- 登录（统一认证 + 教务直登）、课表、考试、成绩、学期切换、图书馆二维码都有实现。
- 课表以周网格 / 日列表展示，支持并排冲突、按分钟定位、隐私打码、自动配色。
- 两个 Glance 桌面插件（今日课程 / 下节课倒计时）。
- 设置页六个分组（学期与校区、课表外观、作息表、数据与同步、隐私、关于/诊断）已完整实现。
- 测试以纯 JVM 单元测试为主，共约 **403 个**（见 [`docs/06-testing-strategy.md`](docs/06-testing-strategy.md)）。
- **尚未完成 / 已知问题**：研究生账号不支持；androidTest 为零；
  jxfw 真实 fixture 待用一次真实抓包替换；教务直登 `pwd` 加密形态未验证。
  完整清单与验收标准见 [`docs/05-agent-task-list.md`](docs/05-agent-task-list.md)。

---

## 环境搭建（坑很多，请逐条核对）

### 1. 必须 JDK 21

- **JDK 25 会被 Gradle 9.6 拒绝**，而且报错信息只有一句 `25.0.3`，非常难懂。
  原因是被 Gradle 拉起的 Kotlin 编译 daemon 不支持该 class 版本。
- JDK 17 也可以，但本项目在 **JDK 21** 上实测。
- 设置环境变量（Windows bash 示例）：

```bash
export JAVA_HOME="C:/Program Files/Microsoft/jdk-21.0.12.101-hotspot"
```

Gradle 输出里的 `Launcher JVM` / `Daemon JVM` 都应该是 21。

### 2. 工具链版本组合

| 组件 | 版本 | 说明 |
|---|---|---|
| Gradle | 9.6.1 | 本机在 `C:\tools\gradle-9.6.1`（`bin` 已入用户 PATH，`GRADLE_HOME` 已设）；`./gradlew.bat` 亦可用，发行包已缓存 |
| AGP | 9.4.0 | **自带 Kotlin 2.2.10** |
| Kotlin | 2.2.10 | 与 AGP 内置版本对齐（纯 JVM 模块显式声明） |
| KSP | 2.2.10-2.0.2 | 前缀必须等于 Kotlin 版本 |
| compileSdk / targetSdk | 37 | |
| minSdk | 26 | `java.time` 原生可用，无需 desugaring |

### 3. ⚠ AGP 9 自带 Kotlin：Android 模块**不能**再 apply `org.jetbrains.kotlin.android`

```kotlin
// ✅ 正确：Android 模块只 apply 这两个
plugins {
    alias(libs.plugins.android.application)   // 或 android.library
    alias(libs.plugins.kotlin.compose)         // 用了 Compose 才需要
}
```

如果 apply 了 `org.jetbrains.kotlin.android`，会直接构建失败：

```text
The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin support since AGP 9.0.
```

**纯 JVM 模块**（`core-model` / `core-common` / `data-gdut`）仍然用
`org.jetbrains.kotlin.jvm`，版本 2.2.10。

### 4. ⚠ `android.disallowKotlinSourceSets=false` 是 Room/KSP 与 AGP 9 共存的必要开关

`gradle.properties` 里这一行 **必须保留**：

```properties
android.disallowKotlinSourceSets=false
```

KSP 通过 `kotlin.sourceSets` DSL 注册生成源目录，AGP 9 的 built-in Kotlin 默认禁止这种写法，会报：

```text
Using kotlin.sourceSets DSL to add Kotlin sources is not allowed with built-in Kotlin.
```

关掉这个限制是目前唯一可用的办法（Room 2.8.5 + KSP 2.2.10-2.0.2 实测可编译运行）。

### 5. ⚠ `local.properties` 的 `sdk.dir` **必须用正斜杠**

```properties
# ✅ 正确
sdk.dir=C:/Users/Administrator/AppData/Local/Android/Sdk

# ❌ 错误：AGP 9 的 SdkLocator 会抛
# java.io.IOException: The filename, directory name, or volume label syntax is incorrect
# sdk.dir=C\:\\Users\\Administrator\\AppData\\Local\\Android\\Sdk
```

`local.properties` 已被 `.gitignore`，模板见 [`local.properties.example`](local.properties.example)。

### 6. Gradle 下载慢用腾讯云镜像

`gradle-wrapper.properties` 的 `distributionUrl` 已指向腾讯镜像
`https://mirrors.cloud.tencent.com/gradle/gradle-9.6.1-bin.zip`（官方源在国内极慢，
且曾导致 wrapper 下载中断留下 `.part` 残片）。发行包已解压缓存到
`~/.gradle/wrapper/dists`，`./gradlew.bat` 可离线使用。
如需其它版本，从 `https://mirrors.cloud.tencent.com/gradle/` 下载对应 zip 即可。

---

## 怎么构建 / 跑测试 / 跑登录验证

环境已持久化（2026-09-11 整理）：

- `JAVA_HOME` = `C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot`（用户级环境变量，已 setx）
- `GRADLE_HOME` = `C:\tools\gradle-9.6.1`，其 `bin` 已加入用户 `PATH`（新终端里直接敲 `gradle` 即可）
- Gradle Wrapper 已修好：`distributionUrl` 指向腾讯镜像，发行包已缓存在
  `~/.gradle/wrapper/dists`，`./gradlew.bat` 可离线使用
- **旧 shell 里没有这些变量**（setx 只对新进程生效），必要时手动：
  `export JAVA_HOME="C:/Program Files/Microsoft/jdk-21.0.12.101-hotspot"`

以下用 `./gradlew.bat`（等价于 `gradle`，二选一）：

```bash
cd "C:/Users/Administrator/Desktop/GDUTDay/gdutday-android"
```

### 构建

```bash
./gradlew.bat :app:assembleDebug
./gradlew.bat :app:assembleRelease   # 已验证可出 APK（app/build/outputs/apk/release/）
```

### 跑测试

```bash
# 全部纯 JVM 模块（推荐，快；instrumentedTest 需真机/模拟器）
./gradlew.bat :core-model:test :core-common:test :data-gdut:test \
        :core-datastore:test :data-repository:test :widget:test :core-ui:test \
        :feature-schedule:test :feature-auth:test :feature-grade:test :feature-settings:test
```

测试数量与逐模块统计见 [`docs/06-testing-strategy.md`](docs/06-testing-strategy.md)。

### 跑登录验证

一个**你自己在本机跑**的端到端登录诊断工具。**不要把真实凭据写进代码库。**

```bash
# 方式一：环境变量
export GDUT_STUDENT_ID="3120xxxxxx"
export GDUT_PASSWORD="你的密码"
./gradlew.bat :data-gdut:verifyLogin

# 方式二：项目根目录 secrets.properties（已被 .gitignore）
cp secrets.properties.example secrets.properties
# 编辑 secrets.properties 后：
./gradlew.bat :data-gdut:verifyLogin
```

它会逐步打印登录页解析、密文长度、跳转链、cookie 名与域、学期/课表/考试/成绩条数，
最后给 `RESULT: PASS/FAIL`。**绝不打印密码或 cookie 值。**
完整用法与「失败模式 → 原因 → 下一步」诊断决策表见
[`docs/07-verify-login.md`](docs/07-verify-login.md)。

---

## 模块清单

| 模块 | 一句话 |
|---|---|
| `core-model` | 纯领域模型（`Term` `Course` `Grade` `Exam` `Campus`），零第三方依赖 |
| `core-common` | 与 Android 无关的纯逻辑：作息表、学期历、节次切分、课表网格、配色 |
| `data-gdut` | **协议逆向层**（纯 JVM）：登录加密、表单、重定向、HTML/JSON 解析、图书馆二维码 |
| `core-network` | OkHttpClient 工厂 + 网络状态监听 |
| `core-database` | Room：实体、DAO、映射 |
| `core-datastore` | DataStore 偏好 + Keystore 加密的会话/凭据 |
| `core-ui` | 主题、通用组件、可离线单测的纯函数（WCAG 对比度、隐私打码） |
| `data-repository` | 编排层：读路径 Flow → UI State、写路径多接口同步、手写 DI 容器 |
| `feature-auth` | 登录页 |
| `feature-schedule` | 课表页（周网格 / 日列表） |
| `feature-grade` | 成绩页（绩点统计 + 趋势） |
| `feature-settings` | 设置页（当前为占位） |
| `widget` | 两个 Glance 桌面插件 |
| `app` | Application + MainActivity + NavHost（应用壳） |

依赖方向与"为什么这么分"见 [`docs/00-architecture.md`](docs/00-architecture.md)。

---

## 文档索引

| 文档 | 内容 |
|---|---|
| [`docs/00-architecture.md`](docs/00-architecture.md) | 模块依赖、四条核心决策（不用 WebView/Hilt/旧后端）、冷启动、线程模型、发布流程 |
| [`docs/01-gdut-protocol.md`](docs/01-gdut-protocol.md) | ★ 学校接口完整逆向报告 + **已实测/未实测清单** |
| [`docs/02-data-model.md`](docs/02-data-model.md) | Room 表结构、映射规则、开学日期四级优先级、迁移策略 |
| [`docs/03-ui-spec.md`](docs/03-ui-spec.md) | 四页面交互、网格渲染、并排布局、WCAG 字色、打码、边界情况 |
| [`docs/04-widget-spec.md`](docs/04-widget-spec.md) | 插件尺寸/内容/空状态、数据驱动、分级刷新、Glance 限制 |
| [`docs/05-agent-task-list.md`](docs/05-agent-task-list.md) | **待办任务清单**（含验收标准） |
| [`docs/06-testing-strategy.md`](docs/06-testing-strategy.md) | 逐模块测试统计、MockWebServer、缺失的测试、改接口回归流程 |
| [`docs/07-verify-login.md`](docs/07-verify-login.md) | 登录验证工具用法 + 诊断决策表 |

---

## 已知限制

- **研究生不支持**。`UserType.GRADUATE` 登录后会被拒绝；走的是完全不同的 yjsxt/ehall 体系
  （每个子应用单独授权、返回具体时刻而非节次）。已知复杂度记录在
  [`docs/01-gdut-protocol.md`](docs/01-gdut-protocol.md)。
- **学期开学日期需要人工维护**。`KnownSemesterStarts` 目前只有一条记录；
  未收录的学期会退到粗略猜测（误差可达两周），UI 会提示用户校准。
- **滑块验证无法自动通过**。统一认证触发风控滑块时，只能改用教务系统图形验证码直登，
  或先在浏览器登录一次清掉计数。
- **学校接口随时可能变更**，本项目不保证可用性。协议层的每条结论都标注了验证状态。
- **测试仅在 JVM 上**。Room 迁移、真实 AndroidKeyStore、Glance 渲染都没有真机验证。

---

## 法律声明

- 本项目是**学生自制的非官方客户端**，与**广东工业大学无任何隶属关系**，
  不代表学校立场，也未获学校授权或认可。
- 本项目**仅访问用户本人有权访问的数据**（用户自己输入账号登录后的课表、成绩、考试）。
  不含任何爬虫绕过、不含自动化答题、不绕过任何访问控制。
- 本项目**不含广告、不收集、不上传任何数据**。没有自建服务器，账号密码与
  cookie 只存在于用户设备上（cookie 经 AndroidKeyStore 加密）。
- 参考了 `gdutday` 组织的开源实现：
  - 前端 `gdutday-wechat`：**MIT**；
  - 后端 `gdutday-wechat3.0-java`：LICENSE 文件写 **Apache-2.0**，
    但 commit 记录显示为 **AGPL-3.0**，**存在冲突**。本项目**没有复用其后端代码**，
    只在协议逆向与设计取舍上参考了其思路。
- 学校接口是本项目自行逆向的记录；**接口随时可能变更**，本项目不保证可用性。
- 使用本项目的风险由使用者自行承担。请合理使用，不要高频请求学校服务器。
