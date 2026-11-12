package xyz.wagyourtail.unimined.source

import groovy.lang.Closure
import groovy.lang.DelegatesTo
import org.gradle.api.Action
import org.gradle.api.artifacts.Dependency
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import xyz.wagyourtail.unimined.api.util.delegatingTo
import javax.inject.Inject

/**
 * `source { }` — decompiler engine selection and options for
 * `genSources`/`gen<Env>Sources`.
 */
abstract class SourceExtension @Inject constructor(
    objects: ObjectFactory,
    private val dependencyFactory: org.gradle.api.artifacts.dsl.DependencyFactory
) {

    /**
     * Decompiler engine override — the [xyz.wagyourtail.unimined.api.spi.Named.name]
     * of a registered engine (e.g. `"vineflower"`, `"external"`). Unset:
     * capability selection picks the highest-priority supporting engine.
     */
    val engine: Property<String> = objects.property(String::class.java)

    /** Engine-specific options, forwarded verbatim — for vineflower these
     *  are its `-key=value` decompiler settings (e.g. `dgs` → `1`). The
     *  `external` engine additionally honors `mainClass` here (it is not
     *  passed to the decompiler). */
    val options: MapProperty<String, String> =
        objects.mapProperty(String::class.java, String::class.java)

    /**
     * JVM args for the decompiler's forked JVM (1.x
     * `sourceGenerator.jvmArgs`) — defaults to `-Xmx4G -Xms1G -Xss4M`.
     */
    val jvmArgs: ListProperty<String> =
        objects.listProperty(String::class.java)
            .apply { convention(listOf("-Xmx4G", "-Xms1G", "-Xss4M")) }

    /**
     * Java major version the decompiler's forked JVM resolves through
     * the toolchain service (1.x `sourceGenerator.javaVersion`) —
     * defaults to `17`; convention `""` disables the launcher lookup.
     */
    val javaVersion: Property<String> =
        objects.property(String::class.java).apply { convention("17") }

    /**
     * Raw CLI args appended to the decompiler invocation after the
     * `-key=value` options (1.x `sourceGenerator.args`) — defaults to
     * `-jrt=1`.
     */
    val args: ListProperty<String> =
        objects.listProperty(String::class.java)
            .apply { convention(listOf("-jrt=1")) }

    /**
     * Maven coordinate backing the `external` engine — a fernflower-CLI
     * decompiler jar (`engine.set("external")` + this). Set via
     * [generator] in the 1.x style.
     */
    val dependency: Property<String> = objects.property(String::class.java)

    /**
     * Select a decompiler jar by coordinate (1.x `generator(dep)`) —
     * switches the engine to `external`. A bare version string is
     * shorthand for `org.vineflower:vineflower:<version>`.
     */
    fun generator(dep: Any) = generator(dep) {}

    /** `generator(dep) { /* configure Dependency */ }` — [action]
     *  configures the materialized dependency; its resulting
     *  `group:name:version` coordinates are what the external engine
     *  resolves (excludes can't ride a coordinate, use `options` for
     *  engine tuning). */
    fun generator(dep: Any, action: Action<Dependency>) {
        engine.set("external")
        val d: Dependency =
            if (dep is Dependency) dep
            else dependencyFactory.create(
                if (dep is String && !dep.contains(":")) {
                    "org.vineflower:vineflower:$dep"
                } else dep.toString()
            )
        action.execute(d)
        val module = d as? org.gradle.api.artifacts.ExternalModuleDependency
            ?: throw org.gradle.api.GradleException(
                "source { generator($dep) }: expected a maven coordinate " +
                "dependency, got ${d.javaClass.simpleName}")
        dependency.set("${module.group}:${module.name}:${module.version}")
    }

    /** Groovy DSL twin of [generator]. */
    fun generator(
        dep: Any,
        @DelegatesTo(value = Dependency::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) = generator(dep) { action.delegatingTo(it) }
}
