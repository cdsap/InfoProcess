plugins {
    kotlin("jvm") version "2.0.21" apply false
}

group = "io.github.cdsap"
version = providers.gradleProperty("infoProcessVersion").orElse("1.0.0-SNAPSHOT").get()

subprojects {
    group = rootProject.group
    version = rootProject.version

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(17)
        }
        dependencies {
            "testImplementation"(kotlin("test"))
            "testImplementation"("junit:junit:4.13.2")
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }
}

