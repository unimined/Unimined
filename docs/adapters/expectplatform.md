# ExpectPlatform adapter

Plugin id: `xyz.wagyourtail.unimined.adapter-expectplatform`

Bridges [ExpectPlatform](https://github.com/unimined/ExpectPlatform)
into Unimined. ExpectPlatform's own `insertAgent(JavaExecSpec)`
can't attach to Unimined runs (they aren't `JavaExecSpec`s), and
dev-vs-prod needs *different* handling — the adapter covers both:

- **Dev runs** get the javaagent + `-Dexpect.*` sysprops, including
  `-Dexpect.side` so the agent strips the other side's
  `@Environment`-annotated members at classload (dev classes are
  raw; loaders scan bytes before transforms — issue #4)
- **The prod jar** gets ExpectPlatform's transform applied
  *statically* as a JAR-surface step — `@ExpectPlatform` redirects,
  `@PlatformOnly` filtering, and annotation remaps are resolved
  into the artifact, so `runProd*` runs and shipped jars need no
  agent
- **`modImplementation`/`modRuntimeOnly`** are marked for
  ExpectPlatform's artifact transform, so resolved mod jars (e.g.
  a shared `common` module) arrive already platform-transformed

Requires **both** `xyz.wagyourtail.unimined.expect-platform` and
`xyz.wagyourtail.unimined` applied — the adapter activates when
both are present.

## Project layout

The canonical multi-loader setup — the `@ExpectPlatform` stub lives
in the shared env, one `<pkg>/<platform>/<Class>Impl` class per
loader env:

```text
my-mod/
├── build.gradle.kts
└── src/
    ├── main/java/com/example/
    │   └── Platform.java              # @ExpectPlatform stubs
    ├── fabric/java/com/example/fabric/
    │   └── PlatformImpl.java          # real impl for "fabric"
    └── forge/java/com/example/forge/
        └── PlatformImpl.java          # real impl for "forge"
```

## build.gradle.kts

```kotlin
plugins {
    id("xyz.wagyourtail.unimined") version "..."
    id("xyz.wagyourtail.unimined.expect-platform") version "1.2.0"
    id("xyz.wagyourtail.unimined.adapter-expectplatform") version "..."
}

unimined {
    minecraft {
        version = "1.20.1"
        loader("fabric", "0.16.0")
        expectPlatform {
            platform("fabric") // the @ExpectPlatform platform name
        }

        sourceSet("forge") {
            version = "1.20.1"
            loader("forge", "47.2.0")
            expectPlatform { platform("forge") }
        }
    }
}

dependencies {
    "mainImplementation"(expectPlatform.annotationsDep)
}
```

## Code

```java
// src/main/java/com/example/Platform.java — shared env
public final class Platform {

    // Body is replaced at transform time by
    // com/example/<platform>/PlatformImpl.loaderName()
    @ExpectPlatform
    public static String loaderName() {
        throw new AssertionError();
    }

    // Present only on the listed platforms — filtered out elsewhere
    @PlatformOnly("forge")
    public static void forgeOnlyThing() {}
}

// src/fabric/java/com/example/fabric/PlatformImpl.java
public final class PlatformImpl {
    public static String loaderName() { return "fabric"; }
}

// src/forge/java/com/example/forge/PlatformImpl.java
public final class PlatformImpl {
    public static String loaderName() { return "forge"; }
}
```

`@Environment(EnvType.CLIENT)` marks side-only members with
ExpectPlatform's own annotation — in the prod jar it is remapped to
the loader's side annotation (see the table below), and in dev the
agent strips the other side's members at classload.

## Per-env knobs

`platform(name, remap)` sets `platformName`/`remap` on the env's
`expectPlatform` extension and marks its mod-dep configurations.
All of it resolves lazily — a `common` env's `platform(...)`
propagates to `combineWith` children by convention, and each knob
is per-env overridable:

| Property | Default | Purpose |
|---|---|---|
| `enabled` | `true` | master switch — off: no agent, no jar step |
| `agent` | `true` | off: drop the javaagent + sysprops from runs, keep the static jar transform |
| `platformName` | inherited | the ExpectPlatform platform name |
| `remap` | table + inherited + explicit | annotation-descriptor remaps (see below) |
| `stripAnnotations` | `false` | strip ExpectPlatform's own annotations from output |

## The `@Environment` remap table is filled for you

`remap` defaults to a built-in table for the env's platform — the
same maps ExpectPlatform's README documents:

- `fabric` (and `legacyfabric`/`babric`/`ornithe`) →
  `net.fabricmc.api.Environment`/`EnvType`
- `quilt` → `org.quiltmc.loader.api.minecraft.MinecraftOnlyIn`/`EnvType`
- `forge` → era-picked by `env.version`: ≥1.13
  `net.minecraftforge.api.distmarker.OnlyIn`/`Dist`, 1.8–1.12
  `net.minecraftforge.fml.relauncher.SideOnly`/`Side`, ≤1.7.10
  `cpw.mods.fml.relauncher.SideOnly`/`Side`
- `neoforge` → `net.neoforged.api.distmarker.OnlyIn`/`Dist`
- `cleanroom` → the 1.12-era fml table

Explicit `remap` entries merge **over** the table (table <
inherited env < explicit). Loaders with no side-annotation
convention (vanilla, jarmod, modloader, bukkit family) get none.

## Manual jar step

The same static transform is declared as a JAR-surface verb when
you want it on a specific product instead of env-wide:

```kotlin
jar {
    expectPlatform { platformName.set("fabric") }
}
```

## Requirements

- Java 17+
- `xyz.wagyourtail.unimined.expect-platform` ≥ **1.2.0** for side
  stripping; the agent jar resolves from the applied plugin's
  version
