package xyz.wagyourtail.unimined.mclauncher

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/** Per-username serialized `FullJavaSession` persistence —
 *  plaintext ([CredentialStore]) or opt-in encrypted
 *  ([EncryptedCredentialStore]). */
interface SessionStore : java.io.Serializable {
    fun get(username: String): JsonObject?
    fun users(): Set<String>
    fun put(username: String, session: JsonObject)
}

/**
 * On-disk Microsoft credential store — per-username serialized
 * `FullJavaSession` entries (refresh-token material) under the
 * unimined global cache. Written `0600`; keyring encryption is an
 * opt-in layer above this, not a plaintext-fallback mode.
 */
class CredentialStore(private val path: Path) : SessionStore {

    private fun read(): JsonObject = runCatching {
        JsonParser.parseString(Files.readString(path)).asJsonObject
    }.getOrElse { JsonObject() }

    private fun write(obj: JsonObject) {
        Files.createDirectories(path.parent)
        val fresh = !Files.exists(path)
        Files.writeString(path, obj.toString())
        if (fresh) runCatching {
            Files.setPosixFilePermissions(
                path, PosixFilePermissions.fromString("rw-------"))
        }
    }

    /** Stored session json for [username], or null. */
    override fun get(username: String): JsonObject? =
        read().getAsJsonObject(username)

    /** All stored usernames. */
    override fun users(): Set<String> = read().keySet()

    /** Persist [session] under [username]. */
    override fun put(username: String, session: JsonObject) {
        val obj = read()
        obj.add(username, session)
        write(obj)
    }

}
