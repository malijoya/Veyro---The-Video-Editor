// Top-level build file. Module configuration lives in build-logic convention plugins.
// Declaring the Kotlin plugins here puts one Kotlin Gradle plugin version (libs.versions.kotlin)
// on the build classpath. It replaces the older KGP that AGP's built-in Kotlin would otherwise pull in.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
