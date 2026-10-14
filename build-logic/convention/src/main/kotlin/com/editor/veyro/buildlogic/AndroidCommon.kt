package com.editor.veyro.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Settings shared by application and library modules.
 *
 * AGP 9 compiles Kotlin itself (built-in Kotlin), so no Kotlin Android plugin is
 * applied. The Kotlin JVM target follows compileOptions.targetCompatibility.
 */
internal fun Project.configureAndroidCommon(extension: CommonExtension) {
    extension.apply {
        compileSdk {
            version = release(VeyroBuildConfig.COMPILE_SDK)
        }
        defaultConfig.apply {
            minSdk = VeyroBuildConfig.MIN_SDK
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
        compileOptions.apply {
            sourceCompatibility = VeyroBuildConfig.JAVA_VERSION
            targetCompatibility = VeyroBuildConfig.JAVA_VERSION
        }
    }

    dependencies {
        add("testImplementation", libs.findLibrary("junit").get())
        add("androidTestImplementation", libs.findLibrary("androidx-junit").get())
        add("androidTestImplementation", libs.findLibrary("androidx-test-runner").get())
    }
}
