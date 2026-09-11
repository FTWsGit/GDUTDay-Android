plugins {
    alias(libs.plugins.android.library)
}

// core-datastore：偏好设置 + 加密的登录态存储。
//
// 只用 DataStore Preferences（不用 Proto），因为字段不多且不需要强 schema；
// 但**会话 cookie 不进 DataStore**，走 Keystore 加密后的独立文件，
// 原因见 SessionStore 的注释。
android {
    namespace = "com.gdutday.core.datastore"
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
    // 会话模型（GdutSession / StoredCookie / LoginMethod / GdutHosts）已下沉到 core-model，
    // 这里不再依赖协议层 data-gdut —— core 必须是叶子（分层修复 M21）。

    api(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.truth)

    // 注意：不用 androidx.security:security-crypto。
    // 它内部封装的 EncryptedSharedPreferences 在部分机型上有已知的
    // "首次创建耗时数秒"和"master key 损坏后无法恢复"的问题，
    // 而且已经处于维护模式。这里直接用 AndroidKeyStore + AES/GCM 自己实现，
    // 代码量差不多，但行为完全可控。详见 KeystoreCipher。
}
