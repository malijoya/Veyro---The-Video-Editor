plugins {
    alias(libs.plugins.veyro.android.application)
    alias(libs.plugins.veyro.android.compose)
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

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)

    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
