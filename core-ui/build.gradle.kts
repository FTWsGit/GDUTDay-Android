plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

// core-ui：主题、通用组件、可复用的 Compose 基础件。
// **不依赖任何数据模块** —— 它是叶子，谁都能依赖它，它不依赖谁。
android {
    namespace = "com.gdutday.core.ui"
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
    api(project(":core-model"))
    api(project(":core-common"))

    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.ui.graphics)
    api(libs.androidx.compose.ui.tooling.preview)
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.material.icons.extended)
    api(libs.androidx.core.ktx)

    debugImplementation(libs.androidx.compose.ui.tooling)

    // 纯逻辑（WCAG 相对亮度、隐私打码）的 JVM 单测。
    // 这些函数刻意不依赖 Android/Compose，测试无需 Robolectric。
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
