plugins {
    alias(libs.plugins.kotlin.jvm)
}

// core-model：纯领域模型，零第三方依赖。
// 所有其它模块都可以依赖它，它不依赖任何模块。
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
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
