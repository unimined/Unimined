package xyz.wagyourtail.unimined.mclauncher

import java.util.*

/**
 * A materialized launch session — only ever produced at execution time,
 * never persisted to the configuration model.
 */
data class Session(
    val username: String,
    val uuid: String,
    val accessToken: String,
    val userType: String = "msa",
    val clientId: String = "",
    val xuid: String = "",
    val userProperties: Map<String, String> = emptyMap()
)

/**
 * Supplies a [Session] at execution time. Implementations: [OfflineAuth]
 * (always available), [MicrosoftAuth], [InjectedSessionAuth], custom.
 * Serializable because providers sit on the configuration model
 * (`RunConfig.auth.provider`) — the config cache round-trips them.
 * Sessions themselves are never serialized.
 */
fun interface AuthProvider : java.io.Serializable {

    /** Materialize a session — may prompt or hit the network. */
    fun session(): Session

}

/** Offline-mode auth — deterministic uuid from the player name. */
class OfflineAuth(private val username: String) : AuthProvider {

    override fun session(): Session = Session(
        username = username,
        uuid = UUID.nameUUIDFromBytes("OfflinePlayer:$username".toByteArray(Charsets.UTF_8))
            .toString().replace("-", ""),
        accessToken = "0",
        userType = "legacy"
    )

}
