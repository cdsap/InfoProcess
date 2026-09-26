plugins {
    `java-gradle-plugin`
    `kotlin-dsl`
    `maven-publish`
}

dependencies {
    implementation(project(":core"))
    implementation(project(":collector-gradle"))
    implementation(project(":collector-kotlin"))
    implementation(project(":collector-test"))
    implementation(project(":collector-gc"))
    testImplementation(kotlin("test"))
    testImplementation(gradleTestKit())
}

gradlePlugin {
    website = "https://github.com/cdsap/InfoProcess"
    vcsUrl = "https://github.com/cdsap/InfoProcess"
    plugins {
        create("infoProcess") {
            id = "io.github.cdsap.infoprocess"
            implementationClass = "io.github.cdsap.infoprocess.plugin.InfoProcessPlugin"
            displayName = "InfoProcess"
            description = "Collect Gradle, Kotlin daemon, test worker, and GC process information"
            tags = listOf("gradle", "process", "build-scan", "gc")
        }
    }
}

