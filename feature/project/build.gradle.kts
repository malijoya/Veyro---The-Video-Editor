plugins {
    alias(libs.plugins.veyro.android.feature)
}

android {
    namespace = "com.editor.veyro.feature.project"
}

dependencies {
    implementation(projects.data.project)
    implementation(projects.media.import)
    implementation(projects.media.playback)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
}
