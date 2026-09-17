# ============================================================================
# R8 / ProGuard 规则（app 模块）
# ============================================================================
#
# 原则：**能不加规则就不加**。每一条 keep 规则都会让 R8 少优化一块代码，
# 直接反映为包体积变大和启动变慢。
#
# 本项目没有反射式框架（没有 Hilt/Dagger/Gson/Moshi 的运行时反射），
# 所以理论上需要的 keep 规则非常少。下面几条都是实测必需的。

# ---- kotlinx.serialization ----
# 序列化器的 companion 是通过反射查找的（`serializer()` 静态方法），
# R8 会把它们当成未使用代码删掉。
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# 我们自己的 @Serializable 类：保留 serializer()
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
    static kotlinx.serialization.KSerializer serializer(...);
}

# ---- OkHttp ----
# OkHttp 5 用 ServiceLoader 加载 TLS 扩展（conscrypt / Android10Platform）。
# R8 会删掉 ServiceLoader 的注册文件，导致运行时静默降级。
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# ---- jsoup ----
# jsoup 本身无反射，但它会按需加载可选的 XML 解析器。
-dontwarn org.jsoup.**

# ---- ZXing ----
# zxing-core 有多平台实现（JavaSE/Android/J2ME），R8 会对未选中的分支报 warn。
-dontwarn com.google.zxing.client.j2se.**
-dontwarn com.google.zxing.client.android.**

# ---- Room ----
# Room 生成的 Impl 类由注解处理器直接引用，通常不需要 keep。
# 但 @Dao 的默认方法（Kotlin interface with body）在某些 R8 版本下会被内联出错，
# 保守起见保留 DAO 接口本身（只有几个接口，体积影响可忽略）。
-keep interface com.gdutday.core.database.*Dao { *; }

# ---- 领域模型 ----
# data class 的 copy/componentN 由编译器直接调用，不需要 keep。
# 但 GdutException 的子类是通过 `when (e) { is X -> }` 匹配的，
# R8 的类合并优化可能把只有一个实现的 sealed 子类合并掉 —— 那不影响正确性，
# 反而更小，所以**不**加 keep。

# ---- 保留行号 ----
# 崩溃栈里没有行号等于没有栈。用 nameSourceFile + 保留行号，
# 同时把源文件名统一成一个字符串避免泄露模块结构。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---- Kotlin metadata ----
# 运行时不需要（没有反射框架读它），删掉能省不少体积。
# 但如果将来引入了需要读 metadata 的库（如 Moshi 的反射适配器），要注掉这一行。
# -keep class kotlin.Metadata { *; }   # 刻意不启用

# ---- Glance ActionCallback ----
# 桌面插件按钮用 `actionRunCallback<T>()` 注册点击事件：Glance 在收到点击广播时，
# 按类名反射 `Class.forName(...).newInstance()` 实例化对应的 ActionCallback，
# 要求一个公开无参构造函数。类名本身因为有 `T::class.java` 引用不会被删，
# 但 R8 默认仍会混淆类名/成员，且可能把"看起来没人调用"的无参构造函数优化掉——
# 两者任一发生，点击都会在 release 包上静默失败（不崩溃、不刷新，表现就是"点了没反应"）。
-keep class * implements androidx.glance.appwidget.action.ActionCallback {
    public <init>();
}

# ---- WorkManager 默认反射工厂 ----
# ScheduleSyncWorker 由 GdutWorkerFactory 直接 `new` 出来，构造调用在源码里可见，
# R8 天然不会动它；但 NextClassRefreshWorker 没注册进那个工厂，
# 由 WorkManager 按 WorkSpec 里持久化的类名走默认反射工厂实例化，
# 原理和上面的 ActionCallback 一样，同样需要保留标准 (Context, WorkerParameters) 构造函数，
# 否则跨天定时刷新会在 release 包上悄悄失效。两个 Worker 一起保留，成本可忽略。
-keep public class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
