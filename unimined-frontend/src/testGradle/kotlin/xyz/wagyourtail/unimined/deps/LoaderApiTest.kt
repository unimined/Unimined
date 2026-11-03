package xyz.wagyourtail.unimined.deps

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue

/** Offline tests — [LoaderApi.fetch] is stubbed with canned pom/json. */
class LoaderApiTest {

    @BeforeEach
    fun stubFetch() {
        LoaderApi.fetch = { url ->
            when {
                url.endsWith(".pom") -> POM
                else -> error("no stub for $url")
            }
        }
    }

    @AfterEach
    fun resetFetch() {
        LoaderApi.fetch = { java.net.URI.create(it).toURL().readBytes().decodeToString() }
    }

    @Test
    fun `fabric full returns the aggregate coordinate`() {
        assertEquals(
            setOf("net.fabricmc.fabric-api:fabric-api:0.92.2+1.20.1"),
            LoaderApi.api("fabric", "0.92.2+1.20.1")
        )
    }

    @Test
    fun `module resolves a pom dependency coordinate`() {
        assertEquals(
            "net.fabricmc:fabric-loader:0.15.7",
            LoaderApi.apiModule("fabric", "fabric-loader", "0.92.2+1.20.1")
        )
    }

    @Test
    fun `unknown loader api errors with the provider list`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            LoaderApi.api("notalader", "1")
        }
        assertTrue(e.message!!.contains("fabric"))
    }

    @Test
    fun `missing module throws`() {
        assertThrows(IllegalStateException::class.java) {
            LoaderApi.apiModule("fabric", "no-such-module", "0.92.2+1.20.1")
        }
    }

    private companion object {
        const val POM = """<?xml version="1.0"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <dependencies>
    <dependency>
      <groupId>net.fabricmc</groupId>
      <artifactId>fabric-loader</artifactId>
      <version>0.15.7</version>
    </dependency>
  </dependencies>
</project>"""
    }
}
