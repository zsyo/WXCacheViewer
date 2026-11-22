pluginManagement {
    repositories {
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
        // 只用官方仓库：CI 上阿里云镜像偶发 502，Gradle 会把它记为解析失败并拖垮 lint 工具依赖。
        // 国内本机如需加速，可在 ~/.gradle/init.gradle.kts 里自行加镜像，不要写进仓库影响 CI。
        google()
        mavenCentral()
    }
}

rootProject.name = "WXCacheViewer"
include(":app")
