# unimined-cli

`unimined` generates complete modding environments — the single entry
point for new projects. Binaries ship per-platform (native `.kexe`,
fat jar, Node build).

```bash
unimined new mymod --group com.example --version 1.20.1 --loader fabric=0.16.0
unimined init                       # in an existing directory
```

Highlights:

- **multiloader** — repeat `--loader`; add `--modules` for the
  common+per-loader subproject layout
- **products** — `--product bta|reindev|nsss|custom:<name>`
- **eras** — `--jarmod`, `--jars split|merged`, `--side client|server|merged`,
  era-aware forge entrypoints
- **escape hatches** — `--mappings "<dsl>"`, `--extra "<line>"`,
  `--var key=val`, `--no-wrapper`, `--include-build`, `--toolchain`

Full flag list: [CLI Reference](../reference/cli.md).
