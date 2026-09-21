pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "ebook2tts"
include(":core")
include(":engine")
// :app 已由 Flutter 工程接管（app/android 为独立 Gradle 构建，并 include 本仓 :core/:engine），
// 根构建不再包含它，避免两套 Gradle 构建互相打架。
