# Auth

Unimined provides a way to authenticate in your run configs.
The recommended way is to use [gradle properties/env vars](https://docs.gradle.org/current/userguide/build_environment.html#sec:gradle_configuration_properties).

## Enabling

You may want to consider putting them in the [global properties file](https://docs.gradle.org/current/userguide/directory_layout.html#dir:gradle_user_home)

```properties
unimined.auth.enabled=true
unimined.auth.username=myUsername
```

you only need to specify username if you want to use multiple different accounts, otherwise it will default to the first
account in the cache.

Auth is also accessible under [`runs.auth`](Run-Config.md).
for more information, see [`AuthConfig`](https://unimined.wagyourtail.xyz/unimined/%version%/api-docs/unimined/xyz.wagyourtail.unimined.api.runs.auth/-auth-config/index.html)

## Details

Enabling auth will open a web-browser to prompt for login when launching, and will store the token in a cache.

This cache is stored in the unimined global cache ([`GRADLE_USER_HOME`](https://docs.gradle.org/current/userguide/directory_layout.html#dir:gradle_user_home)`/cache/unimined/auth.json.enc`)
and is encrypted using a key stored to the system keyring.

Due to OS limitations, the keyring may be fully accessible to other java applications,
or even other applications entirely. see [java keyring's docs](https://github.com/javakeyring/java-keyring?tab=readme-ov-file#security-concerns)
for more info.

Other libraries, such as [DevAuth](https://github.com/DJtheRedstoner/DevAuth) and even some minecraft clients, simply store these tokens
in a plaintext file, so it's probably not a big deal.

If you are concerned, you can disable caching tokens entirely by setting `unimined.auth.storeCredentials` to `false`.

Authentication is backed by the [MinecraftAuth](https://github.com/RaphiMC/MinecraftAuth) library.
