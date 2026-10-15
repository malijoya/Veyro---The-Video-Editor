plugins {
    alias(libs.plugins.veyro.android.feature)
}

android {
    namespace = "com.editor.veyro.feature.editor"
}

dependencies {
    implementation(projects.data.project)
    implementation(projects.core.timeline)
    implementation(projects.media.playback)
}
