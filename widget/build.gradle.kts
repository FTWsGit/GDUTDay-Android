plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

// ============================================================================
// widget：桌面插件（App Widget）。
//
// 用 **Glance**（androidx.glance）而不是传统 RemoteViews：
// - Glance 用 Compose 语法写插件 UI，与主 App 的写法一致，能复用大量逻辑；
// - 它最终仍然编译成 RemoteViews，所以**没有 WebView、没有额外运行时**，
//   内存与耗电和手写 RemoteViews 相当；
// - 传统 RemoteViews 只支持十几种 View，写一个 7 列课表网格要几百行 XML + 适配器，
//   维护成本远高于 Glance。
//
// ## ⚠ 插件进程的数据访问
//
// App Widget 运行在**主 App 进程**（我们没有声明 android:process），
// 所以可以直接用 `context.appContainer` 拿到同一个容器、同一个 Room 数据库。
// 这是刻意的设计：如果给插件单独开进程，就要处理跨进程的数据库并发
// （SQLite 多进程需要 WAL + 仔细的锁），复杂度陡增而收益为零。
//
// 代价：插件更新会唤醒主进程。但 Room 的 Flow 只在数据变化时发一次，
// 而且插件本身就是低频更新（一天几次），完全可接受。
// ============================================================================
android {
    namespace = "com.gdutday.widget"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":core-common"))
    implementation(project(":core-database"))
    implementation(project(":core-datastore"))
    implementation(project(":core-ui"))
    implementation(project(":data-repository"))

    api(libs.androidx.glance.appwidget)
    api(libs.androidx.glance.material3)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // 纯 JVM 单测：尺寸映射、打码、倒计时分级刷新都是与 Android 无关的纯函数，
    // 用普通 JUnit4 + Truth 即可，不需要 Robolectric，跑得快也稳定。
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
