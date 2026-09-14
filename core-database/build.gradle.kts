plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

// core-database：Room。
//
// 为什么用 Room 而不是 DataStore/文件：
// - 课表要按 (学期, 周次, 星期) 查询，成绩要按学期分组，这些都是关系型查询；
// - Widget 进程需要**只读**地快速取当天课程，SQLite 的索引查询比反序列化整个 JSON 快得多；
// - 一学期几百行课程 + 几百行成绩，体量正好是 SQLite 的甜点区。
//
// ⚠ KSP 与 AGP 9 built-in Kotlin 有冲突，靠 gradle.properties 里的
//   android.disallowKotlinSourceSets=false 绕过，不要删那一行。
android {
    namespace = "com.gdutday.core.database"
    compileSdk = libs.versions.compileSdk.get().toInt()

    sourceSets {
        // 把 schema JSON 暴露给单测 classpath/assets，MigrationTestHelper 才能读到 1.json / 2.json。
        getByName("test") {
            resources.srcDir("$projectDir/schemas")
            assets.srcDir("$projectDir/schemas")
        }
    }

    testOptions {
        unitTests {
            // Robolectric 读取 assets（schema JSON）需要这个开关。
            isIncludeAndroidResources = true
        }
    }

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")

        // Room 的 schema 导出目录。schema JSON 必须提交进仓库，
        // 否则将来写迁移时无法验证、也没法做 MigrationTest。
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
            // 生成的 DAO 实现里带中文注释时更容易定位问题
            arg("room.incremental", "true")
        }
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

    api(libs.androidx.room.runtime)
    api(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.android)

    // 纯 JVM 断言 + Robolectric（真实 SQLite 上跑 Room 迁移，见 MigrationTest）。
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
