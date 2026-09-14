---
name: "testing-strategy"
kind: "guide"
description: "Test coverage, pure JVM strategy, MockWebServer usage, fixture conventions"
alwaysApply: false
---


# 测试策略

相关文档：[架构](../architecture/architecture.md) · [协议](../reference/spec/gdut-protocol.md) ·
[登录验证](./verify-login.md)

---

## 1. 测试规模

全项目约 413 个纯 JVM 单元测试，0 失败；`core-database` 是例外，DAO 的 `@Query` SQL
只能在真实 Room + SQLite 上验证，目前没有 Robolectric/androidTest，SQL 写错不会被任何测试发现。
各模块测试数随开发变动，用下面的统计方法现查。

### 统计方法（可复现）

```bash
JAVA_HOME="C:/Program Files/Microsoft/jdk-21.0.12.101-hotspot" \
  gradle :core-model:test :core-common:test :data-gdut:test :core-datastore:test \
          :data-repository:test :widget:test :core-ui:test \
          :feature-schedule:test :feature-auth:test :feature-grade:test :feature-settings:test \
          :feature-toolbox:test
```

然后读各 `build/test-results/**/TEST-*.xml` 的 `tests` 属性求和。

---

## 2. 为什么协议层做成纯 JVM 模块

`data-gdut` 刻意**不** apply `com.android.library`，而用 `org.jetbrains.kotlin.jvm`：

1. 登录加密、表单构造、重定向跟随、HTML/JSON 解析**全部与 Android 无关**，
   用 OkHttp + jsoup 的 JVM 版本就能跑；
2. 单元测试直接跑在 JVM 上，**不需要模拟器**；
3. 学校改接口时，改这一个模块 + 补一个 fixture 就能回归。

这一决策的直接收益是 `AuthServerClientTest`：它用 MockWebServer 在 JVM 上
**离线跑完整条登录链路**（含 302 链、cookie 传递、错误页识别、加密往返），
每次改协议代码都能秒级回归，不必拿真账号撞学校服务器。

为此，所有 Client 都**接受外部传入的 OkHttpClient**，不在模块内部 `new`：
Android 侧统一配置超时/TLS/日志/CookieJar，测试侧塞 MockWebServer 的 client。
地址通过 `GdutHosts.forTestServer(baseUrl)` 注入，默认值取自 `GdutEndpoints`。

`core-model` / `core-common` 同样是纯 JVM，所以课表几何、学期历、节次切分、配色分配
这些最容易算错、又最难在真机上肉眼定位的纯逻辑，都能用固定输入钉死。

---

## 3. 真实 fixture 的作用与来源

位置：`data-gdut/src/**test**/resources/fixtures/`
（注意：**不是** `src/main/resources`，凭据/抓包不进 main 产物）。

| 文件 | 来源 | 说明 |
|---|---|---|
| `authserver_login_page.real.html` | `GET .../authserver/login?type=userNameLogin` | 不带 `service`，页面里 `var service = null;` |
| `authserver_login_page_with_service.real.html` | `GET jxfw/new/ssoLogin` 跟随 1 次 302 的落点 | 带 `service`，值是 JSON 数组 |
| `authserver_encrypt.js.real` | `.../common/encrypt.js` | **加密算法的地面真相** |
| `authserver_login.js.real` | `.../web/js/login.js` | 提交逻辑、滑块、改 action |
| `authserver_dzlogin.js.real` | `.../web/js/dzlogin.js` | 同一套逻辑的非压缩版 |

抓取时间 **2026-09-10**，方式 curl + 桌面版 Chrome UA，**未登录状态**。
页面里的 `pwdEncryptSalt` 与 `execution` 都是**一次性、会话绑定、已过期**的值，
保留原值是为了让测试验证"从真实页面解析出真实字段"。

### 为什么必须有真实 fixture

手写合成 fixture 无法暴露真实页面的怪癖，而这些怪癖恰恰是最容易踩的：

- 登录页开头带**两个** U+FEFF BOM，而 U+FEFF 在 Java/Kotlin 里**不算空白字符**，
  `trim()` / `trimStart()` 去不掉，会让所有 `startsWith("<!doctype")` 之类的判断失效。
  这个坑是跑真实 fixture 才暴露的。
- `service` 的右值是**带转义斜杠的 JSON 数组**，普通字符串正则会匹配不到。
- `_badCredentialsCount` 等号两边**无空格**。

`LenientJson.sanitize` / `looksLikeHtml` 对 BOM 的处理、`extractInlineVar` 的
JS 右值扫描器，都是为了应对这些真实结构。

### 合成 fixture 的局限

jxfw 的 `jxfw_*.json` / `jxfw_*.html` 是照逆向字段**手工合成**的。
它们能验证解析器的逻辑正确性，但**不能证明字段名在学校那边真的存在**。
`fixtures/README.md` 里明确写了这点：用 [登录验证工具](./verify-login.md) 跑一次真实账号，
把 dump 出来的响应替换掉合成 fixture，才算真正闭环（开放项见 `temp/agent-task-list-open.md`）。

---

## 4. MockWebServer 怎么用

`AuthServerClientTest` 是范例。核心手法：

### 4.1 用 `Dispatcher` 按 path 路由

一个 `MockWebServer` 只有一个端口，所以 `GdutHosts.forTestServer` 把
authserver 与 jxfw 两个 base 都指向它，测试按 **path** 区分请求
（`/authserver/login` vs `/new/ssoLogin`）—— 这与生产环境的路径布局一致，
业务代码不需要任何特判。

```kotlin
server.dispatcher = object : Dispatcher() {
    override fun dispatch(request: RecordedRequest): MockResponse {
        recorded += request   // 全部记下来，供断言请求体
        return route(request)
    }
}
```

关键区分：`/new/ssoLogin` 不带 `ticket` 是 SSO **入口**（302 到统一认证并带 service），
带 `ticket=` 是 SSO **回调**（验票后 302 到首页并下发 jxfw 的 JSESSIONID）。真实环境就是这个语义。

### 4.2 可配置"剧本"

```kotlin
private inner class Script(
    var ssoEntry: () -> MockResponse = { redirect(".../authserver/login?service=...") },
    var postLogin: () -> MockResponse = { redirect(".../new/ssoLogin?ticket=ST-mock-ticket") },
    var ssoCallback: () -> MockResponse = { redirect(".../").addHeader("Set-Cookie", "JSESSIONID=JXFW-SESSION-1; Path=/; HttpOnly") },
    var jxfwHome: () -> MockResponse = { ok("") },
    var checkCaptcha: () -> MockResponse = { ... },
    var userConf: () -> MockResponse = { ok(userConfHtml("3120012345")) },
)
```

每个测试只改自己关心的那一环，其余走默认成功路径。

### 4.3 用真实登录页做输入

服务端返回的 HTML 是 `authserver_login_page_with_service.real.html`，
只把 `var service` 和 `execution` 替换成指向 MockWebServer 的值 ——
**表单结构、隐藏域、salt 全部真实**。所以"解析真实页面 → 组装请求体"这条路径被真正验证过。

### 4.4 重定向链与错误页的覆盖方式

| 测试 | 剧本改动 | 验证点 |
|---|---|---|
| 完整登录流程 | 默认剧本 | 拿到两个域的 JSESSIONID |
| POST 带 `?service=` | 默认 | 请求 path 以 `/authserver/login?service=` 开头 |
| POST body 含空 name 的 salt 字段 | 默认 | `body[""] == "xaOfScaw6epvgypH"` |
| password 是密文、不含 `passwordText` | 默认 | 密文长度 > 60、能 `decryptStripPrefix` 还原 |
| Base64 的 `+ / =` 被百分号编码 | 默认 | 原始 body 不含裸露 `+` `/` |
| 密码错误 | `postLogin` 返回填了 `#showErrorTip` 的登录页 | 抛 `BadCredentials`，`userMessage` 为服务端原文 |
| 回到登录页但无错误文案 | `postLogin` 返回干净登录页 | 抛 `UnexpectedLoginResult` 而非 `BadCredentials` |
| 需要滑块 | `checkCaptcha` 返回 `{"isNeed":true}` | 抛 `CaptchaRequired`，且**没有任何 POST 被发出** |
| 会话没建起来 | `ssoCallback` 200、`jxfwHome` 302 回 authserver | 抛 `SessionExpired` |
| 无限重定向 | 每跳 ticket 递增（避免被循环检测提前停下） | 抛 `TooManyRedirects` 并附完整链路 |
| 登录页变维护页 | 覆盖 dispatcher 让 `GET /authserver/login` 返回维护页 | 抛 `Parse` 并附诊断 |

`checkNeedCaptcha` 的 mock 故意用 `Content-Type: text/plain`，
验证实现没有依赖"响应必须是 JSON"。

---

## 5. 各层测试的分工

| 层 | 测什么 | 不测什么 |
|---|---|---|
| `core-model` | 值对象的构造校验、编码、`parseWeeks`、`parseScore`、异常文案 | —— |
| `core-common` | 作息表、`weekOf` 的 floorDiv、节次切分、网格并排布局、开学日期反推 | Android/Compose |
| `data-gdut` 纯函数 | 表单编码、JS 右值解析、AES 黄金值、JSON 提取、字段容错 | 真实学校服务器 |
| `data-gdut` 端到端 | MockWebServer 上的完整登录链、错误页识别、重定向 | 真实 TLS/风控 |
| `core-datastore` | AES/GCM 往返、IV 唯一、篡改拒绝、`SecureFile` 解密失败删文件、设置映射 | 真实 AndroidKeyStore、真实 DataStore 文件 |
| `data-repository` | `buildScheduleUiState` 的 Flow 装配、学期选择、配色持久化、冲突判定、开学日期四级优先级 | Room 真实 SQL（用 `flowOf` 假数据） |
| `widget` | 快照映射、空状态分类、尺寸→行数、刷新间隔 | 真实 Glance 渲染 |
| `feature-*` | 登录校验/错误映射、成绩格式化/趋势点、考试分组/日期标签/相对天数、设置映射、网格几何 | Compose UI |

`AesGcmCipherTest` 用**假的 `AesKeyProvider`** 在普通 JVM 上完整验证：
往返一致、IV 每次不同、篡改被 GCM Tag 拒绝、换密钥必失败。
真正依赖 AndroidKeyStore 的只剩 `AndroidKeystoreKeyProvider` 一个类，
它没有值得用模拟器去测的分支。

---

## 6. 缺什么

以下全部**没有被任何测试覆盖**（开放项清单见 `temp/agent-task-list-open.md`）：

| 缺失 | 风险 |
|---|---|
| `androidTest`（真机） | —— |
| Robolectric | —— |
| **Room 迁移测试** | schema 变更后老用户数据可能丢/迁移报错，只有真机才能发现 |
| **DAO SQL 测试** | `@Query` 写错不会被任何测试发现（`core-database` 零测试） |
| **真实 AndroidKeyStore 行为** | 强盒降级、密钥丢失后的静默丢弃、`setUserAuthenticationRequired(false)` 的实际效果 |
| **Glance 渲染** | `SizeMode.Exact`、`LocalSize`、`FlowDataStore` 是否真的每次重读 |
| **`SecureFile.secureErase`** | 真实文件系统上的覆盖+删除行为 |
| **Compose UI 测试** | 页面交互、无障碍、点击目标 |
| **截图测试** | 视觉回归 |
| **release/R8 包** | `AppContainerHolder` 静态 holder 取容器、ProGuard 规则是否够 |

`core-database` 的 `GdutDatabase.inMemory()` 已经为 Robolectric 准备好了
（用真实 Room + SQLite 而不是 mock DAO —— SQL 写错 mock 发现不了），只是还没有测试去用它。

---

## 7. 学校改接口时的回归流程

1. **定位症状**：同步失败的 `GdutException.Parse` 会带上原始响应片段（截前 500 字符）。
   用户可在"设置 → 诊断信息"复制出来。
2. **跑验证脚本**：[`gradle :data-gdut:verifyLogin`](./verify-login.md)
   用真实账号逐步打印登录页、跳转链、课表/考试/成绩条数，定位在哪一步断。
3. **抓真实响应**：把断点那一步的响应原文存成 `fixtures/<name>.real.*`。
4. **改解析器**：`data-gdut` 的解析器只依赖 fixture，改完立刻能离线测。
5. **补/改测试**：`AuthServerClientTest` / `JxfwParserTest` / `JxfwScheduleParserTest`。
6. **若只是某个接口下线**：
   - 课表：`ScheduleEndpoint.AUTO` 已能自动回退，用户无感；确认后考虑改默认值；
   - 其它接口：改 `GdutEndpoints` 常量（**所有硬编码地址集中在此，只改这一个文件**）。
7. **若 URL 整体迁移**：只改 `GdutHosts` 的默认值 + `GdutEndpoints` 文档，
   代码不引用常量。

`GdutEndpoints` 每条常量都标注了**实测状态与来源**，判断"这条还能不能用"时先看它。
`GdutHosts` 与 `GdutEndpoints` 的分工：前者是运行时配置、后者是协议文档兼默认值来源。

### 7.1 故障排查案例：不是学校接口的问题

**2026-09-12，「同步考试安排」按钮 100% 失败，错误只有一句"同步失败"。**

排查结论：不是接口问题 —— `GradeViewModel.refreshExams()` 直接在
`viewModelScope.launch`（主线程）里调 `ScheduleRepository.sync()`，
而 sync 流程里的 OkHttp 是阻塞调用，抛 `NetworkOnMainThreadException`，
message 为 null，被包装成没有信息量的 `GdutException.Local("同步失败", 类名)`。
课表下拉刷新（WorkManager）与成绩同步（自己 `withContext(IO)`）都在后台线程，
所以只有这一个按钮挂。

**教训与排查路径**（按这个顺序走能省很多弯路）：

1. **先分清"全挂还是单入口挂"**：其它同步入口正常时，几乎可以排除
   接口改版 / TLS / 风控 / 服务端故障 —— 这些会让所有入口一起挂；
2. **看"设置 → 诊断信息"的 lastError**：release 包没有 HTTP 日志
   （`logHttp=false`），`sync_state.last_error` 是唯一落盘的错误现场；
3. **报错文案越笼统，越要怀疑计划外异常**：`GdutException` 家族都有具体的
   `userMessage`，只有走到 `catch (e: Exception)` 兜底分支才只剩"同步失败"；
4. **Repository 入口自己切 `withContext(Dispatchers.IO)`**，
   不指望每个调用方（尤其 `viewModelScope` 默认主线程）记得切 ——
   阻塞式 OkHttp 是项目刻意的线程模型（见架构文档·线程模型），切线程责任必须收口。

设备侧取证备注：release 包 `run-as` 不可用（非 debuggable）、无线 adb 无
`INJECT_EVENTS` 权限（`input tap` 被拒）、logcat 无 App 自身日志 ——
让用户读诊断页是唯一低成本通道；要拿 logcat 现场需装 debug 包
（`applicationIdSuffix=".debug"`，可与 release 并存，不覆盖用户数据）。

---

## 8. 写测试的约定

- 测试方法名用反引号中文（如 `` `完整登录流程能走通并拿到两个域的会话` ``），
  失败时一目了然。
- 断言用 Google Truth（`assertThat`）。
- 时间相关的纯函数把 `now` 作为参数传入，**不在测试里读墙钟**，避免跨天/跨时区抖动。
- 涉及随机数的地方注入 `Random(seed)` 或固定 IV，保证可复现。
- 不在测试里打真实网络；`data-gdut` 的网络测试一律走 MockWebServer。
- 跨模块的 fixture 只放在 `data-gdut/src/test/resources/`。
