import org.gradle.api.Project
import java.util.Properties

/**
 * The release version from version.properties at the repo root (key "version" or "versionCode");
 * tools/build-module.sh may override it with -PhgVersionName / -PhgVersionCode.
 */
fun Project.hgVersion(key: String): String {
    val override = findProperty(if (key == "version") "hgVersionName" else "hgVersionCode")
    if (override is String) return override
    val props = Properties()
    rootProject.file("version.properties").reader().use { props.load(it) }
    return props.getProperty(key) ?: error("version.properties has no $key")
}
