package xyz.wagyourtail.unimined.minecraft

/** Lookup facade on `unimined-transform`'s extension — implemented
 *  by `TransformExtension`; envs resolve it via
 *  `project.extensions.findByName("uniminedTransform")` so the
 *  minecraft module never class-depends on the transform module.
 *  Returns `Any` — the concrete spec type stays transform-side;
 *  consumers cast (transform's `loader` extension fn) or use it as a
 *  Groovy closure delegate (env's `loader` Closure overloads). */
interface TransformExtensionFacade {

    /** The transform spec for [environmentId] (created on demand). */
    fun environmentSpec(environmentId: String): Any

}
