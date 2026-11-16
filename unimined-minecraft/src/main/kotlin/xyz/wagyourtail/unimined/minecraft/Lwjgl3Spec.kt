package xyz.wagyourtail.unimined.minecraft

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * The env-level `lwjgl3 { }` spec — RetroFuturaGradle's lwjgl3
 * bindings-swap parity: drop the version-JSON's `org.lwjgl.lwjgl:*`
 * (lwjgl2 core/util/platform and its natives entries) and put lwjgl3
 * artifacts on the environment's classpath + natives set instead, for
 * running old Minecraft on modern JVMs. The lwjgl3ify mod itself is a
 * user dependency — this surface only swaps the bindings, mirroring
 * RFG's model (its `lwjgl3Configuration` + `lwjgl3Bindings` props).
 */
abstract class Lwjgl3Spec @Inject constructor(
    objects: ObjectFactory
) {

    /** Whether the swap applies — set when `lwjgl3 { }` is invoked. */
    abstract val enabled: Property<Boolean>

    /** LWJGL 3 version to resolve (RFG `lwjgl3Version` parity). */
    abstract val version: Property<String>

    /** Bindings pulled in alongside `lwjgl` core — each adds
     *  `org.lwjgl:lwjgl-<binding>:<version>` plus its host natives
     *  classifier (RFG `lwjgl3Bindings`; e.g. `"stb"`, `"openal"`). */
    abstract val bindings: ListProperty<String>

    /** Bindings with no natives classifier — jar coords only
     *  (RFG `lwjgl3BindingsWithoutNatives`; e.g. `"harfbuzz"`). */
    abstract val nativeslessBindings: ListProperty<String>

    init {
        enabled.convention(false)
        version.convention("3.3.1")
        bindings.convention(emptyList())
        nativeslessBindings.convention(emptyList())
    }

    /** Jar coords the swap puts on the environment's library
     *  classpath — `org.lwjgl:lwjgl:<v>` plus every binding. */
    fun jarCoords(): List<String> {
        val v = version.get()
        return listOf("org.lwjgl:lwjgl:$v") +
                (bindings.get() + nativeslessBindings.get())
                    .map { "org.lwjgl:lwjgl-$it:$v" }
    }

    /** Natives-classifier coords for the environment's natives
     *  configuration — core + [bindings] only, under the host's
     *  classifier name ([nativesClassifier] e.g. `natives-linux`). */
    fun nativesCoords(nativesClassifier: String): List<String> {
        val v = version.get()
        return (listOf("lwjgl") + bindings.get())
            .map { "org.lwjgl:lwjgl-$it:$v:$nativesClassifier" }
    }

    /** The lwjgl3 natives classifier for the host OS+arch —
     *  `natives-{windows,linux,macos}` with the `-x86`/`-arm64`
     *  suffixes lwjgl3 ships (RFG picks a single host classifier the
     *  same way). */
    fun nativesClassifier(
        osName: String = System.getProperty("os.name").lowercase(),
        osArch: String = System.getProperty("os.arch").lowercase()
    ): String {
        val arm = osArch.startsWith("aarch64") || osArch.startsWith("arm")
        return when {
            osName.startsWith("windows") ->
                if (arm) "natives-windows-arm64"
                else if (osArch == "x86" || osArch.startsWith("i3") ||
                    osArch.startsWith("i6")
                ) "natives-windows-x86" else "natives-windows"
            osName.startsWith("mac") || osName.startsWith("osx") ->
                if (arm) "natives-macos-arm64" else "natives-macos"
            else ->
                if (arm) "natives-linux-arm64" else "natives-linux"
        }
    }

}
