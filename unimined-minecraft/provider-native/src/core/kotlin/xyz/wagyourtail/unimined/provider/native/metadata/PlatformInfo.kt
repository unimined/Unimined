package xyz.wagyourtail.unimined.provider.native.metadata

/** Host platform fields used to evaluate Mojang library rules. */
data class PlatformInfo(
    val osName: String,
    val arch: String,
    val osVersion: String,
    val features: Map<String, Boolean> = emptyMap()
) {

    /** Map the current JVM host to Mojang's OS names. */
    companion object {
        fun current(): PlatformInfo {
            val os = System.getProperty("os.name").lowercase()
            val mojangOs = when {
                os.contains("win") -> "windows"
                os.contains("mac") || os.contains("darwin") -> "osx"
                os.contains("linux") -> "linux"
                else -> os
            }
            return PlatformInfo(
                osName = mojangOs,
                arch = System.getProperty("os.arch"),
                osVersion = System.getProperty("os.version")
            )
        }
    }

}
