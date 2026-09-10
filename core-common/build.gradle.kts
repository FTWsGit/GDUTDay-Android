plugins {
    alias(libs.plugins.kotlin.jvm)
}

// core-common：与 Android 无关的纯逻辑 —— 作息表、学期历、节次切分、课表网格、配色。
// 全部有单元测试，跑在 JVM 上，不需要模拟器。
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    // explicitApi() 只作用于 main 源集，不会波及测试代码。
    // 之前用 freeCompilerArgs.add("-Xexplicit-api=strict")，那个是编译器级开关，
    // 测试类里每个 @Test 方法都会被要求写 public，非常吵。
    explicitApi()
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core-model"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
