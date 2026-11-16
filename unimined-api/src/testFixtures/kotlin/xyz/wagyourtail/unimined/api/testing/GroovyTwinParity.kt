package xyz.wagyourtail.unimined.api.testing

import groovy.lang.Closure
import org.gradle.api.Action
import xyz.wagyourtail.unimined.api.util.NoGroovyTwin
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Reflective Groovy-DSL parity guard — scans the unimined classes on
 * the test runtime classpath for public methods taking
 * [Action]/Kotlin function types and reports the ones missing a
 * `Closure` twin at the same parameter index.
 *
 * Why: a Groovy `verb { }` call with no `Closure` overload SAM-coerces
 * the closure into `Action` — which *dispatches*, but the delegate
 * stays the build script, so unqualified member calls inside the
 * block resolve wrongly. The `@DelegatesTo` `Closure` twin is the fix;
 * this check keeps new DSL verbs from regressing the parity rule
 * (AGENTS.md "Groovy DSL parity").
 *
 * Excluded automatically:
 * - non-public, synthetic, bridge, and name-mangled (`foo$module` —
 *  Kotlin `internal`) members;
 * - tombstones: `@Deprecated(level = ERROR|HIDDEN)` methods —
 *  Groovy callers are meant to hit the migration error, not a DSL;
 * - methods/classes annotated [NoGroovyTwin] (Kotlin-only wiring
 *  seams and programmatic utilities);
 * - file-facade `*Kt` classes — Kotlin extension/top-level functions
 *  are invisible to Groovy member dispatch (they're the engine-plugin
 *  layer that registers `registerDslVerb` verbs, or Kotlin-only
 *  sugar whose Groovy twin is a *member* on the receiver);
 * - data-class generated `copy(...)` — a Kotlin-only member Groovy
 *  call sites never use (detected via `component1`).
 *
 * Classpath *directories* and *jars inside the checkout* are scanned
 * (sibling project deps resolve to `build/libs` jars); third-party
 * jars in maven caches are outside the checkout, so engine/adapter
 * compileOnly classes are never flagged. [excludedPackages] skips
 * sibling artifacts with no DSL surface at all (the embedded
 * Gradle-free `mc-launcher` library).
 */
object GroovyTwinParity {

    private val FUNCTION_TYPE: Class<*> = kotlin.Function::class.java

    /** Sibling artifacts with no DSL surface — package roots skipped
     *  wholesale (they can't even see [NoGroovyTwin]). */
    private val NON_DSL_PACKAGES = setOf(
        "xyz.wagyourtail.unimined.mclauncher"
    )

    /**
     * Human-readable violations — e.g.
     * `com.example.Spec#verb(Action)` — for every public method taking
     * an [Action]/function parameter that has no same-name,
     * same-arity `Closure` overload on the declaring class.
     */
    fun missingTwins(packagePrefix: String): List<String> {
        val violations = mutableListOf<String>()
        for (clazz in uniminedClasses(packagePrefix)) {
            if (NON_DSL_PACKAGES.any { clazz.name.startsWith("$it.") }) continue
            // Kotlin file facades — extension/top-level fns, not members
            if (clazz.simpleName.endsWith("Kt")) continue
            if (clazz.getAnnotation(NoGroovyTwin::class.java) != null) continue
            val declared = try {
                clazz.declaredMethods
            } catch (e: Throwable) {
                // signature references an optional (compileOnly) type
                // absent from this classpath — nothing checkable
                continue
            }
            val isDataClass = declared.any { it.name == "component1" }
            for (method in declared) {
                if (skip(method)) continue
                // data-class generated copy — Kotlin-only member
                if (isDataClass && method.name == "copy") continue
                val params = try {
                    method.parameterTypes
                } catch (e: Throwable) {
                    continue
                }
                params.forEachIndexed { index, param ->
                    if (param != Closure::class.java &&
                        (param == Action::class.java ||
                                FUNCTION_TYPE.isAssignableFrom(param))
                    ) {
                        if (!hasClosureTwin(declared, method, index)) {
                            violations += describe(clazz, method, index)
                        }
                    }
                }
            }
        }
        return violations.sorted()
    }

    /** Class files under [packagePrefix] on the test classpath —
     *  classpath *directories* (this module's own source-set outputs)
     *  plus *jars living inside the checkout* (sibling project deps
     *  resolve to `build/libs` jars). Third-party jars in maven
     *  caches are outside the checkout, so engine/adapter compileOnly
     *  classes are never flagged. */
    private fun uniminedClasses(packagePrefix: String): List<Class<*>> {
        val prefixPath = packagePrefix.replace('.', '/') + "/"
        val loader = GroovyTwinParity::class.java.classLoader
        val checkout = checkoutRoot()
        val names = linkedSetOf<String>()
        for (entry in System.getProperty("java.class.path")
            .split(File.pathSeparator)) {
            val root = File(entry).canonicalFile
            if (root.isDirectory) {
                val pkg = root.resolve(prefixPath)
                if (!pkg.isDirectory) continue
                pkg.walkTopDown()
                    .filter { it.isFile && it.name.endsWith(".class") }
                    .forEach { file ->
                        names += packagePrefix + "." + file.relativeTo(pkg)
                            .invariantSeparatorsPath
                            .removeSuffix(".class")
                            .replace('/', '.')
                    }
            } else if (root.isFile && root.name.endsWith(".jar") &&
                root.path.startsWith(checkout.path)
            ) {
                java.util.zip.ZipFile(root).use { zip ->
                    zip.entries().asSequence()
                        .filter {
                            it.name.startsWith(prefixPath) &&
                                    it.name.endsWith(".class")
                        }
                        .forEach { entry ->
                            names += entry.name
                                .removeSuffix(".class")
                                .replace('/', '.')
                        }
                }
            }
        }
        return names.mapNotNull { name ->
            try {
                Class.forName(name, /* initialize = */ false, loader)
            } catch (e: Throwable) {
                // facade/inner-class load quirks — never a DSL surface
                null
            }
        }
    }

    /** The multi-project checkout root — nearest ancestor of the test
     *  working dir containing `settings.gradle.kts` (project-dep jars
     *  live below it; maven-cache jars don't). */
    private fun checkoutRoot(): File {
        var dir = File(System.getProperty("user.dir")).canonicalFile
        while (true) {
            if (dir.resolve("settings.gradle.kts").isFile ||
                dir.resolve("settings.gradle").isFile
            ) return dir
            dir = dir.parentFile ?: return File("/")
        }
    }

    private fun skip(method: Method): Boolean {
        if (!Modifier.isPublic(method.modifiers)) return true
        if (method.isSynthetic || method.isBridge) return true
        if (method.name.contains('$')) return true // internal mangled
        if (method.getAnnotation(NoGroovyTwin::class.java) != null) return true
        // removed-1.x tombstones — Groovy is meant to hit the error
        method.getAnnotation(kotlin.Deprecated::class.java)?.let {
            if (it.level != DeprecationLevel.WARNING) return true
        }
        return false
    }

    private fun hasClosureTwin(
        declared: Array<Method>,
        method: Method,
        index: Int
    ): Boolean = declared.any { twin ->
        twin !== method &&
                twin.name == method.name &&
                twin.parameterCount == method.parameterCount &&
                try {
                    twin.parameterTypes[index] == Closure::class.java
                } catch (e: Throwable) {
                    false
                }
    }

    private fun describe(clazz: Class<*>, method: Method, index: Int): String =
        "${clazz.name}#${method.name}${
            method.parameterTypes.joinToString(
                ",",
                prefix = "(",
                postfix = ")"
            ) { it.simpleName }
        } — missing Closure twin for param #$index"

}
