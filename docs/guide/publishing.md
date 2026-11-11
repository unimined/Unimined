# Publishing

Unimined's publish host (`xyz.wagyourtail.unimined.publish`) turns each
bound environment's outgoing artifacts into an adhoc component named
`unimined<Env>` (`uniminedMain` for `main`). Destination adapters pick
those components up; none of them auto-publish `components.java`.

=== "Kotlin `build.gradle.kts`"
    ```kotlin
    plugins {
        id("xyz.wagyourtail.unimined")
        id("xyz.wagyourtail.unimined.publish")
    }
    ```

=== "Groovy `build.gradle`"
    ```groovy
    plugins {
        id 'xyz.wagyourtail.unimined'
        id 'xyz.wagyourtail.unimined.publish'
    }
    ```

Auxiliary environments (`datagen()`/`gametest()`) are test harnesses —
they never produce components or publications.

## uniminedPublish { }

=== "Kotlin `build.gradle.kts`"
    ```kotlin
    uniminedPublish {
        javaPublication()                 // opt-in ordinary `java` component
        mavenPublications.set(false)      // opt out of all Maven publications
        environment("main") {
            groupId = "com.example"
            artifactId = "examplemod"
            version = "1.0.0"
        }
    }
    ```

=== "Groovy `build.gradle`"
    ```groovy
    uniminedPublish {
        javaPublication()                 // opt-in ordinary `java` component
        mavenPublications.set(false)      // opt out of all Maven publications
        environment("main") {
            groupId.set("com.example")
            artifactId.set("examplemod")
            version.set("1.0.0")
        }
    }
    ```

`mavenPublications` is on by default: the host applies `maven-publish`
and creates one `MavenPublication` per `unimined<Env>` component.
Set it to `false` for component-only or ModPublisher-only builds —
no publications are created at all (the `publishing { }` extension
still exists for your own use). `javaPublication()` opts the ordinary
`components.java` into a `java` publication — it only takes effect
while `mavenPublications` is enabled, and defaults its artifactId to
`<project>-java` since `uniminedMain` already claims `<project>`.

## Maven

One `MavenPublication` is created per environment component as it
binds — `uniminedMain` → `uniminedMain`, `uniminedFabric` →
`uniminedFabric`, etc. Repositories and credentials are plain Gradle
`publishing {}` config:

=== "Kotlin `build.gradle.kts`"
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

=== "Groovy `build.gradle`"
    ```groovy
    publishing {
        repositories {
            maven {
                url = uri("https://maven.example/releases")
                credentials(PasswordCredentials)
            }
        }
    }
    ```

Coordinates default per environment: `main` publishes as
`<project.name>`; every other environment as `<project.name>-<env>`
(the `name-fabric`/`name-forge` convention) — set
`environment("<env>") { artifactId = ... }` to override. Publications
that would resolve to identical coordinates fail the build.

Each publication carries the environment's dev and production
variants plus sources (Gradle module metadata included): the dev jar
rides custom `unimined-dev-*` usage attributes so plain
`java-api`/`java-runtime` consumers resolve the production variant
unambiguously — request a dev usage explicitly to opt in (see
`PublishAttributes`). `publishDev`/`publishProduction`/`publishDataFiles`
on `environment("<env>")` gate each channel; identity-remap
environments publish a single artifact. The loader-merge merged
artifact is intentionally not part of the Maven path — it belongs to
ModPublisher uploads.

## ModPublisher

`xyz.wagyourtail.unimined.publish-modpublisher` feeds artifacts and
release metadata into
[`com.hypherionmc.modutils:modpublisher`](https://github.com/HypherionMC/modpublisher)'s
`publisher { }` as **conventions** when that plugin is applied (it
stays `compileOnly` — no ModPublisher classes load unless you apply
it). There is no adapter DSL — `publisher { }` is the single surface
for what can't be inferred, and anything you set there wins:

=== "Kotlin `build.gradle.kts`"
    ```kotlin
    publisher {
        apiKeys {
            curseforge(providers.environmentVariable("CURSE_TOKEN").get())
            modrinth(providers.environmentVariable("MODRINTH_TOKEN").get())
        }
        curseID.set("1234")
        modrinthID.set("abc")
        versionType.set("release")
        debug.set(true)   // dry-run: builds the upload plan, uploads nothing
    }
    ```

=== "Groovy `build.gradle`"
    ```groovy
    publisher {
        apiKeys {
            curseforge(providers.environmentVariable("CURSE_TOKEN").get())
            modrinth(providers.environmentVariable("MODRINTH_TOKEN").get())
        }
        curseID.set("1234")
        modrinthID.set("abc")
        versionType.set("release")
        debug.set(true)   // dry-run: builds the upload plan, uploads nothing
    }
    ```

Conventions supplied automatically: `artifact` (the forgix
`mergedElements` jar when `adapter-forgix` is applied, else the first
bound env's production jar — `remap<Env>Jar` when it remaps, else
`jar`), `additionalFiles` (remaining per-loader prod jars), `loaders`
(bound envs' loader names), `gameVersions` (env versions), and
`version` (`project.version`). The merged artifact suppresses the
per-loader jars; aux envs are excluded; dev artifacts are never
uploadable.

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

The `adapter-forgix` adapter (plugin id
`xyz.wagyourtail.unimined.adapter-forgix`) merges per-loader
production jars with Forgix's engine:

=== "Kotlin `build.gradle.kts`"
    ```kotlin
    plugins {
        id("xyz.wagyourtail.unimined.adapter-forgix") version "..."
    }
    ```

=== "Groovy `build.gradle`"
    ```groovy
    plugins {
        id 'xyz.wagyourtail.unimined.adapter-forgix' version '...'
    }
    ```

Every bound environment's production jar feeds `mergeLoaderJars`
automatically (`remap<Env>Jar`, or `jar` for non-remapping envs;
auxiliary environments are excluded), keyed by loader name — most
multi-loader builds need no configuration. Forgix's own plugin isn't
required (its DSL only supports multi-project layouts); when applied,
its `forgix { }` merge configurations feed in too, and
`loaderMerge.from(...)` adds or overrides any input. The merged jar
is published on the consumable `mergedElements` configuration
(classifier `merged`) — the ModPublisher adapter prefers it as the
upload artifact and suppresses the per-loader jars when it exists.
See [adapters/forgix](../adapters/forgix.md).
