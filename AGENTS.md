# unimined (repo)

Gradle plugin suite: unified Minecraft modding environment, 2.0 modular
rewrite. Kotlin DSL throughout. Each `unimined-*` dir is a subproject in
this single Gradle build. Workspace-level context: `../AGENTS.md`.
Durable state: `../Docs/progress-2.0-completion.md` (RESUME block +
checklist), `../Docs/plan.md` (2.0 spec — status block is kept current),
active megaplan in `~/.devin/plans/` (see `../.devin/active-plan` if
present).

## Concept → file index

| You want to change… | Look at |
|---|---|
| Loader DSL (`minecraft { sourceSet("x") { loader(n,v) } }`), env model | `unimined-minecraft/.../MinecraftExtension.kt`, `MinecraftEnvironment.kt` |
| Mapping spec/`mappings {}` DSL, source materialization | `unimined-mapping/.../MappingsSpecImpl.kt`, `internal/MappingResolver.kt`, `sources/` (Builtin/File/Maven providers) |
| Mapping read providers (`uml` format dispatch) | `unimined-mapping/.../MappingProviderRegistry.kt`, `uml/UmlMappingProvider.kt` (only MappingProvider impl — UML is the universal reader) |
| Remap pipeline (`remap { }`, step specs) | `unimined-remap/.../RemapExtension.kt`, step spec classes; engines under `unimined-remap/remapper-*` |
| Access widening (AW/AT/CT rewrite) | `unimined-remap/remapper-access/.../AccessRemapStepType.kt`, `ApplyClassTweakerStepType.kt` + specs in `unimined-remap/.../AccessRemapStepSpec.kt` |
| Per-env classpath/transform wiring | `unimined-transform/.../internal/EnvironmentClasspathWiring.kt`, `JarProcessorWiring.kt`, `ProductionJarWiring.kt` |
| Fabric-family loaders (fabric/quilt/legacyfabric/babric/ornithe) | `unimined-transform/transform-fabric/.../internal/FabricEnvironmentWiring.kt`, `FabricLoaderDescriptors.kt` (coords+repos+capabilities), `FabricModAnalysis.kt` (fmj/installer.json reading) |
| Forge (userdev/FG/binpatch eras) | `unimined-transform/transform-forge/.../internal/Forge*Wiring.kt`, `ForgeModAnalysis.kt` |
| Jar-mod era | `unimined-transform/transform-jarmod/` |
| Run configs (`runs { }`), `runClient`, auth | `unimined-runs/.../RunsExtension.kt`, `MinecraftRunTask.kt`, `AuthSpec.kt` (auth core lives in sibling `mc-launcher` project) |
| Production jars, mod packaging | `unimined-mod/.../internal/ModPackagingWiring.kt`, `unimined-transform/.../ProductionJarWiring.kt` |
| Publishing (`unimined<Env>` components → maven) | `unimined-publish/.../PublishVariantWiring.kt`, `PublishExtension.kt` |
| IDE emitters | `unimined-ide/.../emitters/` (idea/eclipse/vscode); sync hooks via `unimined-api/.../spi/IdeSyncTasks.kt` (`contribute(project, taskProvider)` rides `genIdeRuns` — cheap file emitters only, no config resolution) |
| Init templates (`unimined-init` plugin + `unimined-init` cli gen) | `unimined-init/` (`src/probedSpec` = VersionProbe), `unimined-cli/` |
| Adapters (forgix jar merge, jvmdg downgrader, expectplatform, patchbase, modpublisher, stonecutter) | `unimined-adapters/adapter-*/` |
| Diff/splice dispatch (largest refactor hotspot, ~900 lines) | `unimined-transform/.../DiffDispatchSplicer.kt` |
| Launch verification fixtures | `testing/<fixture>/` + `testing/launch-verify.sh` (see header for xvfb usage); dep-remap artifact checks: `testing/interop-verify.sh` |

## Conventions (enforced by review + tests)

- **Plugin ids**: `xyz.wagyourtail.unimined.<name>`; adapters:
  `xyz.wagyourtail.unimined.adapter-<name>`.
- **Config-cache contract** (`../Docs/plan.md` §CC): no `Project`/
  `Configuration` held by tasks; task inputs via providers only; no
  `tasks.create` (use `register`); no `System.getenv` captured at
  configuration; no eager `.get()`/`.orNull` on resolution-triggering
  providers at configuration time — `afterDslEval` is the sanctioned
  post-DSL hook, but even inside it, wiring must not resolve
  configurations or parse mapping data (execution-time providers only).
- **Task types**: every `@TaskAction` class carries
  `@DisableCachingByDefault` (no `@CacheableTask` in the repo).
- **Excludes propagate through `extendsFrom`**: mod buckets exclude
  loader coords so mod transitives can't duplicate the env loader —
  any conf extending those buckets inherits the exclusion for its whole
  graph. Route the real loader jar in as a *file* dep where the view
  needs it (see `fabricModAnalysis` in FabricEnvironmentWiring).
- **Specs serialize**: step-spec objects (`*StepSpec`) are contributed
  during DSL eval and serialize at transform registration — plain
  value fields only, no `Provider`/`Project` refs.
- **Test source sets**: `src/testGradle/` = ProjectBuilder/TestKit
  wiring tests (JUnit5, `project.evaluate()` to fire `afterDslEval`);
  `src/probedSpec/` (unimined-init) = probe spec tests. Fixtures in
  `testing/` pin era-specific old Gradle — do not bump them.
- **Tests must not hit live mavens**: serve artifacts from a local
  `exclusiveContent` repo + `metadataSources { artifact() }` dummy jars
  (`PK 05 06` EOCD stub) — see FabricEnvironmentWiringTest's `serve()`.
- **Docs**: `docs/` (MkDocs Material, `mkdocs build --strict` must stay
  clean), nav in `mkdocs.yml`. User-facing DSL changes need doc updates.

## Verify

```bash
./gradlew build                                   # full gate (~15min)
./gradlew :<module>:testGradle                    # wiring tests
./gradlew :unimined-transform:transform-fabric:testGradle
mkdocs build --strict                             # docs (run from repo root)
testing/launch-verify.sh <fixture> <task> <secs> <success-re> [fail-re]
```

Config-cache smoke: run a fixture task twice; second run must reuse the
entry with zero unimined resolution/mapping work.
