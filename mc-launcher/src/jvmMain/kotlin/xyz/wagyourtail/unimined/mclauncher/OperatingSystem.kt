package xyz.wagyourtail.unimined.mclauncher

/**
 * The launcher-facing operating-system identity used by version-JSON
 * rule matching and `natives` classifier selection.
 */
enum class OperatingSystem(val key: String) {
    WINDOWS("windows"),
    OSX("osx"),
    LINUX("linux");

    companion object {

        /** Detect the host operating system. */
        fun current(name: String = System.getProperty("os.name").lowercase()): OperatingSystem = when {
            name.startsWith("windows") -> WINDOWS
            name.startsWith("mac") || name.startsWith("osx") -> OSX
            else -> LINUX
        }

        /** Jvm-arch name used in `natives` classifier substitution (`${arch}`). */
        fun jvmArch(arch: String = System.getProperty("os.arch")): String =
            when (arch.lowercase()) {
                "x86_64", "amd64" -> "64"
                "x86", "i386", "i686" -> "32"
                else -> arch
            }

    }

}
