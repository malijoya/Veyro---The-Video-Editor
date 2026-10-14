import com.android.build.api.dsl.LibraryExtension
import com.editor.veyro.buildlogic.VeyroBuildConfig
import com.editor.veyro.buildlogic.configureAndroidCommon
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** Android library modules (data, media, designsystem, features). */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")

        extensions.configure<LibraryExtension> {
            configureAndroidCommon(this)
            lint.targetSdk = VeyroBuildConfig.TARGET_SDK
            testOptions.targetSdk = VeyroBuildConfig.TARGET_SDK
        }
    }
}
