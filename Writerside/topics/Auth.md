# Auth

unimined provides a way to authenticate in your run configs.
the recommended way is to use gradle properties/env vars.

```properties
unimined.auth.enabled=true
unimined.auth.username=myUsername
```

This will open a web-browser to prompt for login when configuring and the token isn't cached.

Authentication is backed by the [MinecraftAuth](https://github.com/RaphiMC/MinecraftAuth) library.

Auth is also accessible under [`runs.auth`](Run-Config.md).
for more information, see [AuthConfig](https://unimined.wagyourtail.xyz/unimined/%version%/api-docs/unimined/xyz.wagyourtail.unimined.api.runs.auth/-auth-config/index.html)
