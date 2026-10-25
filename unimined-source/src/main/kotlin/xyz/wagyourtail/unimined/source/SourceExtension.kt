package xyz.wagyourtail.unimined.source

import javax.inject.Inject
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property

/**
 * `source { }` — decompiler engine selection and options for
 * `genSources`/`gen<Env>Sources`.
 */
abstract class SourceExtension @Inject constructor(objects: ObjectFactory) {

    /**
     * Decompiler engine override — the [xyz.wagyourtail.unimined.api.spi.Named.name]
     * of a registered engine (e.g. `"vineflower"`). Unset: capability
     * selection picks the highest-priority supporting engine.
     */
    val engine: Property<String> = objects.property(String::class.java)

    /** Engine-specific options, forwarded verbatim — for vineflower these
     *  are its `-key=value` decompiler settings (e.g. `dgs` → `1`). */
    val options: MapProperty<String, String> =
        objects.mapProperty(String::class.java, String::class.java)

}
