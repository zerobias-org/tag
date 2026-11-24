pluginManagement {
    // Use local build-tools if available (dev), otherwise pull from GitHub Packages Maven (CI)
    val localBuildTools = file("../../org/util/packages/build-tools")
    if (localBuildTools.exists()) {
        includeBuild(localBuildTools)
    }
    repositories {
        maven {
            url = uri("https://maven.pkg.github.com/zerobias-org/util")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: "zerobias-org"
                password = System.getenv("READ_TOKEN") ?: System.getenv("NPM_TOKEN") ?: System.getenv("GITHUB_TOKEN") ?: ""
            }
        }
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        id("zb.workspace") version "1.+"
        id("zb.base") version "1.+"
        id("zb.content") version "1.+"
    }
}

rootProject.name = "tags"

// Auto-discover tag packages under package/.
// A directory is a tag package if it contains build.gradle.kts.
// The per-package build.gradle.kts is a one-line marker:
//   plugins { id("zb.content") }
// — same pattern as vendor/module. Project names mirror filesystem:
//   package/zerobias/global-tags → :zerobias:global-tags
val packageDir = file("package")
if (packageDir.exists()) {
    packageDir.walkTopDown()
        .filter { it.name == "build.gradle.kts" }
        .forEach { buildFile ->
            val moduleDir = buildFile.parentFile
            val relativePath = moduleDir.relativeTo(packageDir).path
            val projectPath = relativePath.replace(File.separatorChar, ':')

            include(projectPath)
            project(":$projectPath").projectDir = moduleDir
        }
}
