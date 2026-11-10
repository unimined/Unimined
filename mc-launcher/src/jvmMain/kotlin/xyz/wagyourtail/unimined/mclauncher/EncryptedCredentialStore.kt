package xyz.wagyourtail.unimined.mclauncher

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-encrypted counterpart of [CredentialStore] — same per-username
 * JSON model, ciphertext on disk (`0600`). Opt-in via
 * `unimined.auth.encrypt=true`; the key comes from a
 * [KeyProvider] — OS keyring when `com.github.javakeyring:java-keyring`
 * is on the classpath (loaded reflectively, so the dep stays
 * optional). There is deliberately NO plaintext fallback: encrypt
 * opted in + no key = fail, never a silent downgrade.
 */
class EncryptedCredentialStore(
    private val path: Path,
    private val key: ByteArray
) : SessionStore {

    /** Supplies the AES-256 key material (32 bytes, base64-encoded). */
    fun interface KeyProvider : java.io.Serializable {
        fun key(): ByteArray
    }

    /** OS-keyring-backed provider — `java-keyring` loaded reflectively;
     *  absent lib or backend = fail (opt-in means explicit). */
    class JavaKeyringProvider(
        private val service: String = "unimined",
        private val account: String = "auth-store-key"
    ) : KeyProvider {
        override fun key(): ByteArray {
            val keyringClass = runCatching {
                Class.forName("com.github.javakeyring.Keyring")
            }.getOrElse {
                throw IllegalStateException(
                    "unimined.auth.encrypt needs " +
                        "com.github.javakeyring:java-keyring on the " +
                        "classpath — add it to the build classpath or " +
                        "turn encrypt off"
                )
            }
            val keyring = keyringClass.getMethod("create").invoke(null)
            val existing = runCatching {
                keyringClass.getMethod("getPassword", String::class.java,
                    String::class.java).invoke(keyring, service, account)
                        as String
            }.getOrNull()
            val b64 = existing ?: run {
                val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
                val encoded = Base64.getEncoder().encodeToString(bytes)
                keyringClass.getMethod("setPassword", String::class.java,
                    String::class.java, String::class.java)
                    .invoke(keyring, service, account, encoded)
                encoded
            }
            return Base64.getDecoder().decode(b64)
        }
    }

    private fun cipher(mode: Int, iv: ByteArray): Cipher =
        Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }

    private fun read(): JsonObject = runCatching {
        val bytes = Files.readAllBytes(path)
        if (bytes.isEmpty()) return JsonObject()
        val iv = bytes.copyOfRange(0, 16)
        val plain = cipher(Cipher.DECRYPT_MODE, iv)
            .doFinal(bytes.copyOfRange(16, bytes.size))
        JsonParser.parseString(String(plain, Charsets.UTF_8)).asJsonObject
    }.getOrElse { JsonObject() }

    private fun write(obj: JsonObject) {
        Files.createDirectories(path.parent)
        val fresh = !Files.exists(path)
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        Files.write(path,
            iv + cipher(Cipher.ENCRYPT_MODE, iv)
                .doFinal(obj.toString().toByteArray(Charsets.UTF_8)))
        if (fresh) runCatching {
            Files.setPosixFilePermissions(
                path, PosixFilePermissions.fromString("rw-------"))
        }
    }

    override fun get(username: String): JsonObject? =
        read().getAsJsonObject(username)

    override fun users(): Set<String> = read().keySet()

    override fun put(username: String, session: JsonObject) {
        val obj = read()
        obj.add(username, session)
        write(obj)
    }

}
