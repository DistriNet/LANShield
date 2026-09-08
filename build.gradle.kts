// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
    id("com.google.devtools.ksp") version "2.3.6" apply false
    id("com.google.dagger.hilt.android") version "2.60.1" apply false
    alias(libs.plugins.cyclonedx.bom)

//     id("com.google.gms.google-services") version "4.5.0" apply false
//     id("com.google.firebase.crashlytics") version "3.0.8" apply false
}

// Aggregate CycloneDX SBOM for the whole build: ./gradlew cyclonedxBom
// Output: build/reports/cyclonedx/bom.json
// The app module configures which configurations are scanned (see app/build.gradle.kts).
val appVersionName = provider {
    project(":app").extensions
        .getByType(com.android.build.api.dsl.ApplicationExtension::class.java)
        .defaultConfig.versionName
}

tasks.cyclonedxBom {
    projectType = org.cyclonedx.model.Component.Type.APPLICATION
    componentName = "LANShield"
    componentVersion = appVersionName
    xmlOutput.convention(null as org.gradle.api.file.RegularFile?)
}

tasks.cyclonedxDirectBom {
    projectType = org.cyclonedx.model.Component.Type.APPLICATION
    componentName = "LANShield"
    componentVersion = appVersionName
    xmlOutput.convention(null as org.gradle.api.file.RegularFile?)
}
