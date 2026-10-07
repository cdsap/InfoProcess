package io.github.cdsap.infoprocess.buildlogic

import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import javax.inject.Inject

internal const val SHARED_LIBS_GROUP = "io.github.cdsap"
private const val SHARED_LIBS_VERSION_PROPERTY = "sharedLibsVersion"
private const val JVM_VERSION = 17

/**
 * Opt-in convention for library modules published to Maven Central as `io.github.cdsap:<module-name>`.
 *
 * ```
 * plugins { id("io.github.cdsap.infoprocess.shared-library") }
 * sharedLibrary { artifactId("plugin-support") }
 * ```
 */
class SharedLibraryPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        pluginManager.apply("com.vanniktech.maven.publish")

        group = SHARED_LIBS_GROUP
        version = providers.gradleProperty(SHARED_LIBS_VERSION_PROPERTY).orNull
            ?: throw GradleException("Gradle property '$SHARED_LIBS_VERSION_PROPERTY' must be set in gradle.properties.")

        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(JVM_VERSION)
            explicitApi()
            compilerOptions {
                // Lowest language/API version accepted by the Kotlin 2.4 compiler; consumers run on old Gradle-embedded stdlibs.
                languageVersion.set(@Suppress("DEPRECATION") KotlinVersion.KOTLIN_2_0)
                apiVersion.set(@Suppress("DEPRECATION") KotlinVersion.KOTLIN_2_0)
                jvmTarget.set(JvmTarget.fromTarget(JVM_VERSION.toString()))
                freeCompilerArgs.addAll("-Xjdk-release=$JVM_VERSION", "-Xsuppress-version-warnings")
            }
            @OptIn(ExperimentalAbiValidation::class)
            abiValidation()
        }

        val mavenPublishing = extensions.getByType<MavenPublishBaseExtension>()
        mavenPublishing.apply {
            publishToMavenCentral()
            signAllPublications()
            pom {
                description.set(provider { project.description ?: "InfoProcess shared library ${project.name}" })
                url.set("https://github.com/cdsap/InfoProcess")
                licenses {
                    license {
                        name.set("The MIT License (MIT)")
                        url.set("https://opensource.org/licenses/MIT")
                        distribution.set("repo")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/cdsap/InfoProcess.git")
                    developerConnection.set("scm:git:ssh://github.com/cdsap/InfoProcess.git")
                    url.set("https://github.com/cdsap/InfoProcess")
                }
                developers {
                    developer {
                        id.set("cdsap")
                        name.set("Inaki Villar")
                    }
                }
            }
        }

        val sharedLibrary = extensions.create<SharedLibraryExtension>("sharedLibrary", path, name, version.toString(), mavenPublishing)
        afterEvaluate {
            if (sharedLibrary.configuredArtifactId == null) {
                throw GradleException("$path applies io.github.cdsap.infoprocess.shared-library but does not declare sharedLibrary { artifactId(\"$name\") }.")
            }
        }

        val moduleMetadata = layout.buildDirectory.file("publications/maven/module.json")
        val verifyJavaTargetMetadata = tasks.register("verifyJavaTargetMetadata") {
            group = "verification"
            description = "Checks that the published Gradle Module Metadata targets Java $JVM_VERSION."
            dependsOn(tasks.named("generateMetadataFileForMavenPublication"))
            inputs.file(moduleMetadata)
            doLast {
                val metadata = moduleMetadata.get().asFile
                check(metadata.isFile) { "Published Gradle Module Metadata was not generated: $metadata" }
                check(metadata.readText().contains("\"org.gradle.jvm.version\": $JVM_VERSION")) {
                    "Published Gradle Module Metadata must target Java $JVM_VERSION: $metadata"
                }
            }
        }
        tasks.named("check") { dependsOn(verifyJavaTargetMetadata) }
    }
}

abstract class SharedLibraryExtension @Inject constructor(
    private val projectPath: String,
    private val projectName: String,
    private val version: String,
    private val mavenPublishing: MavenPublishBaseExtension,
) {
    internal var configuredArtifactId: String? = null
        private set

    /** Publishes this module as `io.github.cdsap:<artifactId>`; must equal the module name. */
    fun artifactId(artifactId: String) {
        check(configuredArtifactId == null) { "$projectPath already declares artifactId '$configuredArtifactId'." }
        require(artifactId == projectName) {
            "$projectPath must publish under its own module name '$projectName', not '$artifactId'."
        }
        configuredArtifactId = artifactId
        mavenPublishing.coordinates(SHARED_LIBS_GROUP, artifactId, version)
        mavenPublishing.pom { name.set(artifactId) }
    }
}
