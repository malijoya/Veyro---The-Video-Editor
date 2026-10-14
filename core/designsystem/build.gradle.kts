plugins {
    alias(libs.plugins.veyro.android.library)
    alias(libs.plugins.veyro.android.compose)
}

android {
    namespace = "com.editor.veyro.core.designsystem"
}

dependencies {
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.ui)
}
