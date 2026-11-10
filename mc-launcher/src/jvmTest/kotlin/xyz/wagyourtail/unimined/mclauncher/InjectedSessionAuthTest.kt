package xyz.wagyourtail.unimined.mclauncher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InjectedSessionAuthTest {

    @Test
    fun `session reads values from the injected environment`() {
        val env = mapOf(
            "UNIMINED_AUTH_ACCESS_TOKEN" to "tok",
            "UNIMINED_AUTH_USERNAME" to "CiUser",
            "UNIMINED_AUTH_UUID" to "a-b-c"
        )
        val s = InjectedSessionAuth(lookup = env::get).session()
        assertEquals("CiUser", s.username)
        assertEquals("abc", s.uuid)
        assertEquals("tok", s.accessToken)
    }

    @Test
    fun `missing token fails with the remediation hint`() {
        val e = assertFailsWith<IllegalStateException> {
            InjectedSessionAuth(lookup = { null }).session()
        }
        assertEquals(true, e.message!!.contains("UNIMINED_AUTH_ACCESS_TOKEN"))
    }

}
