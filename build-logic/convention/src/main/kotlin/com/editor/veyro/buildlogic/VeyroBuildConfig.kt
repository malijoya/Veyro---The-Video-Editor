package com.editor.veyro.buildlogic

import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

/** SDK and JVM levels shared by every module. Change them here, never per module. */
object VeyroBuildConfig {
    const val COMPILE_SDK = 37
    const val TARGET_SDK = 37

    /**
     * API 24 (Android 7.0). Media3 Transformer and MediaCodec work there, but
     * some features (HDR, some codecs, the system photo picker) need newer
     * API levels and must be feature-gated at runtime.
     */
    const val MIN_SDK = 24

    val JAVA_VERSION = JavaVersion.VERSION_17
}

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")
