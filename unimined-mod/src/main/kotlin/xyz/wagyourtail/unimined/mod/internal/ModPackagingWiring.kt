package xyz.wagyourtail.unimined.mod.internal

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.jvm.tasks.Jar
import xyz.wagyourtail.unimined.minecraft.MinecraftEnvironment
import xyz.wagyourtail.unimined.minecraft.MinecraftExtension
import xyz.wagyourtail.unimined.mod.ModExtension
import xyz.wagyourtail.unimined.mod.ModMetadataEmitterRegistry
import xyz.wagyourtail.unimined.mod.task.GenerateModMetadataTask

/**
 * Wires `generate<Env>ModMetadata` per bound environment: captures the
 * `mod {}` model into task inputs, registers the task's output directory
 * as a resources srcDir on the environment's source set, and hands the
 * source set's own resource dirs to the task for merge/discovery.
 *
 * The task scans this env's compiled classes plus its declared gametest
 * envs' for gametest entrypoints and mixin interface injections; manual
 * resource files it merges into generated output are excluded from
 * `processResources` via the task's `mergedPathsFile` output.
 */
internal object ModPackagingWiring {

    fun wire(project: Project, environment: MinecraftEnvironment, sourceSet: SourceSet) {
        val environmentId = environment.environmentId
        val taskName = if (environmentId == "main") {
            "generateModMetadata"
        } else {
            "generate${environmentId.cap()}ModMetadata"
        }

        val outDir = project.layout.buildDirectory
            .dir("generated/unimined/mod/$environmentId")

        // manifest merge file lives OUTSIDE outputDir — that's a
        // resources srcDir, so a META-INF/MANIFEST.MF under it would
        // leak into the jar as a resource
        val manifestOut = project.layout.buildDirectory
            .file("generated/unimined/mod/$environmentId.mf")

        // manual resource files the task merged into generated output —
        // excluded from processResources so content ships exactly once
        val mergedOut = project.layout.buildDirectory
            .file("generated/unimined/mod/$environmentId.merged.txt")

        val task = project.tasks.register(taskName, GenerateModMetadataTask::class.java) { t ->
            t.group = "unimined"
            t.description =
                "Generates mod metadata and mixin configs for the '$environmentId' environment"
            t.environmentId.set(environmentId)
            // aux envs (datagen/gametest children) emit nothing — the
            // parent's metadata references their classes, and a second
            // same-id metadata file would collide on the classpath.
            // Read at execution: env binding fires before datagen()/
            // gametest() mark the env aux.
            t.auxEnv.set(environment.isAuxEnv)
            t.outputDir.set(outDir)
            t.manifestFile.set(manifestOut)
            t.mergedPathsFile.set(mergedOut)

            val mod = project.extensions.getByType(ModExtension::class.java)
            val minecraft = project.extensions.getByType(MinecraftExtension::class.java)

            t.metadata.set(project.provider { mod.snapshot() })
            t.mixinConfigs.set(
                project.provider { mod.mixins.map { it.snapshot() } }
            )
            t.loaderName.set(environment.loaderSpec.map { it.name })
            t.minecraftVersion.set(environment.version)
            t.side.set(environment.side.map { it.name })
            t.devNamespace.set(minecraft.devNamespace(environmentId))
            t.prodNamespaces.set(minecraft.modSourceNamespaces(environmentId))
            t.namedNamespaces.set(minecraft.namedNamespaces(environmentId))
            // plain paths, not the live FileCollection — the generated
            // srcDir below would otherwise feed the task's own output back
            // as an input and self-depend
            t.resourceDirs.from(project.provider {
                sourceSet.resources.srcDirs - outDir.get().asFile
            })
            // own classes + declared gametest envs' classes — resolved
            // lazily since `gametest {}` may be configured after this
            // env's wiring runs
            t.classesDirs.from(project.provider {
                val dirs = LinkedHashSet(sourceSet.output.classesDirs.files)
                val sourceSets = project.extensions
                    .getByType(SourceSetContainer::class.java)
                environment.gametestEnvs.forEach { ge ->
                    sourceSets.findByName(ge.environmentId)
                        ?.output?.classesDirs?.files?.let { dirs += it }
                }
                dirs
            })
            // the dirs above are compileJava outputs — Gradle can't
            // infer the producer through the provider, so declare it
            // (implicit-input usage is a hard failure on Gradle 9).
            // compileJavaTaskName, not classesTaskName — `classes`
            // pulls processResources which consumes this task's output
            t.dependsOn(project.provider {
                val sourceSets = project.extensions
                    .getByType(SourceSetContainer::class.java)
                listOfNotNull(sourceSet.compileJavaTaskName) +
                        environment.gametestEnvs.mapNotNull { ge ->
                            sourceSets.findByName(ge.environmentId)
                                ?.compileJavaTaskName
                        }
            })
            t.declaredAccessFiles.from(
                environment.accessWideners, environment.accessTransformers,
                environment.accessFileOverrides.map { specs ->
                    specs.map { java.io.File(it.path) }
                }
            )
            t.customAccessFiles.set(environment.accessFileOverrides)
            // loader packaging facts — version feeds manifest attrs
            // (`Fabric-Loader-Version`), manifestAttributes carries
            // loader-contributed extras (ornithe `Calamus-Generation`)
            t.loaderVersion.set(
                environment.loaderSpec.map { it.version })
            t.manifestAttributes.set(environment.manifestAttributes)
            val emitters = ModMetadataEmitterRegistry()
            // AT→AW conversion needs the dev-namespace tree — attach
            // the env's mapping bundle only when the loader consumes
            // AW AND AT-family inputs are declared, so the common
            // AW-on-fabric shape never pays for mapping resolution at
            // processResources
            val consumesAw = environment.loaderSpec.orNull?.name?.let {
                emitters.has(it) && emitters.select(it).accessFormat == "aw"
            } ?: false
            if (consumesAw && (!environment.accessTransformers.isEmpty ||
                    environment.accessFileOverrides.get().isNotEmpty())) {
                t.accessBundle.from(
                    minecraft.mappingBundle(
                        environmentId, "access-at2aw",
                        minecraft.devNamespace(environmentId),
                        minecraft.devNamespace(environmentId)
                    )
                )
            }
        }

        // publish the env's resolved mod id for run-config `{modid}`
        // templating (fabric client gametest's
        // `-Dfabric.client.gametest.modid`, datagen's `--modid`).
        // `mod.id` wins; a hand-written `*.mod.json` resource's `id`
        // is the fallback. unimined-runs reads this extra property —
        // keep the key in sync with RunsWiring's MOD_ID_PREFIX.
        val modExt = project.extensions.getByType(ModExtension::class.java)
        project.extensions.getByType(
            org.gradle.api.plugins.ExtraPropertiesExtension::class.java
        )["unimined.modId.$environmentId"] =
            project.provider { modExt.snapshot().id ?: scanModId(sourceSet) }

        sourceSet.resources.srcDir(task.flatMap { it.outputDir })

        // manifest-attribute loaders (foxloader's ModId/ModMain set)
        // merge the generated manifest into the env jar — prod jars
        // mirror `jar.manifest` downstream so they inherit it
        project.tasks.named(
            sourceSet.jarTaskName,
            Jar::class.java
        ) { jar ->
            jar.manifest.from(manifestOut)
            // generated resources carry META-INF/MANIFEST.MF for the
            // dev classpath (exploded-dir FML scan) — the packed jar
            // gets those attrs through the manifest merge above, so
            // the file copy itself must not ship as an entry
            jar.exclude("META-INF/MANIFEST.MF")
            jar.dependsOn(task)
        }

        // manual metadata files the task merged must not ALSO ship from
        // their source location — the merged copy in outputDir is the
        // single packaged form. eachFile runs at copy time, after the
        // metadata task has produced mergedPathsFile.
        project.tasks.withType(org.gradle.language.jvm.tasks.ProcessResources::class.java)
            .matching { it.name == sourceSet.processResourcesTaskName }
            .configureEach { pr ->
                val merged = task.flatMap { it.mergedPathsFile }.map { f ->
                    f.asFile.takeIf { it.isFile }
                        ?.readLines()?.filter { it.isNotBlank() }?.toSet()
                        ?: emptySet()
                }
                pr.eachFile { details ->
                    if (details.file.absolutePath in merged.get()) {
                        details.exclude()
                    }
                }
            }
    }

    private fun String.cap(): String = replaceFirstChar(Char::uppercase)

    /** First `id` field found in a `*.mod.json` under the source set's
     *  resource roots — the manual-metadata fallback for the published
     *  `unimined.modId.<env>` provider. */
    private fun scanModId(sourceSet: SourceSet): String? {
        val json = kotlinx.serialization.json.Json
        for (dir in sourceSet.resources.srcDirs) {
            val d = dir as? java.io.File ?: continue
            if (!d.isDirectory) continue
            java.nio.file.Files.walk(d.toPath()).use { stream ->
                val hit = stream
                    .filter { it.fileName.toString().endsWith(".mod.json") }
                    .filter(java.nio.file.Files::isRegularFile)
                    .map { f ->
                        runCatching {
                            json.parseToJsonElement(
                                java.nio.file.Files.readString(f)
                            ).jsonObject["id"]?.jsonPrimitive?.content
                        }.getOrNull()
                    }
                    .filter { it != null }
                    .findFirst()
                if (hit.isPresent) return hit.get()
            }
        }
        return null
    }

}
