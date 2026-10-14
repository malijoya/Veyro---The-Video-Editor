plugins {
    alias(libs.plugins.veyro.android.library)
}

android {
    namespace = "com.editor.veyro.media.importer"
}

dependencies {
    api(projects.core.model)
    implementation(projects.data.project)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.kotlinx.coroutines.android)
}
