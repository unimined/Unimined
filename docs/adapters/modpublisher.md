# ModPublisher adapter

Plugin id: `xyz.wagyourtail.unimined.publish-modpublisher`

Feeds your build's production artifacts + metadata into
[ModPublisher](https://github.com/Hyperspace-Logistics-Group/ModPublisher)'s
own `publisher { }` DSL as **conventions** — the adapter owns
selection and plumbing only; ModPublisher's engine is applied by you.
There is **no adapter DSL**: `publisher { }` stays the single surface
for what can't be inferred, and anything you set there always wins.

## Project layout

```text
my-mod/
├── build.gradle.kts
└── src/
    ├── main/         # common env → common prod jar
    ├── fabric/       # fabric env → remapFabricJar
    └── forge/        # forge env → remapForgeJar
```

## build.gradle.kts

```kotlin
plugins {
    id("xyz.wagyourtail.unimined") version "..."
    id("com.hypherionmc.modutils.modpublisher") version "2.2.4"
    id("xyz.wagyourtail.unimined.publish-modpublisher") version "..."
}

unimined {
    minecraft {
        version = "1.20.1"
        loader("fabric", "0.16.0")

        sourceSet("forge") {
            version = "1.20.1"
            loader("forge", "47.2.0")
        }
    }
}

// artifacts + loaders + gameVersions + version already conventioned —
// write publisher{} only for creds, platform ids, and release metadata
publisher {
    apiKeys {
        curseforge(providers.environmentVariable("CURSE_TOKEN").get())
        modrinth(providers.environmentVariable("MODRINTH_TOKEN").get())
    }
    curseID.set("1234")
    modrinthID.set("abc")
    versionType.set("release")
}
```

## What conventions in

| `publisher` field | Convention |
|---|---|
| `artifact` | the forgix `mergedElements` jar when `adapter-forgix` is applied, else the first bound env's production jar (`remap<Env>Jar` when it remaps, else `jar`) |
| `additionalFiles` | the remaining per-loader prod jars |
| `loaders` | bound envs' `loaderSpec.name`s |
| `gameVersions` | bound envs' `version`s |
| `version` | `project.version` |

Selection: the merged artifact wins when present and **suppresses the
per-loader jars**; aux envs (gametest/datagen) are excluded; dev jars
are never selectable.

## Requirements

- Java 17+
- ModPublisher applied separately — the adapter is plumbing, not a
  redistribution
- API keys live in `publisher.apiKeys { }` as ModPublisher expects —
  resolve them from env vars/gradle properties; they are never logged
  by the adapter
