import com.editor.veyro.buildlogic.VeyroBuildConfig
import com.editor.veyro.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * Pure Kotlin modules with no Android dependency (`:core:*`). They hold the
 * platform-independent editing model so it can later move to Kotlin
 * Multiplatform for iOS without being rewritten.
 */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")

        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = VeyroBuildConfig.JAVA_VERSION
            targetCompatibility = VeyroBuildConfig.JAVA_VERSION
        }
        extensions.configure<KotlinJvmProjectExtension> {
            explicitApi()
            compilerOptions {
                jvmTarget.set(JvmTarget.fromTarget(VeyroBuildConfig.JAVA_VERSION.toString()))
            }
        }

        dependencies {
            add("testImplementation", libs.findLibrary("junit").get())
        }
    }
}
