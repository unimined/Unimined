package xyz.wagyourtail.unimined.api.spec

/**
 * What a `unimined-transform` plugin consumes and produces: an
 * intermediate "named" artifact in [inputNamespace] transformed into a dev
 * artifact in [outputNamespace].
 */
interface TransformSpec {

    val inputNamespace: String

    val outputNamespace: String

}
