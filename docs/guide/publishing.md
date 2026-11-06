# Publishing

Unimined's publish host (`xyz.wagyourtail.unimined.publish`) turns each
bound environment's outgoing artifacts into an adhoc component named
`unimined<Env>` (`uniminedMain` for `main`). Destination adapters pick
those components up; none of them auto-publish `components.java`.

```kotlin
plugins {
    id("xyz.wagyourtail.unimined")
    id("xyz.wagyourtail.unimined.publish")
}
```

Auxiliary environments (`datagen()`/`gametest()`) are test harnesses —
they never produce components or publications.

## uniminedPublish { }

```kotlin
uniminedPublish {
    javaPublication()                 // opt-in ordinary `java` component
    environment("main") {
        groupId = "com.example"
        artifactId = "examplemod-fabric"
        version = "1.0.0"
    }
}
```

Coordinate values override the publication's coordinates per
environment.

## Maven

`xyz.wagyourtail.unimined.publish-maven` creates one
`MavenPublication` per `unimined<Env>` component and applies
`maven-publish`. Repositories and credentials are plain Gradle
`publishing {}` config:

```kotlin
publishing {
    repositories {
        maven {
            url = uri("https://maven.example/releases")
            credentials(PasswordCredentials::class)
        }
    }
}
```

Each publication carries the environment's dev/prod jars and variant
metadata (Gradle module metadata included). The loader-merge merged
artifact is intentionally not part of the Maven path — it belongs to
ModPublisher uploads.

## ModPublisher

`xyz.wagyourtail.unimined.publish-modpublisher` feeds artifacts and
release metadata into
[`com.hypherionmc.modutils:modpublisher`](https://github.com/HypherionMC/modpublisher)'s
`publisher { }` when that plugin is applied (it stays `compileOnly` —
no ModPublisher classes load unless you apply it).

```kotlin
uniminedPublishModpublisher {
    apiKeys {
        curseforge.set(providers.environmentVariable("CURSE_TOKEN"))
        modrinth.set(providers.environmentVariable("MODRINTH_TOKEN"))
    }
    curseID.set("1234")
    modrinthID.set("abc")
    versionType.set("release")
    gameVersions.set(listOf("1.20.1"))
    loaders.set(listOf("fabric", "forge"))
    mergedArtifact.set(layout.buildDirectory.file("libs/mymod-merged.jar"))
    // or, without a merged jar:
    // perLoaderArtifacts.put("forge", <prod jar provider>)
    debug.set(true)   // dry-run: builds the upload plan, uploads nothing
}
```

`mergedArtifact` wins over `perLoaderArtifacts`; dev artifacts are
never uploadable.

### Manual verification (live upload)

Automated coverage (ProjectBuilder dry-run tests) asserts the config
lands on `publisher { }` — it does not upload. Verifying a real
release still needs a human pass:

- [ ] `publishMod` with `debug.set(true)` and a real merged jar —
      inspect the dry-run plan (files, version, loaders, gameVersions).
- [ ] Real upload to a staging/draft Modrinth or CurseForge project
      with scoped tokens via env vars — confirm version, changelog,
      loaders, game versions, and additional files on the platform UI.
- [ ] Confirm API keys come from providers only (never literals in
      the build file) and never appear in logs.

## Loader-jar merging (forgix)

`xyz.wagyourtail.unimined.mod-loader-merge` declares `loaderMerge { }`
and `mergeLoaderJars`; engines are `ServiceLoader` plugins on the
buildscript classpath. Forgix ships as
`xyz.wagyourtail.unimined:unimined-mod-loader-merge-forgix`:

```kotlin
buildscript {
    dependencies {
        classpath("xyz.wagyourtail.unimined:unimined-mod-loader-merge-forgix:<version>")
    }
}

loaderMerge {
    engine.set("forgix")
    from(
        "fabric" to tasks.named("remapFabricJar").flatMap { it.archiveFile },
        "forge" to tasks.named("remapForgeJar").flatMap { it.archiveFile },
    )
}
```

Task-output providers are inferred — inputs build before the merge
runs. The merged jar is published on the consumable `mergedElements`
configuration (classifier `merged`) for publishers.
