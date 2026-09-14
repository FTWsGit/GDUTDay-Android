# 测试 fixture 说明

## `.real` 后缀 = 从线上抓取的**真实**响应

抓取时间：2026-09-10（authserver）/ 2026-09-14（jxfw，`:data-gdut:dumpFixtures`）
抓取方式：真实登录会话（jxfw 部分）/ curl + 桌面版 Chrome UA（authserver 部分）

| 文件 | 来源 | 说明 |
|---|---|---|
| `authserver_login_page.real.html` | `GET https://authserver.gdut.edu.cn/authserver/login?type=userNameLogin` | **不带** `service` 参数，页面里 `var service = null;` |
| `authserver_login_page_with_service.real.html` | `GET https://jxfw.gdut.edu.cn/new/ssoLogin` 跟随 1 次 302 后的落点 | **带** `service`，页面里 `var service = ["https:\/\/jxfw.gdut.edu.cn\/new\/ssoLogin"];` |
| `authserver_encrypt.js.real` | `.../gdutThemes/static/common/encrypt.js` | CryptoJS 库 + 末尾的 `encryptPassword` / `getAesString` / `randomString`，**加密算法的地面真相** |
| `authserver_login.js.real` | `.../gdutThemes/static/web/js/login.js` | 提交逻辑：`setUrlParam` 改写 form action、`startLogin`、`checkForm`、`createSliderCaptcha` |
| `authserver_dzlogin.js.real` | `.../gdutThemes/static/web/js/dzlogin.js` | 同一套逻辑的非压缩版，可读性好，用于人工比对 |
| `jxfw_term_list.real.html` | `GET /xsksap!ksapList.action`（已登录） | 学期列表 HTML，`JxfwTermParser` 的地面真相 |
| `jxfw_schedule_data_list_p1.real.json` | `POST /xsgrkbcx!getDataList.action` 第 1 页（已登录） | 课表接口 B 真实响应，`kcbh`/`jxbmc`/`pkrq`/`ksrq` 字段已联网确认存在 |
| `jxfw_schedule_all_kb_list.real.html` | `GET /xsgrkbcx!xsAllKbList.action`（已登录） | 课表接口 A 真实响应。**2026-09-14 实测仍存活**（解析出 13 行），AUTO 回退链保留 |
| `jxfw_exam_data_list.real.json` | `POST /xsksap!getDataList.action`（已登录） | 考试安排（非考试周，`total=0` 空响应也是地面真相） |
| `jxfw_score_data_list_all.real.json` | `POST /xskccjxx!getDataList.action`（已登录） | 全部学期成绩，含劳动教育 bug 现场（`xnxqdm=""` 查询） |

### 隐私说明

这些页面都是**未登录状态**下抓取的，不含任何个人信息。
文件里的 `pwdEncryptSalt`（如 `xaOfScaw6epvgypH`）和 `execution` token
都是**一次性、会话绑定、且已过期**的值，没有安全风险；保留原值是为了让测试
能验证"从真实页面里解析出真实字段"这条路径。

jxfw 的 5 个 `.real` 文件抓自已登录会话，入库前已脱敏：
所有真实人名（学生 `xsxm`、教师 `teaxms`）替换为 `师N老师` 占位，
无 cookie 值、无学号。重新抓取用 `:data-gdut:dumpFixtures`（凭据同 verifyLogin），
dump 原始文件在 `build/dump-fixtures/`，脱敏后再移入本目录。

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
- `class_schedule_main.real.html`：班级课表入口页真实抓包。

重抓命令见 `temp/issue-001-class-schedule-import.md` 第 6 节（需真实会话）；
抓到真实响应后替换上述文件，注意先脱敏。
