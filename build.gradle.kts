// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
    id("com.google.devtools.ksp") version "2.3.6" apply false
    id("com.google.dagger.hilt.android") version "2.59.2" apply false

//    id("com.google.gms.go[DELETEME]ogle-services") version "4.4.4" apply false
//    id("com.google.fire[DELETEME]base.crashlytics") version "3.0.7" apply false
}