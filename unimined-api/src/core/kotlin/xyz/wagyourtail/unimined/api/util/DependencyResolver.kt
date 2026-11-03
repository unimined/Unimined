package xyz.wagyourtail.unimined.api.util

import java.nio.file.Path

/**
 * Coordinates-to-files resolution seam — Gradle adapters resolve
 * `group:name:version[:classifier]` notation through configurations;
 * no-Gradle callers (tests, CLI use) provide their own impl (e.g.
 * module-cache lookup + download-on-miss, see unimined-remap's
 * testFixtures harness).
 */
fun interface DependencyResolver {
    fun resolve(notation: String): List<Path>
}
