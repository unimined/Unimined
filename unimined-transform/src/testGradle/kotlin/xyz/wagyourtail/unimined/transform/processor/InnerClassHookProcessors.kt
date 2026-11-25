package xyz.wagyourtail.unimined.transform.processor

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

/**
 * ServiceLoader'd test [JarProcessor] fakes for the `jar-processors`
 * step's inner-class capability hook. Payload-driven so one
 * registration serves many scenarios; the payload passes through
 * serialize/deserialize verbatim.
 *
 * Payload grammar — directives separated by `;`:
 *  * `add:<entryPath>=<thisClass>` — write a class entry at
 *    `<entryPath>` whose constant pool declares `<thisClass>` (the two
 *    may disagree — the FG2 `ain$22` shape)
 *  * `move:<from>=<to>` — relocation-style: drop entry `<from>` and
 *    write `<to>` carrying the relocated `this_class` (real relocation
 *    rewrites the bytecode, so the moved entry's constant pool declares
 *    the new name)
 *
 * The concrete no-arg subclasses exist because the step resolves
 * processors by name through the ServiceLoader registry, and the
 * run-once marker keys on the name — multi-processor ordering tests
 * need distinct names per capable instance.
 */
abstract class PayloadJarProcessor : JarProcessor {

    class Spec(val payload: String) : JarProcessor.JarProcessorSpec

    override fun buildSpec(context: JarProcessor.SpecContext) = Spec("test")

    override fun serializeSpec(spec: JarProcessor.JarProcessorSpec) = (spec as Spec).payload

    override fun deserializeSpec(payload: String) = Spec(payload)

    override fun processJar(
        jar: Path, spec: JarProcessor.JarProcessorSpec,
        context: JarProcessor.ProcessorContext
    ) {
        val directives = (spec as Spec).payload.split(';')
            .filter { it.isNotBlank() }
        val entries = JarFile(jar.toFile()).use { j ->
            j.entries().asSequence()
                .filter { !it.isDirectory }
                .map { it.name to j.getInputStream(it).readBytes() }
                .toMutableList()
        }
        for (directive in directives) {
            val op = directive.substringBefore(':')
            val lhs = directive.substringAfter(':').substringBefore('=')
            val rhs = directive.substringAfter('=')
            when (op) {
                "add" -> entries += lhs to minimalClassBytes(rhs)
                "move" -> {
                    // real relocation rewrites the bytecode, so the
                    // moved entry carries the relocated this_class —
                    // synthesize rather than copy
                    val idx = entries.indexOfFirst { it.first == lhs }
                    if (idx >= 0) {
                        entries.removeAt(idx)
                        entries += rhs to minimalClassBytes(rhs.removeSuffix(".class"))
                    }
                }
                else -> error("unknown test directive '$op'")
            }
        }
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            for ((entryName, bytes) in entries) {
                out.putNextEntry(JarEntry(entryName))
                out.write(bytes)
                out.closeEntry()
            }
        }
    }

    companion object {

        /** minimal class bytes whose constant pool declares [thisClass]
         *  — magic + version + two-entry pool + access/this header,
         *  exactly what the augmentation's constant-pool walk reads. */
        fun minimalClassBytes(thisClass: String): ByteArray {
            val name = thisClass.toByteArray(Charsets.UTF_8)
            val bos = ByteArrayOutputStream()
            val w = DataOutputStream(bos)
            w.writeInt(0xCAFEBABE.toInt())
            w.writeShort(0) // minor version
            w.writeShort(52) // major version
            w.writeShort(3) // constant_pool_count (entries #1, #2)
            w.writeByte(1) // #1 CONSTANT_Utf8
            w.writeShort(name.size)
            w.write(name)
            w.writeByte(7) // #2 CONSTANT_Class → #1
            w.writeShort(1)
            w.writeShort(0x0021) // access_flags
            w.writeShort(2) // this_class → #2
            w.writeShort(0) // super_class (none — never read)
            w.flush()
            return bos.toByteArray()
        }

    }

}

/** pre-remap, capability opted in — the jar-merge/binary-patch shape. */
class CapablePreRemapProcessor : PayloadJarProcessor() {
    override val name = "test-capable-adder"
    override val preRemap = true
    override val contributesPatchAddedClasses = true
}

/** a second capable pre-remap processor — multi-processor ordering
 *  tests need distinct names (the run-once marker keys on the name). */
class SecondCapablePreRemapProcessor : PayloadJarProcessor() {
    override val name = "test-capable-adder-2"
    override val preRemap = true
    override val contributesPatchAddedClasses = true
}

/** pre-remap but NOT opted in — additions must produce no rows. */
class NonContributingPreRemapProcessor : PayloadJarProcessor() {
    override val name = "test-plain-adder"
    override val preRemap = true
}

/** opted in but post-remap — the hook must not fire: post-remap
 *  additions are already dev-namespace. */
class CapablePostRemapProcessor : PayloadJarProcessor() {
    override val name = "test-post-adder"
    override val preRemap = false
    override val contributesPatchAddedClasses = true
}

/** relocation-only shape (pre-remap, not opted in) — moved entries
 *  must never produce false-positive rows. */
class RelocationStyleProcessor : PayloadJarProcessor() {
    override val name = "test-relocator"
    override val preRemap = true
}
