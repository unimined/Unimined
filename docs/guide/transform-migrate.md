# Transform & Migrate

## Transform hooks

`unimined { transform { } }` wires environment transforms — jar merge,
access transforms/wideners, patch application — the surface the
`transform-*` modules plug into.

## Migrate

`unimined { migrate { } }` and `migrateMappings(env) { }` handle
updating a project across MC/mappings versions — re-emitting sources,
moving mappings forward, and fixing up entrypoints for API changes.
