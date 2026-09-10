// ==============================================================================
// 仓库配置。
//
// ## 为什么把阿里云镜像放在 google() 之前
//
// 本项目的目标用户和开发者都在中国大陆，而 `dl.google.com` 在这里的表现是
// **能连上但会 Read timed out**（不是被墙，是慢/丢包），且失败集中在大文件上：
// `intellij-core-32.4.0.jar`、`kotlin-compiler-32.4.0.jar`（各约 50MB，
// Android Lint 的 `extractDebugAnnotations` 任务需要它们）。
// 实测一次 `assembleDebug` 因为这两个包超时而失败，重试耗时 11 分钟。
//
// 阿里云的 `maven.aliyun.com/repository/google` 是 dl.google.com 的完整镜像，
// 国内访问稳定。放在前面意味着：命中镜像就不走 google()，未命中（镜像同步滞后时）
// 会自动回落到 google()，所以**不会牺牲任何可用性**。
//
// 注意 `content {}` 过滤器：没有它，Gradle 会拿每个坐标去问每个仓库，
// 反而增加往返次数。限定 group 之后只有匹配的仓库会被查询。
//
// 若你在海外或镜像不可用，把 aliyun 那两段删掉即可，其余配置无需改动。
// ==============================================================================
pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google") {
            name = "aliyunGoogle"
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        maven("https://maven.aliyun.com/repository/gradle-plugin") {
            name = "aliyunGradlePlugin"
        }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google") {
            name = "aliyunGoogle"
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        maven("https://maven.aliyun.com/repository/public") {
            name = "aliyunPublic"
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "gdutday-android"

// ---- 纯 JVM 模块：无 Android 依赖，可直接跑 JVM 单元测试 ----
// 学校协议逆向层（登录加密 / 课表解析）全部在这里，是整个项目风险最高、
// 也最容易被验证的部分，所以刻意做成纯 JVM 模块，测试不需要模拟器。
include(":core-model")
include(":core-common")
include(":data-gdut")

// ---- Android 基础模块 ----
include(":core-network")
include(":core-database")
include(":core-datastore")
include(":core-ui")

// ---- 数据聚合 ----
include(":data-repository")

// ---- 功能模块 ----
include(":feature-auth")
include(":feature-schedule")
include(":feature-grade")
include(":feature-settings")

// ---- 桌面插件 ----
include(":widget")

// ---- 应用壳 ----
include(":app")
