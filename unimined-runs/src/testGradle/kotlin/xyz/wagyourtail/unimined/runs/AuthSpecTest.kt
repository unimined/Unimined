package xyz.wagyourtail.unimined.runs

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.mclauncher.AuthProvider
import xyz.wagyourtail.unimined.mclauncher.Session

/**
 * `auth { }` verb -> spec property mapping on [RunConfig], incl. the
 * `username` back-compat bridge.
 */
class AuthSpecTest {

    private fun runConfig(): RunConfig {
        val project = ProjectBuilder.builder().build()
        return project.objects.newInstance(RunConfig::class.java, "test")
    }

    @Test
    fun `default kind is offline with Dev username`() {
        val rc = runConfig()
        assertEquals(AuthSpec.Kind.OFFLINE, rc.auth.kind.get())
        assertEquals("Dev", rc.auth.username.get())
    }

    @Test
    fun `username property bridges to auth username`() {
        val rc = runConfig()
        rc.username.set("Notch")
        assertEquals("Notch", rc.auth.username.get())
    }

    @Test
    fun `explicit auth username wins over username property`() {
        val rc = runConfig()
        rc.username.set("Notch")
        rc.auth { it.offline("Herobrine") }
        assertEquals("Herobrine", rc.auth.username.get())
    }

    @Test
    fun `microsoft verb sets kind`() {
        val rc = runConfig()
        rc.auth { it.microsoft() }
        assertEquals(AuthSpec.Kind.MICROSOFT, rc.auth.kind.get())
    }

    @Test
    fun `custom verb captures the provider`() {
        val rc = runConfig()
        val provider = AuthProvider {
            Session("ci", "uuid", "token")
        }
        rc.auth { it.custom(provider) }
        assertEquals(AuthSpec.Kind.CUSTOM, rc.auth.kind.get())
        assertEquals(provider, rc.auth.provider.get())
    }

    private fun extension(): RunsExtension {
        val project = ProjectBuilder.builder().build()
        return project.objects.newInstance(RunsExtension::class.java)
    }

    @Test
    fun `extension auth conventions into all runs`() {
        val ext = extension()
        ext.auth { it.microsoft() }
        val rc = ext.runs.maybeCreate("client")
        assertEquals(AuthSpec.Kind.MICROSOFT, rc.auth.kind.get())
    }

    @Test
    fun `run-level auth wins over extension default`() {
        val ext = extension()
        ext.auth { it.microsoft() }
        val rc = ext.runs.maybeCreate("client")
        rc.auth { it.offline("Solo") }
        assertEquals(AuthSpec.Kind.OFFLINE, rc.auth.kind.get())
        assertEquals("Solo", rc.auth.username.get())
    }

    @Test
    fun `run username bridge still wins over extension auth username`() {
        val ext = extension()
        ext.auth { it.offline("Shared") }
        val rc = ext.runs.maybeCreate("client")
        assertEquals("Shared", rc.auth.username.get())
        rc.username.set("Notch")
        assertEquals("Notch", rc.auth.username.get())
    }

}
