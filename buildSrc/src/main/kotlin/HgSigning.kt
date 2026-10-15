import com.android.build.api.dsl.ApkSigningConfig
import org.gradle.api.GradleException
import org.gradle.api.Project

/**
 * One signing key for every HyperGestures APK (app, notification hooks, overlay).
 *
 * Release builds (CI) pass the release keystore through the environment: HG_KEYSTORE (path),
 * HG_KEYSTORE_PASSWORD, HG_KEY_ALIAS and HG_KEY_PASSWORD. Without HG_KEYSTORE the build signs
 * with the local, git-ignored dev key keystore/hypergestures.jks (README: "Building").
 */
fun ApkSigningConfig.hgSigning(project: Project) {
    val store = System.getenv("HG_KEYSTORE")?.takeIf { it.isNotBlank() }
    if (store != null) {
        fun env(name: String) = System.getenv(name)?.takeIf { it.isNotEmpty() }
            ?: throw GradleException("HG_KEYSTORE is set but $name is not")
        storeFile = project.file(store)
        storePassword = env("HG_KEYSTORE_PASSWORD")
        keyAlias = env("HG_KEY_ALIAS")
        keyPassword = env("HG_KEY_PASSWORD")
    } else {
        storeFile = project.rootProject.file("keystore/hypergestures.jks")
        storePassword = "hypergestures"
        keyAlias = "hypergestures"
        keyPassword = "hypergestures"
    }
}
