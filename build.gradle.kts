// 根构建脚本：只声明插件版本，不在这里 apply。
//
// 注意 AGP 9 的重要变化：Kotlin 支持已内置到 AGP 中。
// Android 模块【不要】apply `org.jetbrains.kotlin.android`，否则会直接构建失败：
//   "The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin
//    support since AGP 9.0."
// 纯 JVM 模块仍然使用 `org.jetbrains.kotlin.jvm`，版本与 AGP 内置 Kotlin 对齐（2.2.10）。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
