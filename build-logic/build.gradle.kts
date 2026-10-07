plugins {
    `kotlin-dsl`
}

dependencies {
    // Provided at runtime by the root build's plugins block, so each plugin is loaded once.
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.mavenPublish.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("sharedLibrary") {
            id = "io.github.cdsap.infoprocess.shared-library"
            implementationClass = "io.github.cdsap.infoprocess.buildlogic.SharedLibraryPlugin"
        }
    }
}
