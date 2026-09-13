plugins {
    alias(libs.plugins.android.library)
}

// data-repository：把 data-gdut（网络）+ core-database（持久化）+ core-datastore（偏好/会话）
// 编排成 UI 可直接消费的 Flow。
//
// 这是唯一同时依赖三者的模块，也是**唯一知道"同步流程长什么样"的模块**。
// UI 层只认这里的接口，完全不知道 jxfw / authserver 的存在。
android {
    namespace = "com.gdutday.data.repository"
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
    api(project(":core-common"))
    api(project(":core-network"))
    api(project(":core-database"))
    api(project(":core-datastore"))
    api(project(":data-gdut"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
