plugins {
    alias(libs.plugins.veyro.android.library)
}

android {
    namespace = "com.editor.veyro.media.render"
}

/*
 * Media3 objects built from the render plan, shared by preview
 * (CompositionPlayer in :media:playback) and export (Transformer in
 * :media:export), so both play exactly the same composition.
 */
dependencies {
    api(projects.core.timeline)
    // Composition appears in this module's public API.
    api(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.common)
}
