# ============================================================================
# widget 模块的 consumer 规则（随 AAR 自动合并进 app 的 R8 配置）
# ============================================================================
#
# 本模块有两处"字符串/反射式实例化"，R8 无法从静态引用看出它们被使用，
# 全量模式下会重命名甚至删除这些类，导致 release 包插件静默失效。
# 每条规则都限定到具体类和构造函数，尽量少阻碍 R8 优化。

# WorkManager 的默认 WorkerFactory 通过反射实例化 Worker（本类没有注册进
# GdutWorkerFactory，所以走默认工厂 —— 见 NextClassRefreshWorker 的 KDoc）。
# 被删/改名后表现为 ClassNotFoundException，倒计时链静默停摆。
-keep class com.gdutday.widget.NextClassRefreshWorker { <init>(...); }

# Glance 在点击时通过反射恢复 ActionCallback
# （TodayScheduleWidget 里的 actionRunCallback<WidgetRefreshAction>()）。
# 保留类和它的无参构造。
-keep class com.gdutday.widget.WidgetRefreshAction { <init>(); }
