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

rootProject.name = "QuantuMobileLauncher"
// QML_HOST_ONLY=1 builds just the AFK PC program (no Android SDK needed), e.g. on the Windows release runner.
if (System.getenv("QML_HOST_ONLY") != "1") include(":app")
include(":afk-core")
include(":afk-host")
