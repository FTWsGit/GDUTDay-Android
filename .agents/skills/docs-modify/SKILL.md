---
name: docs-modify
description: Documentation sync discipline for the GDUTDay project. Use when the current task actually made code changes, test passes, and docs need to be updated.
---

# 文档同步纪律

## `docs/` 怎么组织

| 子目录 | kind | 回答什么问题 | 装什么 |
|---|---|---|---|
| `docs/`（根） | `overview` | 项目是什么 | 项目概览，唯一入口级文档 |
| `docs/architecture/` | `architecture` | 整体结构是什么 | 模块依赖图、设计决策（不用 WebView / 不用 Hilt / 直连学校不做后端）、冷启动路径、线程模型、WorkManager 链路 |
| `docs/subsystems/` | `subsystem` | 一个子系统的现状 | 是什么 + 就地的从句级局部理由 + 这个子系统自己的不变量，一个子系统一个文件说完；如 data-model（Room 实体/DAO/映射/学期解析）、ui（课表网格渲染/自动字色/并排布局）、widget（Glance 插件/尺寸响应/分级刷新） |
| `docs/guides/` | `guide` | How do I…? | 任务导向的查表型文档，一行任务对一行答案；如 agent-task-list、testing-strategy、verify-login、api-probing |
| `docs/guides/contract/` | `contract` | 真正跨子系统、没有单一 owner 的规则 | 编码约定（命名/安全/配置管理）这类没有任何一个子系统能独占的规范；能算进某个子系统自己的事，就不放这里，放对应 `subsystems/*.mdc` |
| `docs/reference/spec/` | `spec` | 外部知识和契约 | authserver / jxfw 协议逆向报告、登录流程、AES 加密、课表接口字段表等外部依赖的协议，学校改接口时才该动 |
| `docs/decisions/` | `decision` | 为什么选了这个而不是那个 | 标准 ADR，只收"防回归/真权衡/跨场景可复用"这三类；只言片语的从句级理由不开 ADR，就地写在 subsystems 里 |

**引用规则**：`decisions/NNNN` 和 `subsystems/<name>` 都可以被其它 `.mdc` 按稳定标识引用（decision 用四位编号、subsystem 用 front matter 的 `name`），例如"见 decision 0005"「见「data-model」」——不要把已经在别处写过的论证/协议复述一遍。`guides/` 内部文件之间也一样，按 `name` 互相指。唯一禁止的是引用裸文件路径（如 `见 xxx.mdc`），因为路径会随目录调整而失效，`name` 不会。

## 脚本

| Script | 干什么 | 用法 |
|---|---|---|
| `docs:list` | 枚举 `docs` 全部 front matter，输出 JSON | `sh .agents/skills/docs-read/scripts/list_docs.sh [--dir <path>]` |
| `docs:create` | 创建新的 .mdc 文档，自动添加规范 front matter | `sh .agents/skills/docs-read/scripts/create_docs.sh <name> "<description>" --kind <kind> [--always] [--dir <path>] [--force]` |

其中，--dir 默认 "docs"（`docs:create` 建议传对应 kind 的子目录，如 `--dir docs/subsystems`，而不是让新文档落进 `docs` 根目录）; --kind 是 `docs:create` 专属，写 `spec`/`architecture`/`subsystem`/`decision`/`contract` 之一（不给会 warn 但不会拦，front matter 里 `kind` 字段留空）; --always 将 alwaysApply 调整为 true，不填默认 false; --force 是强制覆盖同名文件，慎用。**`decision` 的两条约定脚本不会帮你校验**——文件名前缀四位序号（`0008-slug`）、`alwaysApply` 恒为 false，都要自己传对，传错脚本照样生成不会报错。本项目内部接口形状（类型/签名）不单独写文档——Python 源码本身就是契约，直接读源码；行为约定/gotcha 进 `guides/`。

## 触发条件

| 等级 | 变更性质 | 示例 | 同步要求 |
|---|---|---|---|
| **L0 机械重构** | 纯重命名、移动文件、提取函数、改 import 路径，行为不变 | 变量重命名、`move file A→B`、`extractFn` | 只在 `.mdc` 正文/description 点名了旧名字时才同步 |
| **L1 子系统内部变更** | 子系统内部实现变化，对外形状不变 | data-gdut 的登录加密改用新算法、CourseNormalizer 内部优化 | **必须检查该子系统对应的 `.mdc`** |
| **L2 结构/机制变更** | 数据结构、跨子系统机制、管道流程变化 | 管道从 VAD 分段改为直通流式、翻译状态机调整 | **必须检查机制文档**（描述该机制的 `architecture/`、`subsystems/` 正文）；行为变了但文档仍说"会这样"，文档就是错的 |
| **L3 架构迁移** | 拆模块、新增/删除 ASR 后端、改管道架构 | 删除 Vosk backend、新增一种翻译引擎 | **必须动文档结构**：新增/删除/搬迁 `.mdc`，新开 decision |
| **L4 对外契约变更** | 公开接口签名、i18n 域、配置项、纪律与契约变化 | 新增设置项、新增 i18n key 域、pipeline 公开 API 变化 | 修改变更所属的契约文档 |

**判定入口**：问自己"这次变更落在 L 几？"——不是"有没有改代码"，而是"改了什么性质的代码"。


## 二、同步检查清单（按等级递进）

### 所有等级都必须做
1. 跑 `sh .agents/skills/docs-read/scripts/list_docs.sh`，拿当前 front matter 真相源（文档名单以它为准，不靠记忆）。

### L1+ 必须做：接口引用检查

在 `docs/**/*.mdc` 里 grep 变更涉及的标识符：函数名、字段名/类型名、文件路径、类名。命中了就必须读对应 `.mdc` 判断是否需要同步。

### L2+ 必须做：机制事实检查

对描述该机制的 `.mdc`，逐条核对正文里的具体事实是否仍成立：

- "文件名是 X" → 文件还在吗？改名了吗？
- "字段 Y 做 Z" → 字段还在吗？逻辑变了吗？
- "管道走 A→B→C" → 流转路径变了吗？
- "还没做 / 已经做了" → 状态变了吗？

**判定算法**：对每条事实问自己——"如果下次 session 不知道这次改动，会基于这条文档做出错误判断吗？"会，就改；不会，跳过。

### L3+ 必须做：架构一致性检查

- 新增代码匹配现有套路吗？→ 需要补新条目吗？
- 新增/删除的子系统或功能有对应 `.mdc` 吗？→ 没有就新建，有就更新。
- 开了新决策吗？→ 进 `decisions/`，四位序号前缀，`alwaysApply: false`，`status: accepted`。
- 推翻了旧决策吗？→ 旧文件 `status: superseded`，新文件 `supersedes` 指回旧文件。**不回头改旧决策正文**。

### L4 必须做：契约/子系统更新

L4 动的是项目对外承诺的"规矩"——公开接口签名、i18n 域、配置项、某个子系统的不变量。规矩优先落在**变更所属的那一个 `subsystems/*.mdc`**；只有真正跨子系统、没有单一 owner 的才落 `guides/contract/`：

- **改了公开接口签名 / 新增导出** → grep 旧签名在 `docs/**/*.mdc` 的引用；对应 `subsystems/*.mdc` 里写了用法或行为约定的必须同步
- **新增 i18n 领域或 key** → 对照命名契约的域划分：新 key 落进已有域则不动契约；引出了新域必须在该契约补域定义
- **改了某个子系统自己的不变量/纪律**（如模型加载方式）→ 改对应 `subsystems/*.mdc`，那是这条规则的单源；改完 grep 其他 `.mdc` 是否复述了旧规则，复述的也要同步成指针

**判定入口**：问自己"这条规矩主要属于哪一个子系统？"——能答出具体某一个，改那个 `subsystems/*.mdc`；答不出（真的横跨全部子系统、没有单一 owner），才进 `guides/contract/`。

---

## 三、新增文档的纪律

新建 `.mdc` 前必须先回答：

1. **这是哪个 kind？** `contract` / `architecture` / `subsystem` / `decision`
2. **权威来源是谁？** 上游库文档 / 本项目代码 / 设计讨论
3. **更新触发条件是什么？** "对应代码变了就改" vs "决策被推翻才改"
4. **alwaysApply 是 true 还是 false？** true = 每个 session 的默认上下文；false = 靠 description 按需匹配
5. **description 怎么写？** 只写"这个文档是什么、何时读"，不写"为什么"——"为什么"进正文或 decision
6. **这条事实别处写过没有？** 先在 `docs/**/*.mdc` 里搜一遍关键词（文件名/字段名/机制名/decision 编号）。搜到了，改成按 `name` 或编号指过去，不要重新表达一遍；搜不到才允许新写。这一步跟改代码后"grep 旧标识符找该同步的文档"是同一个纪律，只是方向反过来：**写新内容前也要 grep**，不能只在改完代码后才 grep。


## 四、判断一句"为什么"该写在哪

不是所有"为什么"都配得上一篇 ADR。大部分本地理由是从句级的，比如"管道用 queue.Queue 而不是 asyncio 是为了避免引入异步依赖""Sherpa 单一后端是因为流式 ASR 不需要多后端路由"——这种**留在对应 `subsystems/*.mdc` 里当从句写一次就够**，不用单独开 decision，也不要因为"不能写为什么"就把这句话删掉——删了会让读者以为是随手写的数字，反而更容易被误改。

只有满足以下任一条，才值得升级成 `decisions/` 里一篇独立、可引用、accepted 后不改正文的 ADR：

1. **防回归价值**——不写下来，以后大概率会被一个善意的重构"优化"回一个更差甚至有 bug 的版本。
2. **真的比较过备选方案**，取舍本身有信息量，值得留痕迹给以后重新审视。
3. **跨子系统、以后大概率被复用的模式**，需要一个其它文档能安全指过去的锚点。

判定动作：写一句"为什么"之前问自己——"去掉这句话，会不会有人把代码往回改坏？"会，才考虑开 decision；单纯是"顺手记录一下当初怎么想的"，就是从句级理由，就地写。

**已经存在的 decision 不要在 subsystems 里复述论证**：subsystems 文档只写"是什么/现在这样做的结果"，加一句"为什么这样、以后想改先看这个"指向 decision 编号；决策的背景/权衡/后果只在 decision 正文出现一次。


## 改 `docs` 的硬规则

- `docs/*` 只写"项目是什么"，不写用户要求、不写对 AI 的喊话、不写踩坑史
- `description` 只写"文档里面有什么、何时读"，不写法理论
- **禁止引用裸文件路径**（如 `见 xxx.mdc`）——路径会随目录调整失效。**允许、且应该按稳定标识互相引用**：decision 用编号（"见 decision 0005"），其它 `.mdc` 用 front matter 的 `name`（「见「data-model」」）。引用是为了不复述——一条事实/一段论证只在它的权威归属文件里出现一次，其它地方只放指针。
- **文档体量变大是拆分信号**：出现两种阅读节奏（通读理解 vs Ctrl+F 查一行）就拆
- 出现"因为/是为了/而不是/避免/刻意"这类论证连接词时先自查：这是从句级的局部理由（留着）还是完整论证（该不该已经在某篇 decision 里、这里该不该改成指针）？不确定就搜一遍 `decisions/` 有没有对应的

## 不做的事

- 不硬编码 `docs` 文件清单——清单靠 front matter 真相源，跑 `docs:list` 拿
- 不改ADR：文档里的"为什么这样设计"（decision 类内容）不因代码重构而重写. 只要是decisions/* 已经accepted的内容不能修改，只能创建新的文档
- 不替代码写注释：`docs` 是跨 session 的持久知识，不是代码注释的搬运工
- 不照搬源码：Python 源码本身就是接口契约，不要把类型签名逐行抄进 `docs`
- 不重复：一条具体事实（文件名/字段行为/协议/决策论证）只在它的权威归属文件里出现一次；其它地方引用，不复制
- contract 只收真正没有单一 owner 的跨子系统规则；一个子系统自己的调用规范/不变量写进它自己的 `subsystems/*.mdc`，不要因为"这是外部契约"就习惯性地丢进 `guides/contract/`
