package xyz.wagyourtail.unimined.remapper.tiny

import net.fabricmc.tinyremapper.TinyRemapper
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import xyz.wagyourtail.unimined.api.access.AccessModel

/**
 * Applies [AccessModel] access changes to classes during tiny-remapper's
 * analysis pass — the `extraAnalyzeVisitor` hook Loom uses to let
 * widened members participate in member propagation (a mod overriding a
 * method widened by a declared access rule must be recognized as an
 * override even though the bytecode member is still private).
 */
internal class AccessModelAnalyzeVisitorProvider(
    private val rules: AccessModel.Rules
) : TinyRemapper.AnalyzeVisitorProvider {

    // tr's 3-arg signature is deprecated but remains the abstract
    // member — the InputTag overloads delegate to it
    @Deprecated("tr's base signature; the InputTag overloads delegate here")
    override fun insertAnalyzeVisitor(
        mrjVersion: Int, className: String, next: ClassVisitor
    ): ClassVisitor = object : ClassVisitor(Opcodes.ASM9, next) {

        override fun visit(
            version: Int, access: Int, name: String,
            signature: String?, superName: String?, interfaces: Array<out String>?
        ) = super.visit(
            version, adjust(access, rules.classChange(name)),
            name, signature, superName, interfaces
        )

        override fun visitMethod(
            access: Int, name: String, descriptor: String,
            signature: String?, exceptions: Array<out String>?
        ): MethodVisitor? = super.visitMethod(
            adjust(access, rules.methodChange(className, name, descriptor)),
            name, descriptor, signature, exceptions
        )

        override fun visitField(
            access: Int, name: String, descriptor: String,
            signature: String?, value: Any?
        ): FieldVisitor? = super.visitField(
            adjust(access, rules.fieldChange(className, name, descriptor)),
            name, descriptor, signature, value
        )

    }

    private companion object {

        fun adjust(access: Int, change: AccessModel.Change?): Int =
            change?.let { (access and it.clear.inv()) or it.set } ?: access

    }

}
