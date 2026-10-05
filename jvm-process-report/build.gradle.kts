import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult

plugins {
    id("io.github.cdsap.infoprocess.shared-library")
}

sharedLibrary { artifactId("jvm-process-report") }

description = "Spec-driven Gradle and Kotlin daemon report (jstat/jinfo, console table, Build Scan values) shared by the cdsap Gradle plugins"

dependencies {
    // Provided by the Gradle runtime that loads the consuming plugin.
    compileOnly(gradleApi())
    api(project(":plugin-support"))
    // Process and TypeProcess are part of this library's API.
    api(libs.cdsap.jdkToolsParser)
    implementation(libs.picnic)
    implementation(libs.build.observability.core)

    testImplementation(gradleApi())
    testImplementation(gradleTestKit())
    testImplementation(project(":plugin-test-fixtures"))
    // The plugin-test-fixtures fakes implement its API interfaces (FakeDevelocity.develocityApiJar).
    testImplementation(libs.develocity.testApi)
}

// Classpath injected into TestKit builds with GradleRunner.withPluginClasspath; test resources hold the fixture
// plugin descriptor.
val testKitPluginClasspath = objects.fileCollection().from(
    sourceSets.test.map { it.output.resourcesDir!! },
    sourceSets.test.map { it.output.classesDirs },
    sourceSets.main.map { it.output },
    configurations.runtimeClasspath,
)

tasks.test {
    inputs.files(testKitPluginClasspath)
        .withPropertyName("testKitPluginClasspath")
        .withNormalizer(ClasspathNormalizer::class)
    jvmArgumentProviders.add(
        CommandLineArgumentProvider { listOf("-DprocessReport.testKitClasspath=${testKitPluginClasspath.asPath}") },
    )
    // ProjectBuilder defines classes in the application classloader.
    jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")
}

/**
 * Fails when a forbidden module is a dependency of the published POM or anywhere in the resolved runtime classpath:
 * consumers must no longer get `commandline-value-source` or the `gradle-enterprise-gradle-plugin` it leaked (R12).
 */
abstract class VerifyForbiddenDependencies : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val pom: RegularFileProperty

    @get:Input
    abstract val runtimeModules: ListProperty<String>

    @get:Input
    abstract val forbiddenModules: ListProperty<String>

    @TaskAction
    fun verify() {
        val pomText = pom.get().asFile.readText()
        val inPom = forbiddenModules.get().filter { "<artifactId>${it.substringAfter(':')}</artifactId>" in pomText }
        val inRuntime = forbiddenModules.get().filter { it in runtimeModules.get() }
        check(inPom.isEmpty() && inRuntime.isEmpty()) {
            "Forbidden dependencies: published POM $inPom, runtime classpath $inRuntime"
        }
    }
}

val verifyForbiddenDependencies = tasks.register<VerifyForbiddenDependencies>("verifyForbiddenDependencies") {
    group = "verification"
    description = "Checks that the published POM and runtime classpath have no commandline-value-source or Gradle Enterprise plugin."
    dependsOn(tasks.named("generatePomFileForMavenPublication"))
    pom.set(layout.buildDirectory.file("publications/maven/pom-default.xml"))
    forbiddenModules.set(listOf("io.github.cdsap:commandline-value-source", "com.gradle:gradle-enterprise-gradle-plugin"))
    runtimeModules.set(
        configurations.runtimeClasspath.flatMap { it.incoming.resolutionResult.rootComponent }.map { root ->
            val seen = LinkedHashSet<ResolvedComponentResult>()
            val pending = ArrayDeque(listOf(root))
            while (pending.isNotEmpty()) {
                val component = pending.removeFirst()
                if (seen.add(component)) {
                    component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { pending.add(it.selected) }
                }
            }
            seen.mapNotNull { component -> component.moduleVersion?.let { "${it.group}:${it.name}" } }
        },
    )
}
tasks.named("check") { dependsOn(verifyForbiddenDependencies) }
