# CLI Reference

`unimined new <name>` / `unimined init` — generate a modding
environment for any MC version.

```
new <name>  — generate a project in ./<name>
init        — generate in the current directory
```

| Flag | Meaning |
|---|---|
| `--group` | base package group |
| `--version`/`--mc` | MC/product version |
| `--plugin-version` | unimined version to apply |
| `--loader <name=v>` | repeat = multiloader; `v` empty = registry default |
| `--product <p>` | `bta`, `reindev`, `nsss`, `custom:<name>` |
| `--modules` | common + per-loader subproject layout |
| `--dist <path>` | product jar for custom dists |
| `--mappings "<dsl>"` | mappings chain lines |
| `--var k=v` | mappings-scope variables |
| `--side <side>` | `client`, `server`, `merged` |
| `--jarmod` | JarModAgent transforms (modloadermp shape) |
| `--jars split|merged` | MC jar shape for jarmod-era envs |
| `--modid`/`--package`/`--name`/`--mod-version` | mod identity |
| `--metadata dsl` | emit `mod { }` metadata block |
| `--extra "<line>"` | raw `build.gradle.kts` lines |
| `--no-wrapper` | skip gradle-wrapper download |
| `--include-build <path>` | composite `pluginManagement` resolution |
| `--toolchain <n>` | pin the java toolchain |
| `--dsl kotlin|groovy` | emitted DSL flavor |
| `--templates <zip>` / `--template <name>` | extract a bundled project zip |
| `--out <dir>` | output directory |
| `--gradle` | include `gradlew` wrapper files |
