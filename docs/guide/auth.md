# Auth

Run configurations carry a typed `auth { }` spec — each run decides
how its session is produced. 2.0 has no project-global `runs.auth`:
per-run, provider-backed, and nothing token-shaped ever lands on the
configuration model or in the config cache.

```kotlin
runs {
    named("client") {
        auth { microsoft() }
    }
    named("server") {
        auth { offline("DevServer") }
    }
}
```

## Verbs

| Verb | Session produced |
|---|---|
| `offline(name)` | Offline-mode session — deterministic UUID from the player name (default) |
| `microsoft()` | Microsoft account — stored credential + silent refresh |
| `custom(provider)` | Any `AuthProvider` — the extension point for third-party auth (Ely.by, launchers) |
| `injected(prefix)` | Session read from `UNIMINED_AUTH_*` env vars at execution — the CI path |

`username = "x"` on a run is the back-compat shorthand for
`auth { offline("x") }` — an explicit `auth` spec wins over it.

## A default for every run

`runs { auth { } }` configures the default applied to *all* runs —
per-run `auth`/`username` values always win:

```kotlin
runs {
    auth { microsoft() }         // everything logs in...
    named("server") {
        auth { offline("Srv") }  // ...except the server
    }
}
```

## Microsoft login

Run `loginMicrosoft` once on a machine with a browser — it prints the
device-code URL + code, completes the MSA → Xbox → Minecraft chain,
and stores the credential. Runs then consume the store with a silent
refresh; they never prompt mid-launch — a missing credential fails
fast with the remediation in the message.

Store: `<unimined global cache>/auth/credentials.json`, written
`0600`, one serialized session per username.

## Gradle properties

| Property | Default | Purpose |
|---|---|---|
| `unimined.auth.username` | — | Account selector for `loginMicrosoft` and for `microsoft()` runs when more than one user is stored |
| `unimined.auth.encrypt` | `false` | Opt-in AES-256-encrypted store, key from the OS keyring via `java-keyring` (loaded reflectively — add `com.github.javakeyring:java-keyring` to the build classpath). **No plaintext fallback**: encrypt on + no keyring = explicit failure |
| `unimined.auth.storeCredentials` | `true` | `false` reads+refreshes stored credentials in memory only — nothing is written back |

## CI / headless

```kotlin
runs.named("client") { auth { injected() } }
```

reads `UNIMINED_AUTH_ACCESS_TOKEN` (required) plus optional
`UNIMINED_AUTH_USERNAME`/`_UUID`/`_XUID`/`_USER_TYPE` at execution —
provision them from the runner's secret store. Alternatively run
`:loginMicrosoft` locally and ship the resulting `credentials.json`
(which still resolves through the same `unimined.auth.*` props).

## Notes

- Sessions materialize inside the run task's execution — access
  tokens never appear in task inputs, the config cache, or logs.
- `auth { injected() }` never writes to the credential store.
- Old single-player-era launchers passed session details as
  `user_properties` — the resolver still substitutes it; Microsoft
  sessions leave it empty.
