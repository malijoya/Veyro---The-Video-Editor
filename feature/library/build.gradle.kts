plugins {
    alias(libs.plugins.veyro.android.feature)
}

android {
    namespace = "com.editor.veyro.feature.library"
}

dependencies {
    implementation(projects.data.project)
    implementation(libs.androidx.activity.compose)
}
