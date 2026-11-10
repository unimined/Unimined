package xyz.wagyourtail.unimined.mclauncher

import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CredentialStoreTest {

    @Test
    fun `put then get round-trips the session json`() {
        val dir = Files.createTempDirectory("unimined-auth-test")
        val store = CredentialStore(dir.resolve("credentials.json"))
        val session = JsonObject().apply { addProperty("token", "abc") }
        store.put("Player", session)
        assertEquals(session, store.get("Player"))
        assertEquals(setOf("Player"), store.users())
        assertNull(store.get("Other"))
    }

    @Test
    fun `missing file reads as empty store`() {
        val store = CredentialStore(
            Files.createTempDirectory("unimined-auth-test")
                .resolve("nope/credentials.json"))
        assertTrue(store.users().isEmpty())
    }

    @Test
    fun `store file is created with owner-only permissions`() {
        val dir = Files.createTempDirectory("unimined-auth-test")
        val path = dir.resolve("credentials.json")
        CredentialStore(path).put("P", JsonObject())
        assertTrue(path.exists())
        val perms = runCatching {
            Files.getPosixFilePermissions(path)
        }.getOrNull() ?: return  // non-POSIX fs — nothing to assert
        assertEquals(
            PosixFilePermissions.fromString("rw-------"), perms)
    }

    @Test
    fun `encrypted store round-trips and writes ciphertext`() {
        val dir = Files.createTempDirectory("unimined-auth-test")
        val path = dir.resolve("credentials.json.enc")
        val key = ByteArray(32) { it.toByte() }
        val store = EncryptedCredentialStore(path, key)
        store.put("Player",
            JsonObject().apply { addProperty("token", "secret") })
        // on-disk bytes are ciphertext — the session name isn't visible
        val raw = String(Files.readAllBytes(path))
        assertEquals(false, raw.contains("secret"))
        // a store with the same key reads it back
        assertEquals("secret",
            EncryptedCredentialStore(path, key)
                .get("Player")!!.get("token").asString)
    }

    @Test
    fun `put preserves existing entries`() {
        val dir = Files.createTempDirectory("unimined-auth-test")
        val store = CredentialStore(dir.resolve("credentials.json"))
        store.put("A", JsonObject().apply { addProperty("t", "1") })
        store.put("B", JsonObject().apply { addProperty("t", "2") })
        assertEquals(setOf("A", "B"), store.users())
        assertEquals("1", store.get("A")!!.get("t").asString)
    }

}
