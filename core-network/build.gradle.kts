plugins {
    alias(libs.plugins.android.library)
}

// core-network：OkHttpClient 工厂 + 连接状态监听。
//
// 刻意保持极薄。项目只有一个网络栈（OkHttp，由 data-gdut 使用），
// 没有 Retrofit —— 教务系统的接口既不是 REST 也不返回一致的 JSON，
// 用 Retrofit 的注解式声明反而会掩盖掉那些怪异之处（text/html 装 JSON、
// 302 回登录页当"成功"、字段名随学期变化）。
android {
    namespace = "com.gdutday.core.network"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

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
    api(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
}
