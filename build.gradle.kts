plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.vanniktech.mavenPublish) apply false
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
            "testImplementation"(rootProject.libs.junit)
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }
}
