package xyz.wagyourtail.unimined.cli

/** nodejs entry — Kotlin/JS doesn't populate `main(args)`; read
 *  `process.argv` (argv[0..1] are node + script). */
fun main() {
    @Suppress("UnsafeCastFromDynamic")
    val argv: Array<String> = js("process.argv.slice(2)")
    cliMain(argv)
}
