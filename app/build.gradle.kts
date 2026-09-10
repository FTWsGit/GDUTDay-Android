plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ============================================================================
// app：应用壳。
//
// 这里**只**放三样东西：
//   1. Application（持有 AppContainer）
//   2. MainActivity（唯一的 Activity）
//   3. NavHost（路由）
//
// 所有业务逻辑都在 feature-* 和 data-repository 里。
// app 模块的代码量应该始终保持在几百行以内 —— 一旦它开始膨胀，
// 说明有东西放错地方了。
//
// ## 单 Activity 架构
//
// 只有一个 Activity + Compose Navigation。理由与"启动速度"直接相关：
// - 多 Activity 每次切换都要走完整的生命周期 + 窗口创建，约 100~300ms；
// - 单 Activity 内 Compose 导航只是重组，几毫秒；
// - 而且只有一个窗口需要维护主题/ insets，状态更简单。
// ============================================================================
android {
    namespace = "com.gdutday.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.gdutday.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0-alpha01"

        // 只保留中文资源。多语言会让 resources.arsc 变大，
        // 而这个 App 的目标用户 100% 是中文使用者。
        resourceConfigurations += listOf("zh-rCN")

        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        // debug 签名用 AGP 内置的，不额外配置。
        // release 签名不要写进这个文件（会进版本库）。
        // 正确做法：在 ~/.gradle/gradle.properties 里配 GDUTDAY_STORE_FILE 等属性，
        // 或者用 CI 的 secret。见 docs/00-architecture.md 的"发布"章节。
        create("release") {
            val storeFile = findProperty("GDUTDAY_STORE_FILE")?.let { file(it) }
            val storePassword = findProperty("GDUTDAY_STORE_PASSWORD")
            val keyAlias = findProperty("GDUTDAY_KEY_ALIAS")
            val keyPassword = findProperty("GDUTDAY_KEY_PASSWORD")
            if (storeFile != null && storePassword != null && keyAlias != null && keyPassword != null) {
                this.storeFile = storeFile
                this.storePassword = storePassword as String
                this.keyAlias = keyAlias as String
                this.keyPassword = keyPassword as String
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // 调试版允许明文流量是不必要的，学校所有接口都是 https。
            // 不开启 usesCleartextTraffic，等于默认禁止，能挡住降级攻击。
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // R8 全量模式。对启动速度和包体积都有实际收益（更激进的内联与类合并）。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 签名配置：signingConfig 绑定在上面 signingConfigs.create("release")，
            // 其属性来自 ~/.gradle/gradle.properties。属性缺失时 signingConfig 保持空，
            // assembleRelease 产出 unsigned apk —— 强制发布走显式配置，避免误用 debug 签名发布。
            signingConfig = signingConfigs.getByName("release")
            // 属性没配齐时产物仍是 unsigned：签名块内四个属性任一为 null 就不写入 signingConfig
            if (findProperty("GDUTDAY_STORE_FILE") == null ||
                findProperty("GDUTDAY_STORE_PASSWORD") == null ||
                findProperty("GDUTDAY_KEY_ALIAS") == null ||
                findProperty("GDUTDAY_KEY_PASSWORD") == null
            ) {
                signingConfig = null
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            // OkHttp / jsoup / zxing 都带 META-INF 元数据，不排掉会打不出包
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
                "META-INF/versions/*/OSGI-INF/MANIFEST.MF",
            )
        }
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
    implementation(project(":core-network"))
    implementation(project(":core-database"))
    implementation(project(":core-datastore"))
    implementation(project(":core-ui"))
    implementation(project(":data-gdut"))
    implementation(project(":data-repository"))
    implementation(project(":feature-auth"))
    implementation(project(":feature-schedule"))
    implementation(project(":feature-grade"))
    implementation(project(":feature-settings"))
    implementation(project(":widget"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
