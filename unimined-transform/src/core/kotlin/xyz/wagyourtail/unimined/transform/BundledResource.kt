package xyz.wagyourtail.unimined.transform

/**
 * An embedded artifact a transform plugin ships inside its own jar and
 * wants materialized onto an environment's classpath as a file dep —
 * FG1's `/fmllibs/` payloads are the first consumer
 * (`deobfuscation_data_1.5.2.zip` is FML-generated content with no
 * maven home).
 *
 * Declarations go on `TransformEnvironmentSpec.bundledResources` so the
 * env's doc/gen surfaces can see what a provider ships; extraction is
 * via [BundledResourceChannel.materialize].
 */
data class BundledResource(

    /** Classpath resource path inside the contributing plugin jar —
     *  resolved through the caller's anchor class
     *  (`/fmllibs/deobfuscation_data_1.5.2.zip`). */
    val resourcePath: String,

    /** Filename the artifact lands under
     *  (`deobfuscation_data_1.5.2.zip`) — defaults to the resource
     *  basename. */
    val name: String = resourcePath.substringAfterLast('/'),
)
