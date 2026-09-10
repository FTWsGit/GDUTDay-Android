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

    // 只跑纯 JVM 断言（常量一致性、映射往返），不需要 Robolectric。
    // 真正的 SQL 正确性要靠 androidTest 里的 in-memory Room，见 docs/06-testing-strategy.md。
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
