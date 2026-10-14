plugins {
    alias(libs.plugins.veyro.android.application)
    alias(libs.plugins.veyro.android.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.editor.veyro"

    defaultConfig {
        applicationId = "com.editor.veyro"
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            // R8 is enabled in the release-hardening milestone, once keep rules
            // for Media3/Room/serialization have been checked.
            optimization {
                enable = false
            }
        }
    }
}

dependencies {
    implementation(projects.core.designsystem)
    implementation(projects.core.model)
    implementation(projects.data.project)
    implementation(projects.media.import)
    implementation(projects.media.playback)
    implementation(projects.feature.editor)
    implementation(projects.feature.library)
    implementation(projects.feature.project)
    implementation(projects.feature.settings)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.navigation.suite)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.kotlinx.coroutines.android)

    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
