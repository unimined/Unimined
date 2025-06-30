package xyz.wagyourtail.unimined.api.minecraft

import groovy.lang.Closure
import groovy.lang.DelegatesTo
import org.jetbrains.annotations.ApiStatus
import xyz.wagyourtail.unimined.api.minecraft.patch.*
import xyz.wagyourtail.unimined.api.minecraft.patch.ataw.AccessTransformerPatcher
import xyz.wagyourtail.unimined.api.minecraft.patch.ataw.AccessWidenerPatcher
import xyz.wagyourtail.unimined.api.minecraft.patch.bukkit.CraftbukkitPatcher
import xyz.wagyourtail.unimined.api.minecraft.patch.bukkit.PaperPatcher
import xyz.wagyourtail.unimined.api.minecraft.patch.bukkit.SpigotPatcher
import xyz.wagyourtail.unimined.api.minecraft.patch.fabric.FabricLikePatcher
import xyz.wagyourtail.unimined.api.minecraft.patch.fabric.LegacyFabricPatcher
import xyz.wagyourtail.unimined.api.minecraft.patch.forge.CleanroomPatcher
import xyz.wagyourtail.unimined.api.minecraft.patch.forge.ForgeLikePatcher
import xyz.wagyourtail.unimined.api.minecraft.patch.forge.MinecraftForgePatcher
import xyz.wagyourtail.unimined.api.minecraft.patch.forge.NeoForgedPatcher
import xyz.wagyourtail.unimined.api.minecraft.patch.jarmod.JarModAgentPatcher
import xyz.wagyourtail.unimined.api.minecraft.patch.liteloader.LiteLoaderPatcher
import xyz.wagyourtail.unimined.api.minecraft.patch.conversion.reindev.FoxLoaderPatcher
import xyz.wagyourtail.unimined.api.minecraft.patch.rift.RiftPatcher
import java.lang.UnsupportedOperationException

/**
 * usage:
 *
 *
 * @since 0.4.10
 */
interface PatchProviders {

    /**
     * enables the fabric patcher.
     * @param action the action to configure the patcher.
     * @see FabricLikePatcher
     * @since 0.1.0
     */
    fun fabric(action: FabricLikePatcher.() -> Unit)

    /**
     * enables the fabric patcher.
     * @param action the action to perform on the patcher.
     * @since 0.1.0
     */
    fun fabric(
        @DelegatesTo(
            value = FabricLikePatcher::class,
            strategy = Closure.DELEGATE_FIRST
        ) action: Closure<*>
    ) {
        fabric {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    /**
     * enables the fabric patcher.
     * @since 0.1.0
     */
    fun fabric() {
        fabric {}
    }

    /**
     * enables the fabric patcher with additional tweaks for LegacyFabric.
     * @param action the action to perform on the patcher.
     * @see LegacyFabricPatcher
     * @since 0.4.2
     */
    fun legacyFabric(action: LegacyFabricPatcher.() -> Unit)

    /**
     * enables the fabric patcher with additional tweaks for LegacyFabric.
     * @param action the action to perform on the patcher.
     * @since 0.4.2
     */
    fun legacyFabric(
        @DelegatesTo(
            value = LegacyFabricPatcher::class,
            strategy = Closure.DELEGATE_FIRST
        ) action: Closure<*>
    ) {
        legacyFabric {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    /**
     * enables the fabric patcher with additional tweaks for LegacyFabric.
     * @since 0.4.2
     */
    fun legacyFabric() {
        legacyFabric {}
    }

    /**
     * enables the fabric patcher with additional tweaks for babric.
     * @param action the action to perform on the patcher.
     * @since 1.0.0
     */
    fun babric(action: FabricLikePatcher.() -> Unit)

    /**
     * enables the fabric patcher with additional tweaks for babric.
     * @param action the action to perform on the patcher.
     * @since 1.0.0
     */
    fun babric(
        @DelegatesTo(
            value = FabricLikePatcher::class,
            strategy = Closure.DELEGATE_FIRST
        ) action: Closure<*>
    ) {
        babric {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    /**
     * enables the fabric patcher with additional tweaks for babric.
     * @since 1.0.0
     */
    fun babric() {
        babric {}
    }

    /**
     * enables the fabric patcher with additional tweaks for Ornithe.
     * @param action the action to perform on the patcher.
     * @since 1.4.0
     */
    fun ornitheFabric(action: LegacyFabricPatcher.() -> Unit)

    /**
     * enables the fabric patcher with additional tweaks for Ornithe.
     * @param action the action to perform on the patcher.
     * @since 1.4.0
     */
    fun ornitheFabric(
        @DelegatesTo(
            value = LegacyFabricPatcher::class,
            strategy = Closure.DELEGATE_FIRST
        ) action: Closure<*>
    ) {
        ornitheFabric {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    /**
     * enables the fabric patcher with additional tweaks for Ornithe.
     * @since 1.4.0
     */
    fun ornitheFabric() {
        ornitheFabric {}
    }

    /**
     * enables the quilt patcher.
     * @param action the action to configure the patcher.
     * @see FabricLikePatcher
     * @since 0.3.4
     */
    fun quilt(action: FabricLikePatcher.() -> Unit)

    /**
     * enables the quilt patcher.
     * @param action the action to perform on the patcher.
     * @since 0.3.4
     */
    fun quilt(
        @DelegatesTo(
            value = FabricLikePatcher::class,
            strategy = Closure.DELEGATE_FIRST
        ) action: Closure<*>
    ) {
        quilt {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    /**
     * enables the quilt patcher.
     * @since 0.3.4
     * @since 0.3.4
     */
    fun quilt() {
        quilt {}
    }

    /**
     * enables the forge patcher.
     * @param action the action to configure the patcher.
     * @see ForgeLikePatcher
     * @since 0.1.0
     */
    @Deprecated(
        message = "Please specify which forge.",
        replaceWith = ReplaceWith(
            expression = "minecraftForge(action)"
        )
    )
    fun forge(action: ForgeLikePatcher<*>.() -> Unit)

    /**
     * enables the forge patcher.
     * @param action the action to perform on the patcher.
     * @since 0.1.0
     */
    @Deprecated(
        message = "Please specify which forge.",
        replaceWith = ReplaceWith(
            expression = "minecraftForge(action)"
        )
    )
    fun forge(
        @DelegatesTo(
            value = ForgeLikePatcher::class,
            strategy = Closure.DELEGATE_FIRST
        ) action: Closure<*>
    ) {
        @Suppress("DEPRECATION")
        forge {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    /**
     * enables the forge patcher.
     * @since 0.1.0
     */

    @Deprecated(
        message = "Please specify which forge.",
        replaceWith = ReplaceWith(
            expression = "minecraftForge()"
        )
    )
    fun forge() {
        @Suppress("DEPRECATION")
        forge {}
    }

    /**
     * enables the minecraft forge patcher.
     * @since 1.0.0
     */
    fun minecraftForge(action: MinecraftForgePatcher<*>.() -> Unit)

    /**
     * enables the minecraft forge patcher.
     * @since 1.0.0
     */
    fun minecraftForge(
        @DelegatesTo(
            value = MinecraftForgePatcher::class,
            strategy = Closure.DELEGATE_FIRST
        ) action: Closure<*>
    ) {
        minecraftForge {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    /**
     * enables the minecraft forge patcher.
     * @since 1.0.0
     */
    fun minecraftForge() {
        minecraftForge {}
    }

    /**
     * enables the NeoForged patcher.
     * @since 1.1.0
     */
    @Deprecated(message = "neoForged -> neoForge", replaceWith = ReplaceWith("neoForge(action)"))
    fun neoForged(action: NeoForgedPatcher<*>.() -> Unit) {
        neoForge(action)
    }

    /**
     * enables the NeoForged patcher.
     * @since 1.1.0
     */
    @Deprecated(message = "neoForged -> neoForge", replaceWith = ReplaceWith("neoForge(action)"))
    fun neoForged(
        @DelegatesTo(
            value = NeoForgedPatcher::class,
            strategy = Closure.DELEGATE_FIRST
        ) action: Closure<*>
    ) {
        neoForge {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    /**
     * enables the NeoForged patcher.
     * @since 1.1.0
     */
    @Deprecated(message = "neoForged -> neoForge", replaceWith = ReplaceWith("neoForge()"))
    fun neoForged() {
        neoForge {}
    }

    fun neoForge(action: NeoForgedPatcher<*>.() -> Unit)

    fun neoForge(
        @DelegatesTo(
            value = NeoForgedPatcher::class,
            strategy = Closure.DELEGATE_FIRST
        ) action: Closure<*>
    ) {
        neoForge {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    fun neoForge() {
        neoForge {}
    }

    /**
     * enables the cleanroom patcher.
     * @since 1.3.0
     */
    fun cleanroom() {
        cleanroom {}
    }

    /**
     * enables the cleanroom patcher.
     * @param action the action to configure the patcher.
     * @since 1.3.0
     */
    fun cleanroom(action: CleanroomPatcher<*>.() -> Unit)

    /**
     * enables the cleanroom patcher.
     * @param action the action to configure the patcher.
     * @since 1.3.0
     */
    fun cleanroom(
        @DelegatesTo(
            value = CleanroomPatcher::class,
            strategy = Closure.DELEGATE_FIRST
        ) action: Closure<*>
    ) {
        cleanroom {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    /**
     * enables the FoxLoader patcher.
     * @since 1.3.5
     */
    fun foxLoader() {
        foxLoader {}
    }

    /**
     * enables the FoxLoader patcher.
     * @param action the action to configure the patcher.
     * @since 1.3.5
     */
    fun foxLoader(action: FoxLoaderPatcher.() -> Unit) {
        throw UnsupportedOperationException("Only ReIndev is supported by FoxLoader.")
    }

    /**
     * enables the FoxLoader patcher.
     * @param action the action to configure the patcher.
     * @since 1.3.5
     */
    fun foxLoader(
        @DelegatesTo(
            value = FoxLoaderPatcher::class,
            strategy = Closure.DELEGATE_FIRST
        ) action: Closure<*>
    ) {
        foxLoader {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    /**
     * enables the jar mod patcher.
     * @param action the action to configure the patcher.
     * @see JarModAgentPatcher
     * @since 0.1.0
     */
    fun jarMod(action: JarModAgentPatcher.() -> Unit)

    /**
     * enables the jar mod patcher.
     * @param action the action to perform on the patcher.
     * @since 0.1.0
     */
    fun jarMod(
        @DelegatesTo(
            value = JarModAgentPatcher::class,
            strategy = Closure.DELEGATE_FIRST
        ) action: Closure<*>
    ) {
        jarMod {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    /**
     * enables the jar mod patcher.
     * @since 0.1.0
     */
    fun jarMod() {
        jarMod {}
    }

    /**
     * enables **just** access widener patching
     * you have to create your own way to bootstrap it out of dev.
     * (and probably apply launchtweaker or something for dev launches).
     * @since 1.2.0
     */
    fun accessWidener(action: AccessWidenerPatcher.() -> Unit)


    /**
     * enables **just** access widener patching
     * you have to create your own way to bootstrap it out of dev.
     * (and probably apply launchtweaker or something for dev launches).
     * @since 1.2.0
     */
    fun accessWidener(
        @DelegatesTo(
            value = AccessWidenerPatcher::class,
            strategy = Closure.DELEGATE_FIRST
        ) action: Closure<*>
    ) {
        accessWidener {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    /**
     * enables **just** access widener patching
     * you have to create your own way to bootstrap it out of dev.
     * (and probably apply launchtweaker or something for dev launches).
     * @since 1.2.0
     */
    fun accessWidener() {
        accessWidener {}
    }

    /**
     * enables **just** access transformer patching
     * you have to create your own way to bootstrap it out of dev.
     * (and probably apply launchtweaker or something for dev launches).
     * @since 1.2.0
     */
    fun accessTransformer(action: AccessTransformerPatcher.() -> Unit)

    /**
     * enables **just** access transformer patching
     * you have to create your own way to bootstrap it out of dev.
     * (and probably apply launchtweaker or something for dev launches).
     * @since 1.2.0
     */
    fun accessTransformer(
        @DelegatesTo(
            value = AccessTransformerPatcher::class,
            strategy = Closure.DELEGATE_FIRST
        ) action: Closure<*>) {
        accessTransformer {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    /**
     * requires `side "server"`
     *
     * this is currently done in a kinda dumb way that is slow, avoid if possible for now.
     *
     * @since 1.2.0
     */
    @ApiStatus.Experimental
    fun craftBukkit(action: CraftbukkitPatcher.() -> Unit)

    /**
     * requires `side "server"`
     *
     * this is currently done in a kinda dumb way that is slow, avoid if possible for now.
     *
     * @since 1.2.0
     */
    @ApiStatus.Experimental
    fun craftBukkit(
        @DelegatesTo(
            value = CraftbukkitPatcher::class,
            strategy = Closure.DELEGATE_FIRST
        ) action: Closure<*>) {
        craftBukkit {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    /**
     * requires `side "server"`
     *
     * this is currently done in a kinda dumb way that is slow, avoid if possible for now.
     *
     * @since 1.2.0
     */
    @ApiStatus.Experimental
    fun spigot(action: SpigotPatcher.() -> Unit)

    /**
     * requires `side "server"`
     *
     * this is currently done in a kinda dumb way that is slow, avoid if possible for now.
     *
     * @since 1.2.0
     */
    @ApiStatus.Experimental
    fun spigot(
        @DelegatesTo(
            value = SpigotPatcher::class,
            strategy = Closure.DELEGATE_FIRST
        ) action: Closure<*>) {
        spigot {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    /**
     * enables the flint patcher.
     * @param action the action to configure the patcher.
     * @see FabricLikePatcher
     * @since 1.2.0
     */
    fun flint(action: FabricLikePatcher.() -> Unit)

    /**
     * enables the flint patcher.
     * @param action the action to perform on the patcher.
     * @since 1.2.0
     */
    fun flint(
        @DelegatesTo(
            value = FabricLikePatcher::class,
            strategy = Closure.DELEGATE_FIRST
        ) action: Closure<*>
    ) {
        flint {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    /**
     * enables the flint patcher.
     * @since 1.2.0
     */
    fun flint() {
        flint {}
    }

    /**
     * enables **just** access transformer patching
     * you have to create your own way to bootstrap it out of dev
     * (and probably apply launchtweaker or something for dev launches).
     * @since 1.2.0
     */
    fun accessTransformer() {
        accessTransformer {}
    }

    /**
     * enables the rift patcher.
     * @param action the action to configure the patcher.
     * @see RiftPatcher
     * @since 1.2.0
     */
    fun rift(action: RiftPatcher.() -> Unit)

    /**
     * enables the rift patcher.
     * @param action the action to perform on the patcher.
     * @since 1.2.0
     */
    fun rift(
        @DelegatesTo(
            value = RiftPatcher::class,
            strategy = Closure.DELEGATE_FIRST
        ) action: Closure<*>
    ) {
        rift {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    /**
     * enables the rift patcher.
     * @since 1.2.0
     */
    fun rift() {
        rift {}
    }

    /**
     * enables the liteloader patcher.
     * @param action the action to configure the patcher.
     * @since 1.4.0
     */
    fun liteloader(action: LiteLoaderPatcher.() -> Unit)

    /**
     * enables the liteloader patcher.
     * @param action the action to configure the patcher.
     * @since 1.4.0
     */
    fun liteloader(
        @DelegatesTo(
            value = LiteLoaderPatcher::class,
            strategy = Closure.DELEGATE_FIRST
        ) action: Closure<*>) {
        liteloader {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    /**
     * enables the liteloader patcher.
     * @since 1.4.0
     */
    fun liteloader() {
        liteloader {}
    }

    /**
     * enables the paper patcher.
     * @since 1.4.0
     */
    fun paper() {
        paper {}
    }

    /**
     * enables the paper patcher.
     * @since 1.4.0
     */
    fun paper(action: PaperPatcher.() -> Unit)

    /**
     * enables the paper patcher.
     * @since 1.4.0
     */
    fun paper(
        @DelegatesTo(
            value = PaperPatcher::class,
            strategy = Closure.DELEGATE_FIRST
        ) action: Closure<*>) {
        paper {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    @ApiStatus.Experimental
    fun <T: MinecraftPatcher> customPatcher(mcPatcher: T, action: T.() -> Unit)

}