plugins {
    id("io.github.cdsap.infoprocess.shared-library")
}

sharedLibrary { artifactId("plugin-test-fixtures") }

description = "TestKit helpers and an offline fake Develocity plugin for testing the cdsap Gradle plugins"

dependencies {
    // None of these reach the POM. Consumers already have TestKit and the Gradle API on their test classpath, and the
    // Gradle runtime provides the API to the fake plugins inside a TestKit build.
    compileOnly(gradleApi())
    compileOnly(gradleTestKit())
    // The fakes implement the real Develocity / Gradle Enterprise interfaces. Consumers bring the jar themselves
    // (FakeDevelocity.develocityApiJar), so the real plugin descriptors never compete with the fake ones.
    compileOnly(libs.develocity.testApi)

    testImplementation(gradleApi())
    testImplementation(gradleTestKit())
    testImplementation(libs.develocity.testApi)
}
