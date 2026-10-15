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
    // The overlay painter, so text being edited on screen is drawn by the same code as preview and export.
    implementation(projects.media.render)
    // The image picker for stickers.
    implementation(libs.androidx.activity.compose)
}
