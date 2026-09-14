---
name: docs-read
description: Pre-reading discipline for the GDUTDay project. Use before reading or writing code for any task that requires understanding the project to answer—adding features, fixing bugs, refactoring, modifying code, and also purely explanatory/discussion/troubleshooting questions ("is this field in the wrong place?", "how is this logic parsed?", "how is XX implemented?", "fix this bug").
---

# 读码前置纪律

## 为什么存在

`docs/` 下的 `.mdc` 文档不会被当前 cli agent 自动读取注入上下文。本 skill 借用"cli agent 会读 skill"这个特性模拟 Cursor 自动读 `.mdc` 的效果。

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
| `docs:get_always` | 堆读 `alwaysApply:true` 文档完整内容，stdout 纯文本 | `sh .agents/skills/docs-read/scripts/print_always.sh [--dir <path>]` |

其中，--dir 默认 "docs"。

## Phase 1 — 开干前读核心文档

**任何需要理解 GDUTDay 才能回答的任务**——不管最终会不会落地成一次代码改动，纯解释"这是怎么实现的"、纯排查"这是不是 bug"也算——在读第一行源码 / 写第一行代码 / 第一个 edit 之前，跑一次 print_always 把所有 `alwaysApply:true` 文档全量读进上下文：

```bash
sh .agents/skills/docs-read/scripts/print_always.sh
```

## Phase 2 — 碰具体代码区域前按映射读领域文档

根据**要碰的文件路径/技术区域**匹配读对应 `alwaysApply:false` 文档——"碰"包括要改它、要读它答疑、要排查它的行为，不是只有"要改"才算。读之前读，不是读完源码/改完代码再补。拿映射对的清单靠 `docs:list` 跑一遍看当前有哪些 `alwaysApply:false` 文件 + 它们的 `description`（"何时读"由每个 `.mdc` 的 front matter `description` 字段决定）。

**判定 Phase 2 要读哪几个**：跑 `docs:list`

```bash
sh .agents/skills/docs-read/scripts/list_docs.sh
```

遍历输出里 `alwaysApply: false` 的条目，问自己"要碰的文件 / 区域符不符合这个条目 description 写的场景"——符合就读对应 `.mdc`。

**没匹配到**：要碰的区域不在任何 `alwaysApply:false` 文档的 description 场景里，Phase 2 不读额外文档——Phase 1 的核心文档已经覆盖了这些通用知识。

**多文件多映射**：一次任务要碰多个不同区域的文件时，把每个区域映射到的文档都读一遍。使用Read 工具并行读取

**遇到不确定的边界**：改动横跨多个领域，优先两个都读，不要赌"够用"。
