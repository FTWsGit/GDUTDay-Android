# 01 · 广工协议逆向报告

> 本文是 `data-gdut` 模块全部 KDoc 的汇总与展开，是最重要的一份文档。
> 代码实现以源码为准；本文解释**为什么**这么实现，并明确标注每条结论的验证状态。

**验证状态的三种标记**，全文统一使用：

- **实测 2026-09-10** —— 在 `authserver.gdut.edu.cn` / `jxfw.gdut.edu.cn` 上真实抓包或请求得到的结论。
  相关原始文件见 [`data-gdut/src/test/resources/fixtures/`](../data-gdut/src/test/resources/fixtures/)（`.real` 后缀）。
- **推断自旧代码（未联网验证）** —— 来自 `gdutday-wechat` / `gdutday-wechat3.0-java` /
  `GDUTAuth` / `GDUT.ClassSchedule` 的实现，可信但本轮未复验。
- **完全未验证** —— 无任何来源，需要真实账号或真实抓包才能确认。

相关文档：[架构](./00-architecture.md) · [登录验证工具](./07-verify-login.md) ·
[测试策略](./06-testing-strategy.md)

---

## 0. 域名与角色

| 主机 | 系统 | 本项目中的角色 |
|---|---|---|
| `authserver.gdut.edu.cn` | 统一身份认证（CAS 改造版） | 登录入口，签发 ticket 与各子系统的会话 |
| `jxfw.gdut.edu.cn` | 本科教务系统 | 课表 / 考试 / 成绩 / 学期列表 |
| `yjsxt.gdut.edu.cn` | 研究生系统（ehall） | **不实现**，仅记录 |

所有硬编码 URL 集中在 [`GdutEndpoints.kt`](../data-gdut/src/main/kotlin/com/gdutday/data/gdut/GdutEndpoints.kt)。
运行时代码不引用它，而是引用 [`GdutHosts.kt`](../data-gdut/src/main/kotlin/com/gdutday/data/gdut/GdutHosts.kt) ——
后者默认值取自 `GdutEndpoints`，测试时指向 MockWebServer。

> ⚠ **文档与代码不一致 1**：`GdutHosts` 的 KDoc 说"两者的默认值由 `GdutHostsTest` 交叉校验"，
> 但仓库里**没有** `GdutHostsTest`。这条交叉校验目前并不存在。

---

## 1. 统一认证登录

### 1.1 浏览器视角的完整流程

入口不是直接访问 `authserver`，而是先访问**教务系统的 SSO 入口**，让它 302 带上 `service`：

```text
① GET  https://jxfw.gdut.edu.cn/new/ssoLogin
        → 302 https://authserver.gdut.edu.cn/authserver/login?service=https%3A%2F%2Fjxfw.gdut.edu.cn%2Fnew%2FssoLogin
        → 200 登录页 HTML；Set-Cookie: JSESSIONID(authserver, Path=/authserver, HttpOnly)、route
② 解析 #pwdFromId 的隐藏域、#pwdEncryptSalt、内联变量 service / captchaSwitch
③ GET  /authserver/checkNeedCaptcha.htl?username=<学号>&_=<毫秒时间戳>
        → {"isNeed":false}     （true 则抛 CaptchaRequired，绝不自动重试）
④ password = AuthServerCrypto.encryptPassword(明文, salt)
⑤ POST /authserver/login?service=<urlencoded service>
        body = 隐藏域原样 + username + password(密文) + captcha="" + rememberMe=true
        ⚠ 不含 passwordText（浏览器在提交前把它 disabled 了）
        ⚠ 含一个 name 为空的字段，值是 salt
⑥ 手动跟随 302：authserver → jxfw/new/ssoLogin?ticket=ST-xxx → jxfw 首页
⑦ 判成败：落到 jxfw 主机 = 成功；200 且返回登录页 = 失败（从 #showErrorTip 取文案）
⑧ GET  https://jxfw.gdut.edu.cn/  → 必须 200；若 302 回 authserver 说明会话没建起来
⑨ GET  /personalInfo/common/getUserConf → 正则抠学号 → 首位数字判定身份
```

实现位于 [`AuthServerClient.login()`](../data-gdut/src/main/kotlin/com/gdutday/data/gdut/auth/AuthServerClient.kt)。

`AuthServerConfig.entryUrl` 默认为 null → 走上面这条"浏览器路径"。旧 Java 后端走的是
**两段式**：直接 `GET /authserver/login?type=userNameLogin`（不带 service），认证成功后再用
空 body POST 一次带 service 的地址触发 SSO。两条路都能用，本项目选浏览器路径：
只需一次认证、跳转链更短、更接近真实用户行为。

### 1.2 登录页的真实结构（实测 2026-09-10）

```html
<form class="loginFromClass" method="post" id="pwdFromId" action="/authserver/login">
  <input type="text"     id="username"       name="username"     value="">
  <input type="password" id="password"       name="passwordText" value="">   ← 提交前被 JS disabled
  <input type="hidden"   id="saltPassword"   name="password"     value="">   ← 密文写这里
  <input type="text"     id="captcha"        name="captcha"      value="">
  <input type="checkbox" id="rememberMe"     name="rememberMe"   value="true">
  <input type="hidden"   id="_eventId"       name="_eventId"     value="submit">
  <input type="hidden"   id="cllt"           name="cllt"         value="userNameLogin">
  <input type="hidden"   id="dllt"           name="dllt"         value="generalLogin">
  <input type="hidden"   id="lt"             name="lt"           value="">
  <input type="hidden"   id="pwdEncryptSalt"                     value="xaOfScaw6epvgypH">
                                                   ↑ 没有 name 属性！
  <input type="hidden"   id="execution"      name="execution"    value="dd938fd8-...">
</form>
```

### 1.3 登录表单字段表（POST body）

| 字段 | 值来源 | 必填 | 备注 |
|---|---|---|---|
| `username` | 用户输入学号 | 是 | |
| `password` | **AES 密文** | 是 | 覆盖页面里 `name=password` 的隐藏域（原值为空） |
| `passwordText` | —— | **绝不发送** | 浏览器提交前 `$(LOGIN_PASSWORD_ID).attr("disabled")`，disabled 控件不参与序列化 |
| `captcha` | 空串 | 是 | 字段必须**存在且为空**；缺失会被拒 |
| `rememberMe` | `true` | 本项目默认 | checkbox，初始 `value="true"` |
| `_eventId` | `submit` | 是 | 隐藏域原样回传 |
| `cllt` | `userNameLogin` | 是 | 决定登录类型 |
| `dllt` | `generalLogin` | 是 | |
| `lt` | 空串（可能来自隐藏域） | 存在即可 | |
| `execution` | 登录页隐藏域原样 | 是 | CAS flow execution key，见 1.5 |
| `""`（空 name） | `pwdEncryptSalt` 的 value | 是 | 浏览器真实行为；见 1.4 |

> **为什么 `captcha` 必须"存在且为空"**：`FormFields.add(name, value)` 对 null 当空串处理，
> 正是为了复刻教务系统大量使用的"字段存在但值为空"写法。漏掉字段会被服务端拒绝。

### 1.4 `pwdEncryptSalt` 没有 `name` 属性

这是最容易踩的细节。浏览器提交时它会变成请求体里一个 `=<salt>` 的空名键值对。
本项目不需要特判：jsoup 的 `attr("name")` 对缺失属性返回**空字符串**（不是 null），
于是它自然成为空名字段。

- 旧 Java 后端为此专门写了 `tempMap.put("", pwdEncryptSalt)`；
- F# 版写了 `| null, v -> ("", v.Value)`；
- 两者殊途同归，本实现直接取 `attr("name")` 即可。

`FormFields`（自己实现的表单编码器）存在的**唯一直接原因**就是这个空名字段：
OkHttp 的 `FormBody.Builder` 理论上也接受空 name，但那是未文档化行为，
一旦某个版本加了 `require(name.isNotEmpty())` 就会静默破坏登录。

### 1.5 `service` 与 `execution` 的会话绑定性质

**`execution`** 的值形如
`dd938fd8-d88d-405a-bf86-2fe07ca76605_ZXlKaGJHY2lPaUpJVXpVeE1...`（UUID + Base64 的 JWT 样式串），
是 CAS 的 flow execution key，**与 JSESSIONID 绑定且一次性**。

因此登录必须是"取页面 → 立刻提交"，中间不能换 cookie jar，也不能复用旧的 `execution`。
这就是为什么 `AuthServerClient.login()` **每次都新建一个 `SessionCookieJar`**。

**`service`** 决定 CAS 给哪个系统签票据。`login.js` 在 `DOMContentLoaded` 时会改写表单 action：

```js
if (service && service != "") {
    utils.setUrlParam("pwdFromId", "?service", encodeURIComponent(service));
}
```

所以浏览器实际 POST 到 `/authserver/login?service=<urlencoded>`。
**漏掉这个 query 会导致 CAS 不知道该签发哪个系统的 ticket** ——
登录看似成功却拿不到 jxfw 会话。这是最容易踩且最难排查的坑。

### 1.6 内联变量的三种形态（实测 2026-09-10）

抓取线上真实页面后确认：

| 变量 | 不带 service 时 | 带 service 时 |
|---|---|---|
| `service` | `var service = null;` | `var service = ["https:\/\/jxfw.gdut.edu.cn\/new\/ssoLogin"];` |
| `captchaSwitch` | `var captchaSwitch = "2";` | 同 |
| `_badCredentialsCount` | `var _badCredentialsCount="5";`（等号两边**无空格**） | 同 |
| `needCaptcha` | `var needCaptcha = "";` | 同 |

⚠ **`service` 的右值是一个 JSON 数组，不是普通字符串字面量**，而且斜杠被转义成 `\/`。
一个很自然会写的正则 `var\s+service\s*=\s*"([^"]*)"` 在这种情况下**匹配不到任何东西**，
于是 `submitUrl()` 不会带 `?service=`，CAS 不签票据 —— 登录流程看着走完了，却拿不到 jxfw 会话。

`AuthLoginPageParser.extractInlineVar` 因此实现了带括号深度跟踪的 JS 右值扫描器，
三种形态（字符串 / 数组 / `null`）都要处理，并有单元测试盯着。
右值扫描还必须处理**字符串里的分号**和 **JS 自动分号插入（顶层换行也算语句结束）**。

### 1.7 登录失败的判定

CAS 在密码错误时**不返回 4xx**，而是 **200 + 重新渲染的登录页**，错误文案写在：

```html
<div id="formErrorTip" class="form-errorTip lang_text_ellipsis">
    <span id="showErrorTip" class="form-error"></span>
    <span id="showWarnTip"  class="form-warn"></span>
</div>
```

判定逻辑（`evaluateLoginResponse`）：

1. 落到了 `expectedHost`（jxfw）→ **成功**；
2. 返回 200 且响应体还是登录页 → 从 `#showErrorTip` 取文案：
   - 有条文 → `GdutException.BadCredentials(serverMessage = 文案)`；
   - **没有条文 → `UnexpectedLoginResult`，而不是 `BadCredentials`**。
     因为这可能是滑块/风控静默拦截或会话过期，误报"密码错误"会让用户去改一个本来正确的密码，
     甚至反复重试把账号锁掉。宁可报一个更含糊但诚实的错误。
3. 其它 → `UnexpectedLoginResult`，detail 里附 `finalUrl` / 状态码 / 跳转链 / 响应片段。

错误页还会带出 `_badCredentialsCount`，即服务端下发的"本会话还剩几次试错机会"。
`login.js` 的 `credentialsCount()` 判断它是否 `== 0`，为 0 时页面一加载就强制显示验证码。

### 1.8 重定向跟随

手写 [`RedirectFollower`](../data-gdut/src/main/kotlin/com/gdutday/data/gdut/http/RedirectFollower.kt)，
**不用** OkHttp 内置的 `followRedirects(true)`，因为登录判定完全依赖观察每一跳。

行为：
- 记录完整跳转链 `chain`，诊断信息里直接展示；
- 每跳关闭上一个响应体（不关会泄漏连接）；
- 301/302/303 后续请求降级为 GET 并丢弃 body（RFC 7231 / 浏览器行为）；307/308 保留方法与 body；
- **URL 重复即认定循环并停止**，重新发一次请求拿到可用响应，`loopDetected = true`；
- 超出 `maxHops`（默认 10）**明确抛 `TooManyRedirects`** 并附完整链路。

旧 Java 后端 `LoginServiceImpl.debugRedirect()` 的注释写着"最后会无限重定向，因为 nginx 一直永远返回 302"，
它的处置是硬编码 `if (maxTime > 3) break` —— 静默跳出、不报错，
于是登录失败和登录成功走到了同一条后续代码路径。本实现刻意反其道而行。

跨主机的跳转不携带上一跳的自定义头，避免把 authserver 的头泄给 jxfw；
每跳按浏览器规则重算 `sec-fetch-site`（同源 / 同站 / 跨站）。

### 1.9 会话有效性二次校验

即使跳转链看着成功，也可能"ticket 被接受了但 jxfw 会话其实没建起来"。所以
登录后额外 `GET https://jxfw.gdut.edu.cn/`：

- 200 → 会话有效；
- 302 且 Location 指向 authserver → `SessionExpired`；
- 401/403 → `SessionExpired`。

多一个请求，但能把问题挡在登录阶段，而不是等用户刷课表时才发现。
`AuthServerConfig.verifyAfterLogin` 可关闭（默认 true）。

### 1.10 身份判定

登录后 `GET /personalInfo/common/getUserConf`，返回 **HTML**，学号藏在
`<option value='3120xxxxxx' selected>` 里。**学号首位数字**决定身份：

- `3` → 本科
- `2` → 研究生
- `0` → 教师

本实现的正则比旧后端的 `<option value='(\d+)' selected>` 宽松：
引号可选、属性间允许任意空白、要求至少 6 位数字（避免把院系代码误认成学号）。
拿不到时回退到用户输入的学号。

---

## 2. AES 密码加密

### 2.1 地面真相：线上 `encrypt.js` 原文

抓自
`https://authserver.gdut.edu.cn/authserver/gdutThemes/static/common/encrypt.js`
（fixture：`authserver_encrypt.js.real`），末尾：

```js
function getAesString(n, f, c) {                        // n=明文 f=key c=iv
    f = f.replace(/(^\s+)|(\s+$)/g, "");                // ← key 会 trim
    f = CryptoJS.enc.Utf8.parse(f);
    c = CryptoJS.enc.Utf8.parse(c);                     // ← iv 不 trim
    return CryptoJS.AES.encrypt(n, f, {
        iv: c, mode: CryptoJS.mode.CBC, padding: CryptoJS.pad.Pkcs7
    }).toString();                                      // ← Base64(裸密文)
}
function encryptAES(n, f) {
    return f ? getAesString(randomString(64) + n, f, randomString(16)) : n;
}                                                       //  ↑ 64 字节随机前缀  ↑ 16 字节随机 IV
function encryptPassword(n, f) { try { return encryptAES(n, f) } catch (c) {} return n }

var $aes_chars = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678";   // 48 个字符
function randomString(n) {
    var f = "";
    for (i = 0; i < n; i++) f += $aes_chars.charAt(Math.floor(Math.random() * 48));
    return f;
}
```

### 2.2 算法参数

| 参数 | 值 | 说明 |
|---|---|---|
| 算法 | AES | |
| 模式 | CBC | |
| 填充 | Pkcs7 | CryptoJS 的 Pkcs7 等价于 JCE 的 `AES/CBC/PKCS5Padding` |
| Key | 登录页 `#pwdEncryptSalt` 的 value（16 字符），**trim 后** UTF-8 | salt 长度必须是 16/24/32 字节 |
| IV | `randomString(16)`，每次随机，**不 trim** | |
| 明文 | `randomString(64) + 用户密码` | 64 字节随机前缀；是 16 的整数倍，正好 4 个块 |
| 输出 | Base64（裸密文，无 OpenSSL `Salted__` 头） | CryptoJS 传 WordArray key 时不加 Salted 头 |

随机字符表 **48 个字符**，刻意排除易混淆字符：大写缺 `I L O U V`，小写缺 `g l o q u v`，
数字缺 `0 1 9`。这个字符串必须与线上完全一致，否则前缀/IV 的字符分布会成为不必要的指纹差异。
`AuthServerCrypto` 的 `init` 块把 `AES_CHARS.length == 48` 写死校验，误改会在测试阶段炸。

`encryptPassword` 里还有一处**与浏览器的刻意分歧**：JS 版有
`try { ... } catch (c) {} return n`，即加密失败时**把明文密码发出去**。这显然是无意的，
我们不复刻 —— 加密失败一律抛 `GdutException.Parse`，绝不发送明文。
但 `encryptAES` 的 `f ? ... : n`（salt 为空时返回明文）是**有意的设计**（对应服务端未启用密码加密的部署形态），保留。

### 2.3 ⚠ 旧实现的认知错误：把随机值当常量

旧 Java 后端 `LiUtils.cbcEncrypt` 和 F# 的 `GDUT.Auth.EhallCrypto.encrypt` 都把
**64 字节前缀和 16 字节 IV 硬编码成了常量**：

```java
String iv = "Jisniwqjwqjwqjww";   // 16 字节，硬编码
String s = "J69IVxcXqvqNhvk1J69IVxcXqvqNhvk1J69IVxcXqvqNhvk1J69IVxcXqvqNhvk1" + plaintext;  // 16×4 = 64 字节前缀
```

这两个字符串是原作者**某一次抓包看到的随机值**，被误当成了协议常量。

### 2.4 为什么错了还能用

**CBC 解密时只有第 1 个明文块依赖 IV，从第 2 块起只依赖密文块本身。**

服务端解密后丢掉前 64 字节（正好 4 个块）的垃圾前缀，剩下的真实密码与 IV 取值无关。
所以硬编码任何 IV 都能碰巧成功 —— 但这在密文上留下了**固定指纹**
（同一个密码每次登录产生的密文完全一样），对风控不友好，也是明显的实现缺陷。

本项目照浏览器原样每次生成随机前缀与随机 IV。
`AuthServerCrypto.decryptStripPrefix(cipher, key, iv)` 允许传任意 IV 得到相同的去前缀结果，
单元测试用它证明"随机 IV 不影响可解性"。

### 2.5 黄金值测试

`encryptDeterministic` 用**指定的**前缀与 IV 加密（确定性、不取随机数），存在两个理由：

1. **黄金值测试**：用 OpenSSL 算出
   `AES-128-CBC(key="xaOfScaw6epvgypH", iv="Jisniwqjwqjwqjww", data=LEGACY_PREFIX + "mypassword123")`
   的 Base64，断言本方法输出一模一样 —— 证明 Kotlin 实现与浏览器 CryptoJS 等价。
2. **复现旧后端行为**：传旧后端的硬编码参数，就能得到与旧后端完全相同的输出，便于对比。

线上流程**不要**用这个方法，用 `encryptPassword`。

### 2.6 表单编码：Base64 的 `+ / =` 必须百分号编码

AES 密文是 Base64，必然含 `+` `/` `=`。`application/x-www-form-urlencoded` 里
`+` 表示空格，不编码就会把密文里的 `+` 变成空格传给服务端，导致解密失败、报"密码错误"。

`FormFields.encodeComponent` 按 HTML 规范编码：保留 `A-Z a-z 0-9 - _ . *`，
空格编码为 `+`，其余按 UTF-8 逐字节 `%XX`。

---

## 3. 滑块验证码机制

### 3.1 `captchaSwitch` 的语义

`captchaSwitch` 是**验证码形态**而不是开关：

- `"1"` → 图形验证码，`reloadCaptcha()` 把 `#captchaImg` 指向 `/authserver/getCaptcha.htl`；
- `"2"` → 滑块验证码，从 `/authserver/common/toSliderCaptcha.htl` 拉一段 HTML 片段塞进页面。

**实测（2026-09-10）`captchaSwitch == "2"`，即当前是滑块模式。**

`login.js` 的提交逻辑：

```js
if (checkForm()) {
    var cllt = $("#cllt").val();
    if (needCaptcha && captchaSwitch == "2" && cllt == "userNameLogin") {
        createSliderCaptcha();          // ← 滑块
    } else {
        $(".loginFromClass").submit();  // ← 直接提交
    }
}
function createSliderCaptcha() {
    $.ajax({ url: contextPath + "/common/toSliderCaptcha.htl", type: "get",
             success: function (html) { $("#sliderCaptchaDiv").html(html) } });
}
```

`needCaptcha` 初值是空串（假值），由 `checkNeedCaptcha.htl` 的返回决定。

### 3.2 `checkNeedCaptcha.htl`

```text
GET /authserver/checkNeedCaptcha.htl?username=<学号>&_=<毫秒时间戳>
→ {"isNeed":false}（正常账号实测）
```

⚠ **Content-Type 是 `text/plain;charset=UTF-8`，不是 `application/json`**，
所以不能用"响应类型必须是 JSON"的严格解析器。实现直接读 body 字符串再解析。

查询本身失败（网络/非 200/解析不出）时返回 `CaptchaCheck.unknown`，
`required = false` 但 `known = false` —— **"问不出来"不等于"不需要验证"**，
但也不应该因此拦住登录，所以选择不阻断，让提交阶段自己暴露问题。

`_badCredentialsCount` 归零、连续密码错误、异地/机房 IP、风控策略调整都会让 `isNeed` 变 true。

### 3.3 为什么不做自动化

滑块是**第三方行为验证**，自动化既不可行也不应该做。App 侧的正确处置：

1. **绝不自动重试** —— 重试只会让风控计数继续涨；
2. 提示用户改用**教务系统直登**（`LoginMethod.JXFW_DIRECT`），
   那条路用的是 `jxfw.gdut.edu.cn/yzm` 的 140×60 JPEG 图形验证码，用户看一眼就能填；
3. 或让用户先在浏览器里正常登录一次教务系统，把风控计数清掉，再回 App 重试。

`LoginViewModel.submit` 捕获 `CaptchaRequired` 后**自动把方法切到教务系统直登并取验证码**，
不显示"重试"按钮。UI 侧对应 `LoginErrorKind.CAPTCHA_REQUIRED`。

### 3.4 逃生通道：教务系统直登

[`JxfwDirectLogin`](../data-gdut/src/main/kotlin/com/gdutday/data/gdut/jxfw/JxfwClient.kt)，即
旧小程序 `login-edu.vue`（页面上写着"20级点这里:使用教务系统登录"）走的路径：

```text
① GET  https://jxfw.gdut.edu.cn/yzm?d=<毫秒时间戳>
        → 200, Content-Type: image/jpeg;charset=UTF-8, JPEG 140×60
          Set-Cookie: JSESSIONID=...; Path=/; Secure; HttpOnly
        （?d= 是防缓存，每次必须不同）
② 用户看图填验证码
③ POST https://jxfw.gdut.edu.cn/new/login
        Cookie: JSESSIONID=<①拿到的>
        Content-Type: application/x-www-form-urlencoded
        body: account=<学号>&pwd=<密码>&verifycode=<验证码>
        → HTTP 200, Content-Type: text/html;charset=utf-8   ⚠ 但 body 是 JSON
          成功 {"code":0,...}
          失败 {"code":-1,"data":null,"message":"验证码不正确"}
```

三步全部实测过（②除外，那需要真人）：

- `GET /new/login` → **405 Method Not Allowed**，说明它是纯 API，没有配套 HTML 登录页；
- 用错误验证码 POST → `{"code":-1,"data":null,"message":"验证码不正确"}`；
- 验证码图片与 JSESSIONID 绑定：**必须把 ① 的 JSESSIONID 带到 ③**，否则永远提示"验证码不正确"。

⚠ **未验证：`pwd` 是否需要加密**。旧 Java 后端 `LoginServiceImpl.jxfwLogin` 直接发明文，本项目沿用。
如果验证脚本报"账号或密码错误"但密码确实正确，**第一个该怀疑的就是这里** ——
需要在浏览器里抓一次真实直登请求，看 `pwd` 字段的形态。若发现它被加密，
加密逻辑很可能复用 authserver 那套（`encrypt.js` 是全站共用的），可以直接调
`AuthServerCrypto.encryptPassword` 试试。

---

## 4. 本科教务系统（jxfw）接口全集

所有 `!getDataList.action` 接口都返回 EasyUI DataGrid 格式 `{ "total": N, "rows": [...] }`，
且 **POST `application/x-www-form-urlencoded`**。

### 4.1 接口一览

| 接口 | 方法 | 路径 | 关键参数 | Referer | 状态 |
|---|---|---|---|---|---|
| 会话探针 | GET | `/` | —— | `/` | 实测：未登录 302 到 authserver |
| 学期列表 | GET | `/xsksap!ksapList.action` | —— | `/` | 推断（旧后端在用） |
| 课表 A | GET | `/xsgrkbcx!xsAllKbList.action` | `xnxqdm` | **`/xsgrkbcx!getXsgrbkList.action`** | 推断 2022 F# |
| 课表 B | POST | `/xsgrkbcx!getDataList.action` | `xnxqdm` `zc=""` `page` `rows` `sort=kxh` `order=asc` | `/` | 推断 2024 Java |
| 考试 | POST | `/xsksap!getDataList.action` | `xnxqdm` `page=1` `rows=200` `sort=zc,xq,jcdm2` `order=asc` | `/` | 推断 |
| 成绩 | POST | `/xskccjxx!getDataList.action` | `xnxqdm`(空=全部) `jhlxdm=""` `sort=xnxqdm` `order=asc` `page` `rows` | `/` | 推断 |

> ⚠ **两个 Referer 不是同一个**。课表 A 必须带
> `Referer: https://jxfw.gdut.edu.cn/xsgrkbcx!getXsgrbkList.action`，
> F# 源码 `GDUT.ClassSchedule/Library.fs` 里专门留了 `// TODO: 重要！需要记录`。
> 其它接口用首页 `/` 即可。

`JxfwClient.execute` 的错误归一顺序很重要：

1. **先看是不是被 3xx 导回登录页**（最明确的会话失效信号）；
2. 再看状态码（401/403 → SessionExpired；其它非 200 → Http）；
3. 最后看响应体：200 + HTML 也可能是会话失效（有些接口不 302 而是直接渲染登录页）。

### 4.2 学期编码的两种形式

| 形式 | 例子 | 用在哪 |
|---|---|---|
| **短码** `YYYY + N` | `20251` | 页面 `<option>` 的显示、旧后端 DTO、本项目内部存储 |
| **长码** `YYYY + NN`（= `xnxqdm`） | `202501` | jxfw **所有**接口的 `xnxqdm` 请求参数 |

旧 Java 后端在两者之间来回换算，用的是 `(t / 10) * 100 + t % 10`（短→长）和
`s.substring(0, s.length - 2) + s.last()`（长→短），可读性极差且无法处理学期号 ≥ 10 的情况。

本项目改为**显式存 `(year, semester)` 两个字段**，两种编码都由计算属性派生：
`Term.shortCode` / `Term.xnxqdm`。从此不存在"我手上这个字符串到底是哪种码"的问题。
`Term.parse` 同时接受 5 位与 6 位字符串。

> 历史 bug 的核心教训：把两种编码都当字符串传来传去，一定会在某个接口漏掉换算。
> 类型化之后这类 bug 在编译期就消失了。

### 4.3 两个课表接口的对比与自动回退

| 接口 | 粒度 | 优点 | 风险 | 源码依据 |
|---|---|---|---|---|
| `xsAllKbList` | 每教学班一行，`zcs="1,2,…,16"` | 数据量小（约 10 行），无分页风险，天然给出周次集合 | 2022 年逆向所得，**本次未联网验证是否仍存活**；返回 **HTML**，需抠 `var kbxx = [...]` | F# `GDUT.ClassSchedule` |
| `getDataList` | 每周每教学班一行 | 2024 年仍在用，返回标准 JSON，带 `pkrq` 具体上课日期（可反推开学日期、检测调课） | 数据量大，需分页；周次要自己聚合 | Java 后端 |

`ScheduleEndpoint.AUTO`（默认）策略：

1. 先试 `xsAllKbList`（数据量小得多）；
2. 抛异常或返回空 → 回退 `getDataList`（分页取满）；
3. 两个都失败 → 抛 `Parse`，detail 里**同时附上两次的失败原因**。

⚠ **会话失效不会被回退逻辑吞掉**：换接口也一样会失效，直接抛出 `SessionExpired` 让上层重登。

用户可在设置里强制指定其中一个（`ScheduleFetchStrategy`），便于排查；
`LOCAL_ONLY` 则完全不联网、只读本地数据库。

#### xsAllKbList 字段表（推断 2022 F#）

| 字段 | 含义 | 备注 |
|---|---|---|
| `kcmc` | 课程名称 | |
| `kcbh` | 课程编号 | |
| `jxbmc` | 教学班名称 | **可能逗号分隔多值** |
| `kcrwdm` | 课程任务代码 | 优先用它当 `courseCode` |
| `jcdm2` | 节次 | 逗号分隔整数，如 `"1,2"` |
| `zcs` | 周次 | 逗号分隔整数 |
| `xq` | 星期 | 整数 1..7 |
| `jxcdmcs` | 教学场地 | **可能逗号分隔多值** |
| `teaxms` | 授课教师 | **可能逗号分隔多值** |

响应形如：
```html
<script>var kbxx = [ {"kcmc":"高等数学","kcbh":"…","jxbmc":"…","kcrwdm":"…",
                       "jcdm2":"1,2","zcs":"1,2,3,…,16","xq":"1",
                       "jxcdmcs":"教5-301","teaxms":"张三"}, … ];</script>
```

#### getDataList 字段表（推断 2024 Java）

| 字段 | 含义 | 验证状态 |
|---|---|---|
| `kcmc` | 课程名称 | 旧 Java 在用 |
| `jxcdmc` | 教学场地名称 | 旧 Java 在用 |
| `teaxms` | 授课教师 | 旧 Java 在用 |
| `xq` | 星期 | 旧 Java 在用 |
| `zc` | 周次，**单个整数** | 旧 Java 直接当 map key |
| `jcdm` | 节次，**两位拼接** `"0102"` | 旧 Java 在用 |
| `sknrjj` | 授课内容简介 | 旧 Java 在用 |
| `kcbh` | 课程编号 | ⚠ 未验证该接口是否返回 |
| `jxbmc` | 教学班名称 | ⚠ 未验证 |
| `pkrq` | 上课日期 | ⚠ 未验证。**若存在**是反推开学日期的关键 |

> ⚠ **分页 bug**：旧 Java 后端固定 `rows=300` 且不翻页。一学期 20 周 × 每周 20 行 = 400 行，
> 课多的学生会被截断而**静默丢失后半学期的课**。本实现读 `total` 并循环翻页
> （`rowsPerPage=200`，`maxPages=20`），并在取不全时记 warning。

#### `jcdm` 的两位拼接格式

`"0102"` = 第 1、2 节；`"01020506"` = 第 1、2、5、6 节。
必须按**连续段切分**：`{1,2,5,6}` 在课表网格上是两个不相邻的色块（上午两节 + 下午两节），
不能画成一个从第 1 节拉到第 6 节的矩形 —— 那会盖住中午和下午前两节。

旧 Java 后端没做切分（直接把 `"0102"` 逐对转成 `"1,2"` 字符串塞给前端），
遇到非连堂课就会画出跨越午休的错误色块。切分逻辑在 `SectionRunSplitter`。

⚠ `"12"` 这种串在两种格式下含义完全不同（第 12 节 vs 第 1、2 节），
所以 `RawScheduleRow.sectionsPaired` 显式标注格式，**不靠 `parseAuto` 猜**。

#### 节次切分与聚合

`CourseNormalizer` 做两件事：

1. **按连续节次切分**（见上）；
2. **按自然键聚合**。`getDataList` 按周炸开，一门 16 周的课产生 16 行，除 `zc`/`pkrq` 外完全相同。
   聚合键 = `courseCode | teachingClass | courseName | dayOfWeek | startSection | sectionCount | classroom | teacher`。
   把 `classroom`/`teacher` 纳入键，是因为换教室的课（前 8 周在 A 楼、后 8 周在 B 楼）是两行，
   合并会丢信息。

**绝不抛异常**：一行脏数据不该毁掉整张课表。所有丢弃都记录在 `Outcome.dropReasons` 里供诊断。

#### `JsonExtractor` 为什么不用正则

旧 F# 库用的是 `(?<=var kbxx = )\[(.*?)\}];`。这个正则有三个问题：

1. 要求数组**必须以 `}]` 结尾**，最后一个元素若不是对象就匹配不到；
2. 懒惰匹配 `.*?` 遇到嵌套的 `}];` 会提前截断；
3. 要求 `var kbxx = ` 后的空格数、分号位置完全固定。

本实现改用**括号配对扫描**：从标记后第一个 `[`/`{` 开始按深度计数走到配对闭合符，
并正确跳过字符串字面量与转义字符。嵌套多深、结尾是什么类型都不受影响。

### 4.4 考试安排

| 字段 | 含义 |
|---|---|
| `kcmc` | 课程名称 |
| `kcbh` | 课程编号（关联 `Course`） |
| `ksrq` | 考试日期 |
| `kssj` | 考试时段，形如 `"08:30--10:05"`（**两个减号**） |
| `kscdmc` | 考试场地名称 |
| `xqmc` | **校区名称 ← 本科生数据里唯一的校区线索** |
| `kslbmc` | 考试类别名称 |
| `ksaplxmc` | 考试安排类型名称 |

`ksrq` 的格式未在本次实测中确认，旧后端直接透传字符串。本实现用
`CourseNormalizer.parseDateLenient` 兼容多种写法。

`kssj` 的分隔符是**两个减号**，不是普通 `-`，也不是 `~`。`Exam.parseTimeRange` 容错
`--`/`~`/`—`/`-`。

**旧实现的一个 bug**：旧 Java 后端用 `idMap.get(kcbh)` 把考试关联到"该课程在课表里的序号"，
但 `idMap` 是用**考试列表自己的下标**填的，跟课表毫无关系。本项目改用 `courseCode` 做关联键。

### 4.5 成绩与「劳动教育」bug

字段对照：

| 字段 | 含义 |
|---|---|
| `kcmc` | 课程名称 |
| `xnxqmc` | 学期中文名（如 `"2024-2025学年第一学期"`），**分组键** |
| `xnxqdm` | 学期代码（长码） |
| `zcj` | 总成绩（可能是 `87` / `"87"` / `"优秀"` / `""` / `"缺考"`） |
| `cjjd` | 成绩绩点 |
| `xf` | 学分 |
| `kcdlmc` | 课程大类名称 |
| `kcflmc` | 课程分类名称 |
| `xdfsmc` | 修读方式名称（必修/任选/限选…） |

#### ⚠ 劳动教育 bug（必须处理，否则绩点算错）

以 `xnxqdm=""`（查询全部学期）请求时，**「劳动教育」这门课的 `zcj` 与 `cjjd` 会返回空**，
但带上该行自己的 `xnxqdm` 再查一次就有值。

旧 Java 后端为此写过两轮修复，注释原文：

```text
//20240814 修复劳动教育成绩显示问题，问题原因 xnxqdm 为空时，劳动教育不显示成绩，是教务处的问题
//但我们还是抹平教务处的问题。。。
//20250630 修复劳动教育成绩错误问题，原因：
// 第一次修复时，没有设置当前学期的 xnxqdm，而且把所有当前学期的其他课的成绩都 set 到劳动教育的结果对象里了
```

第二次修复说明**第一版写错了**：忘了设 `xnxqdm`，导致遍历到了同学期其它课的成绩。

本项目的兜底方案：
- `JxfwGradeParser.parse` 只负责**标记**哪些行需要兜底（`Outcome.needsLaborEducationPatch`），
  保持纯函数、可离线测试；
- `JxfwClient.fetchGrades` 对每个受影响的学期，用 `Term.parse(termCode)` 得到的学期号
  **重查一次**，再 `mergeLaborEducationPatch` 合并；
- 合并时只覆盖「劳动教育」这一门课的 `scoreText`/`score`/`gpa`，
  学分与课程属性以主查询为准 —— 这正是第二次修复的要点。
- 只接受 `kcmc == "劳动教育"` 的那一行。

`Grade.countsTowardsGpa` 的规则：数值成绩 ≥ 60 且学分 > 0 才计入。
等级制成绩（优秀/良好/合格等）不参与绩点计算。

成绩按 `xnxqmc`（中文名）分组而不是 `xnxqdm`：实测部分行的 `xnxqdm` 会缺失
（这正是劳动教育 bug 的根源之一），但 `xnxqmc` 一直有值。旧后端也是按 `xnxqmc` 分组的。

### 4.6 校区探测

课表接口**不返回校区**，但作息表是按校区区分的。`xqmc` 是唯一能自动探测校区的地方。
`JxfwExamParser` 把解析过程中见到的校区名收集起来，取**出现次数最多的**作为 `campusHint`。

没有考试安排的学期（大一上）就探测不到，此时回退到用户在设置里手选，默认大学城。
`Campus.fromRawName` 做宽松关键字匹配（大学城 / 东风路 / 龙洞 / 番禺），匹配不到返回 `UNKNOWN`。

---

## 5. 图书馆入馆二维码

**完全本地生成，不需要任何网络请求。**

旧 Java 后端 `GdutDayServiceImpl.getLibQr` → `LiUtils.makeQRCode`：

```java
hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H);
hints.put(EncodeHintType.CHARACTER_SET, "utf-8");
hints.put(EncodeHintType.MARGIN, 1);
new MultiFormatWriter().encode(stuId, BarcodeFormat.QR_CODE, width, height, hints);
```

即：**QR 码编码的内容就是学号本身**，纠错等级 H，静默区 1 模块。
服务端只是把它渲染成 PNG 再转 Base64 传回来 —— 一次纯粹浪费流量的往返。

本项目在端上用 zxing 生成（`LibraryQr`），收益：

- **离线可用**：图书馆地下书库经常没信号；
- **秒开**：不用等网络往返，也不用等 Base64 解码；
- **省流量**：一张 PNG 几 KB，一天刷几次也是白花；
- **不依赖第三方服务器**：旧后端的 `api.cerbur.top` 是个人服务器，随时可能挂。

关于 ARGB 像素序：旧后端 `BufferedImage.TYPE_INT_RGB` + `setRGB(x, y, 0xFF000000 / 0xFFFFFFFF)`，
Android 的 `Bitmap.Config.ARGB_8888` 用的是同一个 32 位打包格式，输出可直接灌进
`Bitmap.createBitmap` + `setPixels`，无需通道转换。

> ⚠ ~~**文档与代码不一致 2**：`LibraryRepositoryImpl.qrModuleCount` 把模块数硬编码成 `21`~~
> **已修复**：改为 `LibraryQr.moduleCount(content)` 实算（用 `Encoder.encode` 只做 RS 编码不生成图像）。
> （理由写在 KDoc 里：本科生学号恒 10 位，H 级 version 1 = 21×21）。
> 这个理由对当前学号成立，但它是**硬编码**，一旦支持非本科生或二维码内容变化就会错。
> 已列入 [任务清单](./05-agent-task-list.md)。

---

## 6. 「已实测 / 未实测」清单

> 这是本文最有价值的部分。请严格按状态判断"这条结论能不能信"。

### 实测 2026-09-10

| 结论 | 证据 |
|---|---|
| `authserver` 登录页 `?type=userNameLogin` 返回 200 + HTML，下发 `JSESSIONID(Path=/authserver)` 与 `route` | `authserver_login_page.real.html` |
| 带 service 时 `var service = ["https:\/\/jxfw..."]`（JSON 数组、转义斜杠） | `authserver_login_page_with_service.real.html` |
| `captchaSwitch == "2"`（当前是滑块模式，不是图形验证码） | 真实登录页内联变量 |
| `_badCredentialsCount` 默认 `"5"`，且等号两边无空格 | 真实登录页 |
| 登录页开头带**两个** U+FEFF BOM | `authserver_login_page_with_service.real.html` |
| `checkNeedCaptcha.htl` 返回 `{"isNeed":false}`，Content-Type 是 `text/plain;charset=UTF-8` | 直接请求 |
| 正常账号当前不需要验证码 | 直接请求 |
| `encrypt.js` 的加密算法、48 字符表、64 字节前缀、16 字节 IV | `authserver_encrypt.js.real` |
| `login.js` 的 `setUrlParam` 改写 action、`createSliderCaptcha`、`checkForm` | `authserver_login.js.real` / `authserver_dzlogin.js.real` |
| `GET /new/login` → 405 Method Not Allowed（纯 API） | 直接请求 |
| 错误验证码 POST `/new/login` → `{"code":-1,"data":null,"message":"验证码不正确"}` | 直接请求 |
| `GET /yzm?d=...` → 200, `image/jpeg;charset=UTF-8`, JPEG 140×60, 下发 `JSESSIONID` | 直接请求 |
| jxfw 未登录访问 `/` → 302 到 authserver | 直接请求 |

### 推断自旧代码（未联网验证）

| 结论 | 来源 |
|---|---|
| `xsksap!ksapList.action` 的学期 `<option>` 结构、当前学期在 `selected` 项 | F# / Java |
| `xsAllKbList` 的 URL、GET、参数、`var kbxx` HTML 结构、字段含义、Referer | F#（2022） |
| `xsAllKbList` **是否仍然存活** | 无 |
| `getDataList` 的 URL、POST 表单、JSON 结构、字段含义、Referer | Java（2024） |
| 考试接口全部字段 | Java |
| 成绩接口全部字段、劳动教育 bug、两轮修复的语义 | Java |
| `getUserConf` 的 HTML 结构与正则 | Java |
| 学期短码/长码的换算关系 | Java |
| 教务直登的 `pwd` 是否明文 | Java（明确标为未验证） |
| 四个校区的作息时刻 | 旧小程序 `staticData/campusTime.js` |
| 11 色调色板与配色分配 | 旧小程序 `staticData/colors.js` |
| 图书馆二维码参数（H 级、margin 1、内容=学号） | Java |
| 学号首位 3/2/0 = 本科/研究生/教师 | Java `RoleConstant` |

### 完全未验证（需真实账号 / 真实抓包）

| 项 | 说明 |
|---|---|
| `getDataList` 是否真的返回 `kcbh` / `jxbmc` / `pkrq` | 解析器"有就用、没有就留空" |
| `pkrq` 的日期格式 | `parseDateLenient` 试多种格式 |
| `ksrq`（考试日期）的格式 | 同上 |
| `xsAllKbList` 与 `getDataList` 返回的**真实**校区/教师/教室多值形态 | 合成 fixture 只验证逻辑 |
| `pwdEncryptSalt` 的字符集是否总是 16 个可打印字符 | 有长度校验 |
| 教务直登 `pwd` 是否需要加密 | **最需要优先验证** |
| `route` cookie 的设置规则 | 实测只看到它下发 |
| 番禺校区第 10~12 节作息 | 原数据三节完全相同，疑为占位值 |
| 研究生（yjsxt/ehall）体系 | 见第 7 节 |
| 学校接口的稳定性/改版频率 | —— |

> **合成 fixture 不能证明字段名真的存在。**
> 用 [登录验证工具](./07-verify-login.md) 跑一次真实账号，把 dump 出来的响应替换掉合成 fixture，
> 才算真正闭环。这也是该脚本存在的主要目的。

---

## 7. 研究生（yjsxt / ehall）体系记录（当前不实现）

当前版本只实现本科生。保留常量与本节是为了：
1. 登录成功后能识别出"这是研究生账号"并给出明确提示（`GdutException.UnsupportedUserType`）；
2. 将来要做时不必重新逆向。

已知的额外复杂度：

- 统一认证 `service` 参数指向
  `https://yjsxt.gdut.edu.cn/gsapp/sys/yjsemaphome/portal/index.do`；
- 每个子应用要**单独 POST 授权一次**（`wdcjapp` 成绩、`wdkbapp` 课表）；
- 课表返回的是**具体时刻** `KSSJ`/`JSSJ`（如 `1630`/`1715`）而非节次，需要时刻→节次映射表；
- `ZCBH` 是 21 位的 0/1 串，`ZCMC` 是 `"1-16周"` / `"1-11单周"` / `"2-12双周"` 这类文本；
- 字段名全大写：`KCMC` `JASMC` `JSXM` `XQ` `BJMC`；
- 需要做**连堂课合并**（相邻节次同一门课合成一条）；
- ehall 的响应是三层嵌套 `{ datas: { <app>: { rows: [...] } } }`
  （`LenientJson.nestedRows` 已为此预留）。

`Course.parseWeeks` 已经能解析 `"1-16单周"` / `"1-11双周"` 这类文本，
算是为研究生做的少量铺垫。

---

## 8. 文档与代码不一致汇总

| # | 位置 | 文档说 | 实际 |
|---|---|---|---|
| 1 | `GdutHosts` KDoc | 默认值由 `GdutHostsTest` 交叉校验 | 仓库里**没有** `GdutHostsTest` |
| 2 | `LibraryRepositoryImpl.qrModuleCount` | KDoc 解释了 21 的来源 | 硬编码 21，与实际 BitMatrix 无关 |
| 3 | 任务背景里说 fixture 在 `data-gdut/src/main/resources/fixtures/` | —— | 实际在 `data-gdut/src/**test**/resources/fixtures/`。KDoc 里写的是 `src/test/resources/fixtures/`，是对的 |
| 4 | `Daos.SyncStateDao` KDoc | "两者的不一致由 `SyncStateDaoTest` 盯着" | 仓库里**没有** `SyncStateDaoTest` |
| 5 | `SyncStateEntity.SINGLETON_ID` | KDoc 引用 `SyncStateDaoTest` 断言 `== 1` | 同上，测试不存在 |

第 3 条是任务描述与代码的差异（代码是对的）；第 1、4、5 条是代码 KDoc 引用了**不存在的测试**。
