import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.attributes.java.TargetJvmEnvironment
import org.gradle.work.DisableCachingByDefault
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.GregorianCalendar
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins { kotlin("jvm") }

/*
 * The combined-plugins suite (src/test/.../CombinedPluginsTest.kt) runs TestKit builds whose plugins resolve from
 * build/local-repo, a Maven repository this project writes before the tests run, the way consumers resolve the
 * libraries from Maven Central and plugins from the Plugin Portal. TestKit's withPluginClasspath is never used, so
 * Gradle's own classloader structure (parent-first from settings to build scripts) decides which library copy a
 * plugin sees; the version guard depends on exactly that.
 *
 * The repository holds:
 * - plugin-support, jvm-process-report and plugin-test-fixtures at sharedLibsVersion,
 * - plugin-support and jvm-process-report at 1.0.0, 1.1.0 and 2.0.0 (same classes, the plugin-support version resource
 *   rewritten, plus the test-only LibrarySkewProbe, whose 1.1.0 variant adds a method),
 * - build-observability-core when -PgbosCoreBuild substitutes it (otherwise the TestKit builds resolve it from Central),
 * - the fixture plugins from fixture-plugins/ with their plugin markers.
 *
 * The fixture plugins are source sets of this project rather than separate projects or builds: each compiles against
 * the exact library jar it claims to be built against (the 1.1.0 one links against the 1.1.0-only API), nothing in this
 * build resolves from the repository it is writing, and one task type writes every module.
 */

val sharedLibsVersion = providers.gradleProperty("sharedLibsVersion").get()
val localRepo = layout.buildDirectory.dir("local-repo")
val fixturePluginVersion = "0.0.1"
val libraryVersions = listOf("1.0.0", "1.1.0", "2.0.0")

dependencies {
    testImplementation(project(":plugin"))
    testImplementation(project(":plugin-test-fixtures"))
    testImplementation(gradleTestKit())
    // FakeDevelocity.develocityApiJar copies the Develocity API from the test classpath.
    testImplementation(libs.develocity.testApi)
}

/**
 * Writes one module into a Maven repository layout: `<group>/<artifactId>/<version>/<artifactId>-<version>.pom` and,
 * unless the packaging is `pom`, the jar. Gradle resolves such POM-only modules without Gradle Module Metadata,
 * signatures or checksums, and without the signing that the shared libraries' Maven Central publications require.
 */
@DisableCachingByDefault(because = "Writes into the local repository; copying is cheaper than caching")
abstract class LocalMavenModule : DefaultTask() {
    @get:Input
    abstract val groupId: Property<String>

    @get:Input
    abstract val artifactId: Property<String>

    @get:Input
    abstract val moduleVersion: Property<String>

    @get:Input
    abstract val packaging: Property<String>

    /** Jar entries to write first, as text; they win over entries of the same name in [contents]. */
    @get:Input
    abstract val addedEntries: MapProperty<String, String>

    /** Jars are merged and directories copied, in order; the first entry with a given name wins. */
    @get:Classpath
    abstract val contents: ConfigurableFileCollection

    /** A POM to copy, with `<version>[pomTemplateVersion]</version>` replaced by the module version. */
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val pomTemplate: RegularFileProperty

    @get:Input
    @get:Optional
    abstract val pomTemplateVersion: Property<String>

    /** Dependencies (`group:artifact:version`) of the generated POM when there is no [pomTemplate]. */
    @get:Input
    abstract val pomDependencies: ListProperty<String>

    @get:OutputDirectory
    abstract val moduleDirectory: DirectoryProperty

    @TaskAction
    fun write() {
        val directory = moduleDirectory.get().asFile
        directory.deleteRecursively()
        directory.mkdirs()
        val baseName = "${artifactId.get()}-${moduleVersion.get()}"
        File(directory, "$baseName.pom").writeText(pom())
        if (packaging.get() != "pom") writeJar(File(directory, "$baseName.jar"))
    }

    private fun pom(): String {
        val template = pomTemplate.orNull?.asFile
        if (template != null) {
            return template.readLines()
                .filterNot { it.contains("published-with-gradle-metadata") }
                .joinToString("\n", postfix = "\n")
                .replace("<version>${pomTemplateVersion.get()}</version>", "<version>${moduleVersion.get()}</version>")
        }
        return buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">""")
            appendLine("  <modelVersion>4.0.0</modelVersion>")
            appendLine("  <groupId>${groupId.get()}</groupId>")
            appendLine("  <artifactId>${artifactId.get()}</artifactId>")
            appendLine("  <version>${moduleVersion.get()}</version>")
            appendLine("  <packaging>${packaging.get()}</packaging>")
            if (pomDependencies.get().isNotEmpty()) {
                appendLine("  <dependencies>")
                pomDependencies.get().forEach { notation ->
                    val (group, artifact, version) = notation.split(':')
                    appendLine("    <dependency>")
                    appendLine("      <groupId>$group</groupId>")
                    appendLine("      <artifactId>$artifact</artifactId>")
                    appendLine("      <version>$version</version>")
                    appendLine("    </dependency>")
                }
                appendLine("  </dependencies>")
            }
            appendLine("</project>")
        }
    }

    private fun writeJar(jar: File) {
        val entryTime = GregorianCalendar(1980, 1, 1).timeInMillis
        ZipOutputStream(jar.outputStream().buffered()).use { output ->
            val written = HashSet<String>()
            fun put(name: String, bytes: ByteArray) {
                if (!written.add(name)) return
                output.putNextEntry(ZipEntry(name).also { it.time = entryTime })
                output.write(bytes)
                output.closeEntry()
            }
            addedEntries.get().forEach { (name, text) -> put(name, text.toByteArray()) }
            contents.files.forEach { source ->
                if (source.isDirectory) {
                    source.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach {
                        put(it.relativeTo(source).invariantSeparatorsPath, it.readBytes())
                    }
                } else if (source.isFile) {
                    ZipFile(source).use { zip ->
                        zip.entries().asSequence().filterNot { it.isDirectory }.forEach { put(it.name, zip.getInputStream(it).readBytes()) }
                    }
                }
            }
        }
    }
}

fun camelCase(vararg parts: String): String =
    parts.joinToString("") { part -> part.split('-', '.', ':').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) } }

fun localMavenModule(group: String, artifact: String, version: String, configure: LocalMavenModule.() -> Unit): TaskProvider<LocalMavenModule> =
    tasks.register<LocalMavenModule>("localRepo${camelCase(artifact, version)}") {
        description = "Writes $group:$artifact:$version to the integration-test local repository."
        groupId.set(group)
        artifactId.set(artifact)
        moduleVersion.set(version)
        packaging.set("jar")
        moduleDirectory.set(localRepo.map { it.dir("${group.replace('.', '/')}/$artifact/$version") })
        configure()
    }

fun runtimeArtifacts(name: String, transitive: Boolean, dependency: Any): Configuration =
    configurations.create(name) {
        isCanBeConsumed = false
        isTransitive = transitive
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
            attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
            attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment.STANDARD_JVM))
            attribute(KotlinPlatformType.attribute, KotlinPlatformType.jvm)
        }
    }.also { dependencies.add(name, dependency) }

// ---------------------------------------------------------------- shared libraries

val pluginSupportVersionResource = "io/github/cdsap/pluginsupport/plugin-support-version.properties"

val skewProbe100 = sourceSets.create("skewProbe100") { java.setSrcDirs(listOf("fixture-plugins/library-skew/1.0.0")) }
val skewProbe110 = sourceSets.create("skewProbe110") { java.setSrcDirs(listOf("fixture-plugins/library-skew/1.1.0")) }

val libraryModules = mutableMapOf<Pair<String, String>, TaskProvider<LocalMavenModule>>()

for (library in listOf("plugin-support", "jvm-process-report", "plugin-test-fixtures")) {
    val jar = runtimeArtifacts("${camelCase(library).replaceFirstChar(Char::lowercaseChar)}Jar", transitive = false, project(":$library"))
    val versions = if (library == "plugin-test-fixtures") listOf(sharedLibsVersion) else listOf(sharedLibsVersion) + libraryVersions
    for (version in versions) {
        libraryModules[library to version] =
            localMavenModule("io.github.cdsap", library, version) {
                if (library == "plugin-support" && version != sharedLibsVersion) {
                    addedEntries.put(pluginSupportVersionResource, "version=$version\n")
                }
                contents.from(jar)
                if (library == "plugin-support" && version != sharedLibsVersion) {
                    contents.from(if (version == "1.0.0") skewProbe100.output else skewProbe110.output)
                }
                // The POM the library's Maven Central publication generates, at the version of this module.
                pomTemplate.set(rootProject.layout.projectDirectory.file("$library/build/publications/maven/pom-default.xml"))
                pomTemplateVersion.set(sharedLibsVersion)
                dependsOn(":$library:generatePomFileForMavenPublication")
            }
    }
}

fun libraryJar(library: String, version: String): Provider<RegularFile> =
    libraryModules.getValue(library to version).flatMap { it.moduleDirectory.file("$library-$version.jar") }

// When -PgbosCoreBuild substitutes a local checkout of build-observability-core, the TestKit builds resolve core from
// the local repository too.
if (providers.gradleProperty("gbosCoreBuild").isPresent) {
    val gbosCore = runtimeArtifacts("gbosCore", transitive = true, libs.build.observability.core)
    localMavenModule("io.github.cdsap", "build-observability-core", libs.versions.buildObservabilityCore.get()) {
        contents.from(
            gbosCore.incoming.artifactView {
                componentFilter { it !is ModuleComponentIdentifier || it.module == "build-observability-core" }
            }.files,
        )
        pomDependencies.set(
            gbosCore.incoming.resolutionResult.rootComponent.map { root ->
                val core = root.dependencies.filterIsInstance<ResolvedDependencyResult>().single().selected
                core.dependencies.filterIsInstance<ResolvedDependencyResult>()
                    .filterNot { it.isConstraint }
                    .map { it.requested }
                    .filterIsInstance<ModuleComponentSelector>()
                    .map { "${it.group}:${it.module}:${it.version}" }
            },
        )
    }
}

// ---------------------------------------------------------------- fixture plugins

fun fixturePlugin(name: String, libraries: List<Pair<String, String>>, compileOnly: List<Any>, pomDependencies: List<String>) {
    val sourceSetName = "fixture${camelCase(name)}"
    val sourceSet =
        sourceSets.create(sourceSetName) {
            java.setSrcDirs(emptyList<File>())
            resources.setSrcDirs(listOf("fixture-plugins/$name/src/main/resources"))
        }
    kotlin.sourceSets.named(sourceSetName) { kotlin.setSrcDirs(listOf("fixture-plugins/$name/src/main/kotlin")) }
    dependencies {
        add(sourceSet.compileOnlyConfigurationName, gradleApi())
        libraries.forEach { (library, version) -> add(sourceSet.compileOnlyConfigurationName, files(libraryJar(library, version))) }
        compileOnly.forEach { add(sourceSet.compileOnlyConfigurationName, it) }
    }
    val jar =
        tasks.register<Jar>("${sourceSetName}Jar") {
            from(sourceSet.output)
            archiveFileName.set("$name.jar")
            destinationDirectory.set(layout.buildDirectory.dir("fixture-plugin-jars"))
        }
    localMavenModule("io.github.cdsap.fixture", name, fixturePluginVersion) {
        contents.from(jar)
        this.pomDependencies.set(pomDependencies)
    }
    val pluginId = "io.github.cdsap.fixture.$name"
    localMavenModule(pluginId, "$pluginId.gradle.plugin", fixturePluginVersion) {
        packaging.set("pom")
        this.pomDependencies.set(listOf("io.github.cdsap.fixture:$name:$fixturePluginVersion"))
    }
}

val gbosCoreNotation = "io.github.cdsap:build-observability-core:${libs.versions.buildObservabilityCore.get()}"

fixturePlugin(
    "settings-only",
    libraries = listOf("plugin-support" to "1.0.0"),
    compileOnly = listOf(libs.build.observability.core),
    pomDependencies = listOf("io.github.cdsap:plugin-support:1.0.0", gbosCoreNotation),
)
fixturePlugin(
    "dual-entry",
    libraries = listOf("plugin-support" to "1.1.0"),
    compileOnly = emptyList(),
    pomDependencies = listOf("io.github.cdsap:plugin-support:1.1.0"),
)
fixturePlugin(
    "gradle-spec",
    libraries = listOf("plugin-support" to "1.0.0", "jvm-process-report" to "1.0.0"),
    compileOnly = listOf(libs.cdsap.jdkToolsParser),
    pomDependencies = listOf("io.github.cdsap:jvm-process-report:1.0.0"),
)
fixturePlugin(
    "kotlin-spec",
    libraries = listOf("plugin-support" to "1.1.0", "jvm-process-report" to "1.1.0"),
    compileOnly = listOf(libs.cdsap.jdkToolsParser),
    pomDependencies = listOf("io.github.cdsap:jvm-process-report:1.1.0"),
)

// The fixture plugins run inside Gradle 8.2 too, on its embedded Kotlin 1.8.20 standard library (KTD11).
tasks.withType<KotlinCompile>().matching { it.name.startsWith("compileFixture") }.configureEach {
    compilerOptions {
        languageVersion.set(@Suppress("DEPRECATION") KotlinVersion.KOTLIN_2_0)
        apiVersion.set(@Suppress("DEPRECATION") KotlinVersion.KOTLIN_2_0)
        freeCompilerArgs.addAll("-Xjdk-release=17", "-Xsuppress-version-warnings")
    }
}

// ---------------------------------------------------------------- suite

val localRepoModules = tasks.withType<LocalMavenModule>()

tasks.register("publishLocalRepo") {
    description = "Writes every module the combined-plugins suite resolves to build/local-repo."
    dependsOn(localRepoModules)
}

tasks.test {
    inputs.files(localRepoModules)
        .withPropertyName("localRepo")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dintegration.localRepo=${localRepo.get().asFile.absolutePath}") })
    systemProperty("integration.fixturesVersion", sharedLibsVersion)
    systemProperty("integration.fixturePluginVersion", fixturePluginVersion)
    systemProperty("integration.develocityVersion", libs.versions.develocityTestApi.get())
    // The Gradle lane, for example -Dintegration.gradleVersion=8.2; without it TestKit runs the wrapper's version.
    providers.systemProperty("integration.gradleVersion").orNull?.let { systemProperty("integration.gradleVersion", it) }
    systemProperty("integration.defaultGradleVersion", gradle.gradleVersion)
}
