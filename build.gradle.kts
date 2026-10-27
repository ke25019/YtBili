plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
}

// Build output is redirected through `build-out`, a directory junction that
// resolves to D:\Android\build-ytbili.
//
// Why: this machine's Windows Defender real-time engine holds freshly written
// .dex files, which makes R8 fail with "another process is using this file"
// (and presents as a dexing stall). The exclusion that would fix it requires
// administrator rights. Storing the bytes on D: avoids the scan.
//
// Why a junction rather than pointing buildDir straight at D:: KSP rejects
// generated sources whose path has a different filesystem root from the
// project, with "this and base files have different roots". The junction keeps
// the path under H: while the data lives on D:.
val externalBuildRoot = rootProject.layout.projectDirectory.dir("build-out")
if (file("build-out").exists()) {
    allprojects {
        val name = project.path.replace(':', '_').trim('_').ifEmpty { "root" }
        layout.buildDirectory.set(externalBuildRoot.dir(name))
    }
}
