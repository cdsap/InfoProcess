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
        // Test-only: the fake Develocity plugins (plugin-support tests, plugin-test-fixtures) compile against the real API interfaces.
        gradlePluginPortal {
            content { includeModule("com.gradle", "develocity-gradle-plugin") }
        }
    }
}

rootProject.name = "InfoProcess"
include("core", "collector-gradle", "collector-kotlin", "collector-test", "collector-gc", "plugin", "integration-tests")
include(":plugin-support")
include(":jvm-process-report")
include(":plugin-test-fixtures")

// Local checkout of build-observability-schema whose `:core` replaces the Maven Central
// build-observability-core, e.g. -PgbosCoreBuild=../build-observability-schema (see docs/releasing.md).
providers.gradleProperty("gbosCoreBuild").orNull?.let { gbosCoreBuild ->
    includeBuild(gbosCoreBuild) {
        dependencySubstitution {
            substitute(module("io.github.cdsap:build-observability-core")).using(project(":core"))
        }
    }
}
