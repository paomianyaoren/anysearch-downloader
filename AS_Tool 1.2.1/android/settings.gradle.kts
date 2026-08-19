pluginManagement {
    repositories {
        // 国内网络如果 google()/mavenCentral() 连不上，取消下面两行注释并注释掉上面的源
        // maven("https://maven.aliyun.com/repository/google")
        // maven("https://maven.aliyun.com/repository/central")
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
        // 国内网络可取消下面两行注释
        // maven("https://maven.aliyun.com/repository/google")
        // maven("https://maven.aliyun.com/repository/central")
        google()
        mavenCentral()
    }
}

rootProject.name = "AnySearchAndroid"
include(":app")
