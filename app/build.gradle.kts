import com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    id("kotlin-kapt")
    id("com.google.dagger.hilt.android")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

android {
    namespace = "org.distrinet.lanshield"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.distrinet.lanshield"
        minSdk = 29
        targetSdk = 34
        versionCode = 5
        versionName = "0.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        externalNativeBuild {
            cmake {
                cppFlags += ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")

            configure<CrashlyticsExtension> {
                // Enable processing and uploading of native symbols to Firebase servers.
                // By default, this is disabled to improve build speeds.
                // This flag must be enabled to see properly-symbolicated native
                // stack traces in the Crashlytics dashboard.
                nativeSymbolUploadEnabled = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.material3.adaptive.navigation.suite.android)
    implementation(libs.androidx.runtime.livedata)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.common)
    implementation(libs.androidx.hilt.work)
    implementation(libs.volley)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.accompanist.permissions)
    implementation(libs.hilt.android)
    kapt(libs.hilt.android.compiler)


    implementation(libs.androidx.room.runtime)
    annotationProcessor(libs.androidx.room.compiler)

    // To use Kotlin annotation processing tool (kapt)
    kapt(libs.androidx.room.compiler)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.crashlytics.ndk)
    implementation(libs.google.firebase.analytics)

}

// Allow references to generated code
kapt {
    correctErrorTypes = true
}
