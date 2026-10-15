// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.aboutlibraries) apply false
}

// F-Droid buildserver runs `unset CI` before building, so also check for /home/vagrant
val isCI = System.getenv("CI") != null || System.getProperty("user.home") == "/home/vagrant"
if (!isCI) {
    // One directory per checkout, named after its path under the home
    // directory: worktrees sharing one overwrote each other's generated code,
    // and a branch then compiled against another branch's Hilt bindings.
    // debug/_lib.sh names it the same way.
    val home = System.getProperty("user.home")
    val checkout = rootDir.canonicalPath.removePrefix("$home/").replace('/', '_')
    val localBuildRoot = file("$home/.tigerduck-build").resolve(checkout)
    rootProject.layout.buildDirectory.set(localBuildRoot.resolve(rootProject.name))
    subprojects {
        layout.buildDirectory.set(localBuildRoot.resolve(name))
    }
}

