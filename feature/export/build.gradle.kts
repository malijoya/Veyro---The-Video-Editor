plugins {
    alias(libs.plugins.veyro.android.feature)
}

android {
    namespace = "com.editor.veyro.feature.export"
}

dependencies {
    implementation(projects.data.project)
    implementation(projects.core.timeline)
    implementation(projects.media.export)
    implementation(libs.androidx.activity.compose)
}
