package xyz.wagyourtail.unimined.mclauncher

/**
 * Session materialized from environment variables — the headless/CI
 * auth path. Variable *names* are all that live on the configuration
 * model; values are read inside [session] at task execution, so no
 * token ever lands in the config cache, task inputs, or logs.
 *
 * Defaults: `UNIMINED_AUTH_ACCESS_TOKEN` (required),
 * `UNIMINED_AUTH_USERNAME`/`_UUID`/`_XUID`/`_USER_TYPE` (optional).
 */
class InjectedSessionAuth(
    private val envPrefix: String = "UNIMINED_AUTH_",
    @Transient private val lookup: (String) -> String? = System::getenv
) : AuthProvider {

    private fun env(key: String) = lookup(envPrefix + key)

    override fun session(): Session {
        val token = env("ACCESS_TOKEN")
            ?: throw IllegalStateException(
                "CI auth needs ${envPrefix}ACCESS_TOKEN in the " +
                    "environment — provision it from the runner's " +
                    "secret store, or use auth { microsoft() } with a " +
                    "stored credential"
            )
        return Session(
            username = env("USERNAME") ?: "CI",
            uuid = (env("UUID") ?: "").replace("-", ""),
            accessToken = token,
            userType = env("USER_TYPE") ?: "msa",
            xuid = env("XUID") ?: ""
        )
    }

}
