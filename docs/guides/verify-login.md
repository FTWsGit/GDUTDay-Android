---
name: "verify-login"
kind: "guide"
description: "End-to-end login verification tool, credential setup, failure diagnosis"
alwaysApply: false
---


# 07 · 端到端登录验证工具

一个**你自己在本机跑**的登录验证脚本。它走真实的 `AuthServerClient.login()` +
`JxfwClient`，打到学校的 `authserver.gdut.edu.cn` / `jxfw.gdut.edu.cn`，
逐步打印整条链路，最后给出 `PASS` / `FAIL`。

- 工具源码：`data-gdut/src/**test**/kotlin/com/gdutday/data/gdut/tools/VerifyLogin.kt`
  （放在 test source set，**凭据相关代码绝不进 main / 不入 apk**）。
- Gradle 任务：`:data-gdut:verifyLogin`。

**本工具不会把密码或 cookie 值写进任何输出。** 详见 [第 4 节](#4-安全约定)。

相关文档：[协议逆向](../reference/spec/gdut-protocol.md) · [测试策略](./testing-strategy.md)

---

## 1. 配置凭据

### 方式：环境变量 `secrets.properties`

`secrets.properties` 已被 `.gitignore` 忽略，**永远不要提交它**。

文件里面包含
```
export GDUT_STUDENT_ID=学号
export GDUT_PASSWORD=密码
```
因此只需要:
```
source secrets.properties # 即可获得`$GDUT_STUDENT_ID`, `$GDUT_PASSWORD` 环境变量
```

---

## 2. 怎么跑

```bash
cd "C:/Users/Administrator/Desktop/GDUTDay/gdutday-android"
# JAVA_HOME 已持久化到用户环境变量；旧 shell 里若没有则手动：
# export JAVA_HOME="C:/Program Files/Microsoft/jdk-21.0.12.101-hotspot"

./gradlew.bat :data-gdut:verifyLogin
```

工具按 9 个阶段输出：

| 阶段 | 内容 |
|---|---|
| 头部 | 学号（脱敏）、密码长度（不显示内容）、本科格式检查 |
| `[1/9]` | 取登录页：入口地址、302 跳转链、落点、HTML 大小 |
| `[2/9]` | 解析登录表单：action、提交地址、service、captchaSwitch、badCredentialsCount、execution 长度、salt（脱敏）、隐藏域列表、空 name 字段是否存在 |
| `[3/9]` | 复刻浏览器加密：明文长度、密文长度、密文前 8 字符（**不打印全文**） |
| `[4/9]` | `checkNeedCaptcha.htl`：是否需要验证码、原始响应前 200 字符 |
| `[5/9]` | `AuthServerClient.login()`：`UserType`、登录方式、诊断摘要、cookie（**只列名与域**） |
| `[6/9]` | 学期列表：数量 + 前 5 个 |
| `[7/9]` | 课表（命中哪个接口、条数、前 3 条）、考试条数 + 校区探测、成绩条数 |
| `[8/9]` | **冷启动模拟**：仅凭已保存的 cookie 重建一个全新客户端，验证会话能否跨进程复用。这是 App "首屏读缓存、后台静默同步"核心命题的验证 |
| `[9/9]` | **教务系统直登**（可选，需设 `GDUT_VERIFY_JXFW_DIRECT=1`）：jxfw `/new/login` + 图形验证码逃生通道。需人工看图输码 |

退出码：`0` = 全部阶段通过；`1` = 有阶段失败；`2` = 没读到凭据（**不发出任何网络请求**）。

> 脚本刻意"先做预检、再正式登录"：预检打印的是 `login()` 内部看到的关键中间值，
> 正式登录验证整合后的流程真的能跑通。两者用的是同一套公开类。

### Windows 控制台乱码

任务已强制子进程与 Gradle 用 UTF-8（`defaultCharacterEncoding = "UTF-8"` +
`-Dfile.encoding=UTF-8`）。若仍乱码，在支持 UTF-8 的终端里跑，或把输出重定向到文件再查看。

### 别用真实凭据在 CI 上跑

本工具是给人手工诊断用的。真实凭据不要进 CI、不要进任何脚本变量之外的地方。

---

## 3. 输出怎么读

正常全部通过时：

```text
[5/9] 调用 AuthServerClient.login() 完成真实登录
  登录成功。用户类型: UNDERGRADUATE（学号首位 3 判定）
  cookie（只列名与域，不列值）:
    JSESSIONID @ authserver.gdut.edu.cn (path=/authserver)
    JSESSIONID @ jxfw.gdut.edu.cn (path=/)
    route @ authserver.gdut.edu.cn (path=/authserver)

RESULT: PASS
```

- **两个域的 JSESSIONID 都在**才对。只有 jxfw、没有 authserver，会在会话续期时失败。
- `[7/9]` 的"课表接口命中"会告诉你 `xsAllKbList`（HTML）还是 `getDataList`（JSON）实际可用。
- 课表阶段打印的 warning 会告诉你丢弃了几行脏数据。

---

## 4. 安全约定

1. 密码只作为局部变量存在，**任何情况下都不打印**；头部只显示长度。
2. cookie **只打印名与域，绝不打印值**（`JSESSIONID` 的值等同于账号）。
3. 密文只打印长度与前 8 字符。密文里含 64 字节随机前缀，前 8 字符只是前缀的一部分，
   不泄露密码。
4. 不把任何凭据写进文件。
5. 工具额外做了一次自检：若 `session.diagnostics` 里意外出现密码，会打印告警并把该阶段判失败。

---

## 5. 失败模式 → 原因 → 下一步（诊断决策表）

> 这是本文最有用的部分。多数失败都会表现为同一句"账号或密码错误"，
> 所以**必须先看本工具打印的阶段与底层异常/HTTP 状态**，再决定查什么。

### 5.1 凭据与参数

| 现象 | 最可能的原因 | 下一步 |
|---|---|---|
| 打印"缺少凭据"，退出码 2 | 没设环境变量也没建 `secrets.properties` | 按 [第 1 节](#1-配置凭据) 配置 |
| 学号长度不是 10 / 首位不是 3 | 输错，或确实是研究生/教师账号 | 研究生/教师当前不支持，见 [协议 §7](../reference/spec/gdut-protocol.md) |
| 密码里有 `#` / `=` / 反斜杠，`secrets.properties` 读出来不对 | Properties 规则 | 改用环境变量 |

### 5.2 网络与 TLS

| 现象 | 最可能的原因 | 下一步 |
|---|---|---|
| `Network`，`SSLHandshakeException: PKIX path building failed` | 本机没有可用的根证书链（公司代理、抓包工具、精简 JDK） | 换正常网络；若在抓包，把代理 CA 导入 JDK truststore。**不要**为了绕过而关证书校验 |
| `Network: 网络连接失败` | DNS / 超时 / 校园网限制 / 不在校园网 | 确认能 `curl https://authserver.gdut.edu.cn/authserver/login` |
| `[1/9]` 成功但 `[4/9]` 网络异常 | `checkNeedCaptcha.htl` 被单独拦截 | 见上；该检查失败**不阻断**正式登录（`known=false`） |

### 5.3 登录页解析（`[2/7]` / `Parse`）

| 现象 | 最可能的原因 | 下一步 |
|---|---|---|
| `Parse: 统一认证登录页`，detail 里"一个 `<form>` 都没有" | 被导到维护页 / 移动版 / WAF 拦截页 | 看 detail 里的 title 和 HTML 片段。若 title 是"系统维护中"，等学校恢复 |
| `Parse`，detail 里"有 N 个表单但没有 #pwdFromId" | 学校改版 | 用 fixture 对比 `authserver_login_page*.real.html`；改 `AuthLoginPageParser` |
| salt 为空 / `hasUsableSalt=false` | 登录页结构变了 | 抓新登录页存成 `.real` fixture，重新逆向 |
| `service` 解析为 `<无>` | 入口没走 SSO，或 `extractInlineVar` 没处理新的右值形态 | 看 `[1/9]` 跳转链是否带 `service=`；对比实测的三种形态 |
| `[5/9]` 登录后拿不到 jxfw 会话，但看起来"成功" | POST 漏了 `?service=` | 看诊断摘要里的"提交地址"是否带 `service`；见 [协议 §1.5](../reference/spec/gdut-protocol.md) |

### 5.4 凭据 / 风控 / 身份

| 现象 | 最可能的原因 | 下一步 |
|---|---|---|
| `BadCredentials`，`userMessage` 是服务端原文 | 密码错，或失败次数过多被锁 | 先看 `badCredentialsCount`。用浏览器确认密码 |
| `CaptchaRequired` | 账号触发了**滑块**风控 | 这是设计内的逃生通道：App 会自动切"教务系统登录"；或先在浏览器正常登录一次清掉计数。**不要反复重试** |
| `[4/9]` 显示"需要验证码: true" | 同上 | 同上 |
| `UnsupportedUserType: 研究生/教师` | 账号类型 | 当前只支持本科 |
| `UnexpectedLoginResult`，detail 无 `serverMessage` | 回到登录页但没有明确错误文案：滑块拦截 / 会话过期 / 风控静默拒绝 | **不要**武断改密码。检查 `captchaSwitch`、`badCredentialsCount`，走直登通道 |
| `SessionExpired`（登录阶段） | 跳转链看着成功但 jxfw 会话没建起来 | 看诊断摘要的跳转链；多为 ticket 未被接受，重试或走直登 |

### 5.5 直登（`LoginMethod.JXFW_DIRECT`）

| 现象 | 最可能的原因 | 下一步 |
|---|---|---|
| `BadCaptcha: 验证码不正确` | 验证码填错，或**没把取图时的 JSESSIONID 回传** | 确认重新取一张再填；实现上 `captchaToken` 必须原样回传 |
| `BadCredentials`，但密码确实正确 | **`pwd` 可能需要加密**（本项目按旧后端发明文，标注为未验证） | 浏览器抓一次真实直登请求看 `pwd` 形态；若加密，很可能复用 `AuthServerCrypto.encryptPassword`（开放项见 `temp/agent-task-list-open.md`） |
| `Http 405` | 用了 GET 调 `/new/login` | 它只接受 POST |

### 5.6 jxfw 业务接口

| 现象 | 最可能的原因 | 下一步 |
|---|---|---|
| `[6/9]` / `[7/9]` `SessionExpired` | cookie 失效 / 域不对 | 看 `[5/9]` 的 cookie 列表；确认 jxfw 域有 JSESSIONID |
| `Parse: 课表（xsAllKbList）找不到 var kbxx` | 接口返回的不是 HTML（可能已下线或返回空） | AUTO 会自动回退 `getDataList`；看"课表接口命中"打印。若两个都失败，detail 会同时附两段原因 |
| 课表条数明显偏少 | `getDataList` 分页没取全 | 看 warning（"拉条数不足"/"翻页达到上限"）。旧后端固定 300 不翻页是已知 bug |
| 考试 `Parse` | 接口结构变了 | 抓真实响应对照 `JxfwExamParser` |
| 成绩条数对不上 | 劳动教育 bug 兜底未生效 | 看是否有"劳动教育"行；`needsLaborEducationPatch` 的兜底由 `JxfwClient.fetchGrades` 执行 |
| `Http 500/503` | 教务系统繁忙（常在选课/出分高峰） | 稍后再试；`GdutException.Http` 对 5xx 标为 `recoverable` |

### 5.7 构建 / 运行工具本身

| 现象 | 最可能的原因 | 下一步 |
|---|---|---|
| Gradle 报 `Unsupported class file major version 69` 或只显示一句 `25.0.3` | 用了系统默认 JDK 25 | 设置 `JAVA_HOME` 指向 JDK 21（见 [README](../README.md)） |
| `verifyLogin` 找不到主类 | 加了 `:app:test` 到命令 | 只跑 `:data-gdut:verifyLogin` |
| 输出中文乱码 | 终端编码 | 任务已强制 UTF-8；换终端或重定向到文件 |
| 任务以非 0 退出、Gradle 报 BUILD FAILED | 有阶段失败（这是有意的） | 看 `RESULT: FAIL` 上方的汇总；这是验证工具的正常语义 |

---

## 6. 用它做什么

除了"确认我的账号能不能登录"，它在以下场景最有价值：

1. **学校改接口后的第一诊断**：定位断在哪一步，见 [测试策略 · 学校改接口时的回归流程](./testing-strategy.md)。
2. **替换合成 fixture**：把 `[7/9]` 看到的真实字段 dump 出来，替换
   `data-gdut/src/test/resources/fixtures/` 里手工合成的 jxfw fixture（开放项见 `temp/agent-task-list-open.md`）。
3. **确认 `xsAllKbList` 是否仍存活**（开放项见 `temp/agent-task-list-open.md`）。
4. **确认直登 `pwd` 是否需要加密**（开放项见 `temp/agent-task-list-open.md`）。
