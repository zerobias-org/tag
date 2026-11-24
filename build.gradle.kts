import com.zerobias.buildtools.content.SchemaPrimitives

plugins {
    id("zb.workspace")
}

group = "com.zerobias.content"

// ════════════════════════════════════════════════════════════
// Tag content validator — owned by this repo.
//
// Philosophy (per Chris/Kevin): the dataloader is the source of truth
// for schema rules (UUID format on tag ids, Nmtoken regex on tag-type
// names, description non-blank, etc.). Re-validating those here just
// creates drift risk — when the dataloader tightens a rule, the gate
// gets stale.
//
// Tag's payload shape is unique among content repos: each subdirectory
// of the package is a tag-type, each *.yml file inside (except
// index.yml) is a single tag with `id` + `description`. Tags don't
// have logos.
//
// This validator only enforces things the dataloader CANNOT or DOES NOT
// check:
//
//   1. Filesystem ↔ npm ↔ zerobias-block triangulation. Dataloader
//      reads zerobias.package but never the npm `name` field, and has
//      no view of the on-disk directory layout. A wrong npm name
//      publishes under the wrong package and only surfaces in prod.
//
//   2. Repo-wide unique tag UUIDs (separate :validateUniqueTagIds task
//      registered below). Dataloader sees one tag at a time;
//      collisions only surface when the second one tries to load
//      to the same DB row.
//
// Everything else (UUID format, Nmtoken regex on tag-type names,
// description non-blank, etc.) is delegated to the dataloader running
// in testIntegrationDataloader during gate.
// ════════════════════════════════════════════════════════════
extra["contentValidator"] = { proj: org.gradle.api.Project ->
    val projectDir = proj.projectDir
    val tag = "[tag-validator] ${proj.path}"

    require(projectDir.resolve("package.json").isFile) {
        "$tag package.json missing in ${projectDir.path}"
    }

    // ── 1. Filesystem ↔ npm ↔ zerobias-block triangulation ──
    // Tag packages live two levels deep — package/<scope>/<name>/.
    // The npm name and zerobias.package both derive from those two
    // segments via tag's specific formula:
    //   npm name        = @zerobias-org/tag-<scope>-<name>
    //   zerobias.package = <scope>.<name>.tag   (note the .tag suffix
    //                                             distinguishes from
    //                                             other artifact types)
    val tagName = projectDir.name
    val scope = projectDir.parentFile.name
    val pkgDoc = SchemaPrimitives.parseJson(projectDir.resolve("package.json"))
    SchemaPrimitives.requirePackageIdentity(
        pkgDoc,
        expectedNpmName = "@zerobias-org/tag-$scope-$tagName",
        expectedZerobiasPackage = "$scope.$tagName.tag",
        field = "$tag package.json",
    )
    require(SchemaPrimitives.getPath(pkgDoc, "zerobias.import-artifact") == "tag" ||
            SchemaPrimitives.getPath(pkgDoc, "auditmation.import-artifact") == "tag") {
        "$tag zerobias.import-artifact must be 'tag'"
    }

    proj.logger.lifecycle("$tag: scope=$scope name=$tagName")
}

// ════════════════════════════════════════════════════════════
// :validateUniqueTagIds — repo-wide cross-cut.
//
// Walks every *.yml file under package/ (excluding index.yml and
// node_modules), extracts the `id` field, and fails if two tags
// share the same UUID. Cannot be done by the dataloader (it processes
// one tag at a time); only surfaces in prod when the second tag tries
// to overwrite the first. Wired as a dependency of every per-package
// validateContent so any gate run picks it up.
// ════════════════════════════════════════════════════════════
val validateUniqueTagIds by tasks.registering {
    group = "verification"
    description = "Fail if two tag YAMLs share the same id UUID"

    val packageDir = layout.projectDirectory.dir("package").asFile
    inputs.files(
        fileTree(packageDir) {
            include("**/*.yml")
            exclude("**/index.yml")
            exclude("**/node_modules/**")
        }
    )

    doLast {
        val byId = mutableMapOf<String, MutableList<String>>()
        packageDir.walkTopDown()
            .onEnter { it.name != "node_modules" }
            .filter { it.isFile && it.name.endsWith(".yml") && it.name != "index.yml" }
            .forEach { f ->
                val doc = try {
                    SchemaPrimitives.parseYaml(f)
                } catch (e: Exception) {
                    logger.warn("[validateUniqueTagIds] skipping unparseable ${f.relativeTo(rootDir)}: ${e.message}")
                    return@forEach
                }
                val id = (doc["id"] as? String)?.lowercase() ?: return@forEach
                byId.getOrPut(id) { mutableListOf() }.add(f.relativeTo(rootDir).path)
            }

        val collisions = byId.filterValues { it.size > 1 }
        if (collisions.isNotEmpty()) {
            val report = collisions.entries.joinToString("\n") { (id, paths) ->
                "  $id\n    " + paths.joinToString("\n    ")
            }
            throw GradleException(
                "[validateUniqueTagIds] duplicate tag ids across the repo:\n$report"
            )
        }
        logger.lifecycle("[validateUniqueTagIds] ${byId.size} unique tag ids across ${byId.values.sumOf { it.size }} tag files")
    }
}

subprojects {
    tasks.matching { it.name == "validateContent" }.configureEach {
        dependsOn(rootProject.tasks.named("validateUniqueTagIds"))
    }
}

val projectPaths by tasks.registering {
    group = "info"
    description = "Output project-to-directory mappings for tooling (used by zbb CLI)"
    doLast {
        subprojects.filter { it.buildFile.exists() }.forEach { p ->
            println("${p.path}=${p.projectDir.relativeTo(rootDir)}")
        }
    }
}

val changedModules by tasks.registering {
    group = "info"
    description = "List tag packages changed since last version tag"
    doLast {
        val lastTag = try {
            providers.exec {
                commandLine("git", "describe", "--tags", "--abbrev=0")
            }.standardOutput.asText.get().trim()
        } catch (e: Exception) {
            logger.warn("No version tags found -- listing all tag packages as changed")
            null
        }

        val diffArgs = if (lastTag != null) {
            listOf("git", "diff", "--name-only", lastTag, "HEAD")
        } else {
            listOf("git", "ls-files")
        }

        val result = providers.exec {
            commandLine(diffArgs)
        }.standardOutput.asText.get()

        val changed = result.lines()
            .filter { it.startsWith("package/") }
            .map { it.split("/").drop(1).dropLast(1).joinToString("/") }
            .distinct()
            .filter { it.isNotEmpty() }

        changed.forEach { println(it) }
    }
}
