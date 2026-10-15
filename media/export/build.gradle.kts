plugins {
    alias(libs.plugins.veyro.android.library)
}

android {
    namespace = "com.editor.veyro.media.export"
}

dependencies {
    // ExportPlan and its parts appear in this module's public API.
    api(projects.core.timeline)
    implementation(projects.media.render)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.common)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.kotlinx.coroutines.test)
    // The instrumented test plays the exported file with ExoPlayer.
    androidTestImplementation(libs.androidx.media3.exoplayer)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
