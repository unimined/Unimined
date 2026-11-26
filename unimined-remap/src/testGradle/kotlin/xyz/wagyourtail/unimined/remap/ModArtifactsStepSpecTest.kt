package xyz.wagyourtail.unimined.remap

import groovy.lang.Closure
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xyz.wagyourtail.unimined.remap.spi.RemapSurface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

/** `modArtifacts { }` step spec — the 2.0 shape of 1.x
 *  `mixinRemap { }`: every verb flips a plain serializable field
 *  (the spec serializes into the registered pipeline), and the DSL
 *  verb registers on JAR surfaces through both the `Action` and
 *  Groovy `Closure` overloads. Engine-side negotiation of the same
 *  fields is covered by `UmlModArtifactsStepTypeTest`. */
class ModArtifactsStepSpecTest {

    private fun extension(): RemapExtension {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(GradlePlugin::class.java)
        return project.extensions.getByType(RemapExtension::class.java)
    }

    // ---------- option verbs ----------

    @Test
    fun `off flips mixinRemapOff only`() {
        val spec = ModArtifactsStepSpec().apply { off() }
        assertTrue(spec.mixinRemapOff)
        // refmap emission is a separate axis — off() doesn't touch it
        assertTrue(spec.emitRefmap)
        assertEquals(emptySet<String>(), spec.noRefmapConfigs)
        assertEquals(emptySet<String>(), spec.forceMixinTables)
    }

    @Test
    fun `disableRefmap no-arg turns emission off`() {
        val spec = ModArtifactsStepSpec().apply { disableRefmap() }
        assertFalse(spec.emitRefmap)
        assertEquals(emptySet<String>(), spec.noRefmapConfigs)
    }

    @Test
    fun `disableRefmap vararg accumulates config paths`() {
        val spec = ModArtifactsStepSpec().apply {
            disableRefmap("a.mixins.json")
            disableRefmap("b.mixins.json", "c.mixins.json")
        }
        assertEquals(
            setOf("a.mixins.json", "b.mixins.json", "c.mixins.json"),
            spec.noRefmapConfigs
        )
        // per-config suppression leaves global emission on
        assertTrue(spec.emitRefmap)
    }

    @Test
    fun `disableRefmap set overload merges with earlier calls`() {
        val spec = ModArtifactsStepSpec().apply {
            disableRefmap("a.mixins.json")
            disableRefmap(setOf("b.mixins.json"))
        }
        assertEquals(
            setOf("a.mixins.json", "b.mixins.json"),
            spec.noRefmapConfigs
        )
    }

    @Test
    fun `enable verbs accumulate forceMixinTables`() {
        val spec = ModArtifactsStepSpec().apply {
            enableBaseMixin()
            enableMixinExtra()
            enableJarModAgent()
        }
        assertEquals(setOf("mixin", "mixinextras", "jma"),
            spec.forceMixinTables)
    }

    @Test
    fun `enable verbs are independent`() {
        assertEquals(setOf("mixin"),
            ModArtifactsStepSpec().apply { enableBaseMixin() }
                .forceMixinTables)
        assertEquals(setOf("mixinextras"),
            ModArtifactsStepSpec().apply { enableMixinExtra() }
                .forceMixinTables)
        assertEquals(setOf("jma"),
            ModArtifactsStepSpec().apply { enableJarModAgent() }
                .forceMixinTables)
    }


    @Test
    fun `class name ldc remap is opt-in and scoped`() {
        val spec = ModArtifactsStepSpec()
        assertFalse(spec.remapClassNameLdcs)
        spec.enableClassNameLdcRemap("com/example/Transformer")
        spec.enableClassNameLdcRemap(setOf("com/example/OtherTransformer"))
        assertTrue(spec.remapClassNameLdcs)
        assertEquals(setOf("com/example/Transformer", "com/example/OtherTransformer"),
            spec.classNameLdcClasses)
        spec.disableClassNameLdcRemap()
        assertFalse(spec.remapClassNameLdcs)
        assertEquals(emptySet<String>(), spec.classNameLdcClasses)
    }

    @Test
    fun `launchwrapper transformer ldc remap is independently opt-in`() {
        val spec = ModArtifactsStepSpec()
        assertFalse(spec.remapLaunchwrapperTransformerLdcs)
        assertFalse(spec.remapClassNameLdcs)
        spec.enableLaunchwrapperTransformerLdcRemap()
        assertTrue(spec.remapLaunchwrapperTransformerLdcs)
        assertTrue(spec.remapClassNameLdcs)
        assertEquals(emptySet<String>(), spec.classNameLdcClasses)
        spec.disableLaunchwrapperTransformerLdcRemap()
        assertFalse(spec.remapLaunchwrapperTransformerLdcs)
        assertFalse(spec.remapClassNameLdcs)
    }

    // ---------- correctness-flag tri-state ----------

    private val ldcFlag = ModArtifactsStepSpec.CorrectnessFlag.LAUNCHWRAPPER_TRANSFORMER_LDCS

    @Test
    fun `correctness flag starts never-mentioned and preseed applies`() {
        val spec = ModArtifactsStepSpec()
        assertEquals(emptySet<ModArtifactsStepSpec.CorrectnessFlag>(),
            spec.explicitlyConfiguredCorrectnessFlags,
            "an untouched spec carries no explicit correctness configuration")
        spec.preseedLaunchwrapperTransformerLdcRemap()
        assertTrue(spec.remapLaunchwrapperTransformerLdcs,
            "pre-seed must apply when the user never mentioned the flag")
        assertEquals(emptySet<ModArtifactsStepSpec.CorrectnessFlag>(),
            spec.explicitlyConfiguredCorrectnessFlags,
            "a pre-seed write must not masquerade as a user configuration")
    }

    @Test
    fun `explicit enable is recorded and preseed stays convergent`() {
        val spec = ModArtifactsStepSpec()
        spec.enableLaunchwrapperTransformerLdcRemap()
        assertTrue(ldcFlag in spec.explicitlyConfiguredCorrectnessFlags,
            "the enable verb is a user-strength write")
        spec.preseedLaunchwrapperTransformerLdcRemap()
        assertTrue(spec.remapLaunchwrapperTransformerLdcs,
            "pre-seed on an explicitly-enabled flag is a convergent no-op")
    }

    @Test
    fun `explicit disable is recorded and preseed is a no-op`() {
        val spec = ModArtifactsStepSpec()
        spec.disableLaunchwrapperTransformerLdcRemap()
        assertTrue(ldcFlag in spec.explicitlyConfiguredCorrectnessFlags,
            "the disable verb is a user-strength write")
        spec.preseedLaunchwrapperTransformerLdcRemap()
        assertFalse(spec.remapLaunchwrapperTransformerLdcs,
            "a deliberate user opt-out must survive loader correctness pre-seeds")
    }

    @Test
    fun `direct property assignment is a user-strength write`() {
        val explicitOn = ModArtifactsStepSpec()
        explicitOn.remapLaunchwrapperTransformerLdcs = true
        assertTrue(ldcFlag in explicitOn.explicitlyConfiguredCorrectnessFlags)

        val explicitOff = ModArtifactsStepSpec()
        explicitOff.remapLaunchwrapperTransformerLdcs = false
        assertTrue(ldcFlag in explicitOff.explicitlyConfiguredCorrectnessFlags,
            "even a redundant-value assignment is an explicit user choice")
        explicitOff.preseedLaunchwrapperTransformerLdcRemap()
        assertFalse(explicitOff.remapLaunchwrapperTransformerLdcs,
            "explicit false assignment opts out of the pre-seed")
    }

    @Test
    fun `preseed is idempotent across repeated materializations`() {
        val spec = ModArtifactsStepSpec()
        spec.preseedLaunchwrapperTransformerLdcRemap()
        spec.preseedLaunchwrapperTransformerLdcRemap()
        assertTrue(spec.remapLaunchwrapperTransformerLdcs)
        assertEquals(emptySet<ModArtifactsStepSpec.CorrectnessFlag>(),
            spec.explicitlyConfiguredCorrectnessFlags,
            "repeated pipeline materialization must not corrupt explicitness tracking")
    }

    @Test
    fun `correctness explicitness survives java serialization`() {
        val disabled = ModArtifactsStepSpec()
        disabled.disableLaunchwrapperTransformerLdcRemap()
        val copy = roundTrip(disabled)
        assertTrue(ldcFlag in copy.explicitlyConfiguredCorrectnessFlags,
            "explicitness state must ride the serialized pipeline snapshot — " +
            "config-cache reuse must not reopen the door to pre-seeds")
        copy.preseedLaunchwrapperTransformerLdcRemap()
        assertFalse(copy.remapLaunchwrapperTransformerLdcs,
            "a deserialized explicit opt-out still blocks the pre-seed")

        val untouched = roundTrip(ModArtifactsStepSpec())
        assertEquals(emptySet<ModArtifactsStepSpec.CorrectnessFlag>(),
            untouched.explicitlyConfiguredCorrectnessFlags)
        untouched.preseedLaunchwrapperTransformerLdcRemap()
        assertTrue(untouched.remapLaunchwrapperTransformerLdcs)
    }

    // ---------- serialization ----------

    private fun roundTrip(spec: ModArtifactsStepSpec): ModArtifactsStepSpec {
        val bytes = ByteArrayOutputStream().also {
            ObjectOutputStream(it).use { out -> out.writeObject(spec) }
        }.toByteArray()
        return ObjectInputStream(ByteArrayInputStream(bytes)).use {
            it.readObject() as ModArtifactsStepSpec
        }
    }

    @Test
    fun `spec state survives java serialization`() {
        // every spec field is plain value state — the serialized
        // pipeline snapshot must carry the user's full declaration
        val spec = ModArtifactsStepSpec("uml").apply {
            off()
            disableRefmap()
            disableRefmap("a.mixins.json", "b.mixins.json")
            enableBaseMixin()
            enableJarModAgent()
            enableClassNameLdcRemap("com/example/Transformer")
            enableLaunchwrapperTransformerLdcRemap()
        }
        val copy = roundTrip(spec)
        assertEquals("uml", copy.engine)
        assertEquals(spec.mixinRemapOff, copy.mixinRemapOff)
        assertEquals(spec.emitRefmap, copy.emitRefmap)
        assertEquals(spec.noRefmapConfigs, copy.noRefmapConfigs)
        assertEquals(spec.forceMixinTables, copy.forceMixinTables)
        assertEquals(spec.classNameLdcClasses, copy.classNameLdcClasses)
        assertEquals(spec.remapLaunchwrapperTransformerLdcs,
            copy.remapLaunchwrapperTransformerLdcs)
        assertEquals(spec.remapClassNameLdcs, copy.remapClassNameLdcs)
    }

    @Test
    fun `defaults serialize cleanly too`() {
        val copy = roundTrip(ModArtifactsStepSpec())
        assertNull(copy.engine)
        assertFalse(copy.mixinRemapOff)
        assertTrue(copy.emitRefmap)
        assertEquals(emptySet<String>(), copy.noRefmapConfigs)
        assertEquals(emptySet<String>(), copy.forceMixinTables)
        assertEquals(emptySet<String>(), copy.classNameLdcClasses)
        assertFalse(copy.remapLaunchwrapperTransformerLdcs)
        assertFalse(copy.remapClassNameLdcs)
    }

    // ---------- DSL registration ----------

    @Test
    fun `modArtifacts registers on the jar surface with options`() {
        val ext = extension()
        ext.jar { surface ->
            surface.remap("uml")
            surface.modArtifacts { spec ->
                spec.off()
                spec.disableRefmap("a.mixins.json")
            }
        }
        val steps = ext.surface(RemapSurface.JAR).steps
        val spec = steps.filterIsInstance<ModArtifactsStepSpec>().single()
        assertTrue(spec.mixinRemapOff)
        assertEquals(setOf("a.mixins.json"), spec.noRefmapConfigs)
        // declaration order preserved — modArtifacts after remap
        assertTrue(steps.indexOf(spec) >
                steps.indexOfFirst { it is RemapStepSpec })
    }

    @Test
    fun `modArtifacts pins an engine when named`() {
        val ext = extension()
        ext.jar { it.modArtifacts("uml") }
        val spec = ext.surface(RemapSurface.JAR).steps
            .filterIsInstance<ModArtifactsStepSpec>().single()
        assertEquals("uml", spec.engine)
    }

    @Test
    fun `modArtifacts accepts a Groovy closure`() {
        val project = ProjectBuilder.builder().build()
        val surface = project.objects.newInstance(
            RemapSurfaceSpec::class.java, RemapSurface.JAR,
            project.objects)
        val closure = object : Closure<Any?>(this) {
            fun doCall() {
                (getDelegate() as ModArtifactsStepSpec).apply {
                    off()
                    enableMixinExtra()
                }
            }
        }
        val spec = surface.modArtifacts(closure)
        assertTrue(spec.mixinRemapOff)
        assertEquals(setOf("mixinextras"), spec.forceMixinTables)
        assertEquals(listOf<ModArtifactsStepSpec>(spec), surface.steps)
    }

    @Test
    fun `modArtifacts Groovy engine overload configures too`() {
        val project = ProjectBuilder.builder().build()
        val surface = project.objects.newInstance(
            RemapSurfaceSpec::class.java, RemapSurface.JAR,
            project.objects)
        val closure = object : Closure<Any?>(this) {
            fun doCall() {
                (getDelegate() as ModArtifactsStepSpec).disableRefmap()
            }
        }
        val spec = surface.modArtifacts("uml", closure)
        assertEquals("uml", spec.engine)
        assertFalse(spec.emitRefmap)
    }

}
