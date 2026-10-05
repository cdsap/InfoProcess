plugins {
    id("io.github.cdsap.infoprocess.shared-library")
}

sharedLibrary { artifactId("plugin-support") }

description = "Develocity bridge, entry dispatcher, console service base and version guard shared by the cdsap Gradle plugins"

dependencies {
    // Provided by the Gradle runtime that loads the consuming plugin.
    compileOnly(gradleApi())
    implementation(libs.develocity.adapters)

    testImplementation(gradleApi())
    testImplementation(gradleTestKit())
    // Compiles the test-only fake Develocity / Gradle Enterprise plugins against the real API interfaces.
    testImplementation(libs.develocity.testApi)
}

// PluginSupportVersion reads this at runtime, so callers always see the version of the jar that is actually loaded.
val generatedVersionResources = layout.buildDirectory.dir("generated/plugin-support-version")
val generatePluginSupportVersion = tasks.register<WriteProperties>("generatePluginSupportVersion") {
    destinationFile.set(generatedVersionResources.map { it.file("io/github/cdsap/pluginsupport/plugin-support-version.properties") })
    property("version", project.version.toString())
}
sourceSets.main {
    resources.srcDir(files(generatedVersionResources).builtBy(generatePluginSupportVersion))
}

// Classpath injected into TestKit builds with GradleRunner.withPluginClasspath. Test resources come first so the
// fake `com.gradle.develocity` / `com.gradle.enterprise` descriptors win over the ones in the real Develocity jar,
// which stays on the classpath because the fakes implement its API interfaces.
val testKitPluginClasspath = objects.fileCollection().from(
    sourceSets.test.map { it.output.resourcesDir!! },
    sourceSets.test.map { it.output.classesDirs },
    sourceSets.main.map { it.output },
    configurations.runtimeClasspath,
    configurations.testRuntimeClasspath.map { classpath ->
        classpath.filter { it.name.startsWith("develocity-gradle-plugin-${libs.versions.develocityTestApi.get()}") }
    },
)

tasks.test {
    inputs.files(testKitPluginClasspath)
        .withPropertyName("testKitPluginClasspath")
        .withNormalizer(ClasspathNormalizer::class)
    jvmArgumentProviders.add(
        CommandLineArgumentProvider { listOf("-DpluginSupport.testKitClasspath=${testKitPluginClasspath.asPath}") },
    )
    systemProperty("pluginSupport.expectedVersion", project.version.toString())
    // ProjectBuilder defines classes in the application classloader.
    jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")
}
