pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        // Test-only: plugin-support compiles its fake Develocity plugins against the real API interfaces.
        gradlePluginPortal {
            content { includeModule("com.gradle", "develocity-gradle-plugin") }
        }
    }
}

rootProject.name = "InfoProcess"
include("core", "collector-gradle", "collector-kotlin", "collector-test", "collector-gc", "plugin", "integration-tests")
include(":plugin-support")
