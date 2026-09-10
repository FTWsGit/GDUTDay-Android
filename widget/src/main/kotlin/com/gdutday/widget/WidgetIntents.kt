package com.gdutday.widget

import android.content.Context
import android.content.Intent

/**
 * 打开主 App 的 Intent。
 *
 * ## 为什么不用 `actionStartActivity<MainActivity>()` 这种泛型写法
 *
 * 那需要在 widget 模块编译期引用 `com.gdutday.app.MainActivity`，而 app 依赖 widget，
 * 反向引用会形成模块环（同 [WidgetContainerAccess]）。这里改为运行时解析启动 Activity：
 * launcher intent 由 PackageManager 给出，天然正确，也顺带避免了写死类名后
 * 改包名/改 Activity 名导致的"点了没反应"。
 *
 * `launchMode="singleTask"`（见 AndroidManifest）保证不会叠出多个 Activity 实例。
 */
internal fun mainActivityIntent(context: Context): Intent =
    context.packageManager.getLaunchIntentForPackage(context.packageName)
        ?: Intent().setClassName(context.packageName, "com.gdutday.app.MainActivity")

/** 插件表头上的刷新文案。strings.xml 是既有资源，这里不新增键，直接用常量。 */
internal const val REFRESH_LABEL: String = "刷新"
