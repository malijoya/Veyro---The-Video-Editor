plugins {
    alias(libs.plugins.veyro.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.editor.veyro.data.project"
}

dependencies {
    api(projects.core.model)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlinx.coroutines.test)
}
