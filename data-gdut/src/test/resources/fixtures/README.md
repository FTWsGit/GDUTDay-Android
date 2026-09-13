# 测试 fixture 说明

## `.real` 后缀 = 从线上抓取的**真实**响应

抓取时间：2026-09-10
抓取方式：curl + 桌面版 Chrome UA，未登录状态

| 文件 | 来源 | 说明 |
|---|---|---|
| `authserver_login_page.real.html` | `GET https://authserver.gdut.edu.cn/authserver/login?type=userNameLogin` | **不带** `service` 参数，页面里 `var service = null;` |
| `authserver_login_page_with_service.real.html` | `GET https://jxfw.gdut.edu.cn/new/ssoLogin` 跟随 1 次 302 后的落点 | **带** `service`，页面里 `var service = ["https:\/\/jxfw.gdut.edu.cn\/new\/ssoLogin"];` |
| `authserver_encrypt.js.real` | `.../gdutThemes/static/common/encrypt.js` | CryptoJS 库 + 末尾的 `encryptPassword` / `getAesString` / `randomString`，**加密算法的地面真相** |
| `authserver_login.js.real` | `.../gdutThemes/static/web/js/login.js` | 提交逻辑：`setUrlParam` 改写 form action、`startLogin`、`checkForm`、`createSliderCaptcha` |
| `authserver_dzlogin.js.real` | `.../gdutThemes/static/web/js/dzlogin.js` | 同一套逻辑的非压缩版，可读性好，用于人工比对 |

### 隐私说明

这些页面都是**未登录状态**下抓取的，不含任何个人信息。
文件里的 `pwdEncryptSalt`（如 `xaOfScaw6epvgypH`）和 `execution` token
都是**一次性、会话绑定、且已过期**的值，没有安全风险；保留原值是为了让测试
能验证"从真实页面里解析出真实字段"这条路径。

## 没有 `.real` 后缀的 = 人工构造的**合成** fixture

`jxfw_*.json` / `jxfw_*.html` 这类是照着逆向出来的字段名手工写的，
**不是真实抓包**。它们能验证解析器的逻辑正确性，但**不能证明字段名在学校那边真的存在**。

用 `docs/07-verify-login.md` 里的验证脚本跑一次真实账号，
把 dump 出来的响应替换掉这些合成 fixture，才算真正闭环。
合成 fixture 里凡是"字段名未经联网确认"的地方，文件内都写了 `⚠ 未验证` 注释。

### 班级课表（issue-001，实测 2026-09-12）

- `class_schedule_get_kb_rq.json`：`GET /xsbjkbcx!getKbRq.action` 的合成样例，
  结构按实测记录构造（`[课表rows(24字段), 周日期rows(xqmc/rq)]`，`jcdm` 两位拼接、`zc` 单周次、`pkrs` 上课日期）。
- `class_schedule_all_kb_list.html`：`GET /xsbjkbcx!xsAllKbList.action` 的合成样例，
  `var kbxx` 的 9 字段结构与个人课表 A 完全一致。

重抓命令见 `temp/issue-001-class-schedule-import.md` 第 6 节（需真实会话）；
抓到真实响应后替换上述文件，注意先脱敏。
