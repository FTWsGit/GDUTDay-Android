plugins {
    alias(libs.plugins.kotlin.jvm)
}

// ============================================================================
// data-gdut：广工各业务系统的协议逆向层。**整个项目风险最高、也最核心的模块。**
//
// 刻意做成纯 JVM 模块（不 apply com.android.library），原因：
//   1. 登录加密、表单构造、重定向跟随、HTML/JSON 解析全部与 Android 无关，
//      用 OkHttp + jsoup 的 JVM 版本就能跑；
//   2. 单元测试直接跑在 JVM 上，不需要模拟器，配合 MockWebServer 可以离线验证
//      整个登录流程（含 302 链、cookie 传递、错误页识别）；
//   3. 学校改接口时，改这一个模块 + 补一个 fixture 就能回归。
//
// 依赖注入方式：所有 Client 都**接受外部传入的 OkHttpClient**，
// 不在模块内部 new。这样 Android 侧可以统一配置超时/TLS/日志/CookieJar，
// 测试侧可以塞 MockWebServer 的 client。
// ============================================================================
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
    api(project(":core-common"))

    api(libs.okhttp)
    api(libs.jsoup)
    implementation(libs.zxing.core)
    implementation(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}

// ============================================================================
// verifyLogin：端到端登录验证工具（见 docs/07-verify-login.md）。
//
// 类在 **test** source set 里（data-gdut/src/test/.../tools/VerifyLogin.kt），
// 因此 classpath 必须取 test 源集的 runtimeClasspath，而不是 main。
// `dependsOn(testClasses)` 保证先编译测试代码。
//
// 凭据从环境变量 GDUT_STUDENT_ID / GDUT_PASSWORD 或项目根目录的
// secrets.properties 读取；JavaExec 默认继承当前进程的环境变量。
// workingDir 设为仓库根目录，让工具能直接找到根目录的 secrets.properties。
// ============================================================================
tasks.register<JavaExec>("verifyLogin") {
    group = "verification"
    description = "端到端登录验证：解析登录页 → 加密 → 登录 → 学期/课表/考试/成绩（凭据不落盘）"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.gdutday.data.gdut.tools.VerifyLogin")
    workingDir = rootProject.projectDir
    // Windows 控制台默认是 GBK，中文输出会变乱码。强制子进程与 Gradle 都用 UTF-8。
    defaultCharacterEncoding = "UTF-8"
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Djavax.net.ssl.trustStoreType=Windows-ROOT")
}

tasks.register<JavaExec>("dumpFixtures") {
    group = "verification"
    description = "用真实会话抓取 jxfw 原始响应落成 .real fixture（输出 build/dump-fixtures/）"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.gdutday.data.gdut.tools.DumpFixtures")
    workingDir = rootProject.projectDir
    defaultCharacterEncoding = "UTF-8"
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}
