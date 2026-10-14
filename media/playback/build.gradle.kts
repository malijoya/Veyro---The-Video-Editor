plugins {
    alias(libs.plugins.veyro.android.library)
    alias(libs.plugins.veyro.android.compose)
}

android {
    namespace = "com.editor.veyro.media.playback"
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.designsystem)
    // Player appears in this module's public API (PreviewController.player).
    api(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.exifinterface)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
