plugins {
    alias(libs.plugins.veyro.android.feature)
}

android {
    namespace = "com.editor.veyro.feature.settings"
}

dependencies {
    implementation(projects.data.project)
}
