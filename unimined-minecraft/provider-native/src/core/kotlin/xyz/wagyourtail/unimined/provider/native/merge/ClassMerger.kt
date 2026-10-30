package xyz.wagyourtail.unimined.provider.native.merge

import org.objectweb.asm.Attribute
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*
import xyz.wagyourtail.unimined.mapping.EnvType

class ClassMerger(
    val toMergedClass: (ClassNode, EnvType) -> Unit = { _, _ -> },
    val toMergedFields: (FieldNode, EnvType) -> Unit = { _, _ -> },
    val toMergedMethods: (MethodNode, EnvType) -> Unit = { _, _ -> }
) {
    fun accept(client: ClassNode?, server: ClassNode?): ClassNode {
        if (client == null) {
            if (server!!.access and Opcodes.ACC_INTERFACE == 0) {
                toMergedClass(server, EnvType.SERVER)
                return server
            } else {
                // make interface methods & fields sided
                server.methods?.forEach { toMergedMethods(it, EnvType.SERVER) }
                server.fields?.forEach { toMergedFields(it, EnvType.SERVER) }
                return server
            }
        }
        if (server == null) {
            if (client.access and Opcodes.ACC_INTERFACE == 0) {
                toMergedClass(client, EnvType.CLIENT)
                return client
            } else {
                // make interface methods & fields sided
                client.methods?.forEach { toMergedMethods(it, EnvType.CLIENT) }
                client.fields?.forEach { toMergedFields(it, EnvType.CLIENT) }
                return client
            }
        }
        if (!areClassMetadataEqual(client, server)) {
            throw IllegalArgumentException("Class metadata is not equal!")
        }
        // weaker access
        val merged = ClassNode()
        merged.version = client.version.coerceAtLeast(server.version)
        merged.access = selectWeakerAccess(client.access, server.access)
        merged.name = client.name
        merged.signature = client.signature
        merged.superName = client.superName
        merged.interfaces = client.interfaces?.toMutableSet()?.apply { addAll(server.interfaces ?: setOf()) }?.toList()
            ?: server.interfaces
        merged.sourceFile = client.sourceFile
        merged.sourceDebug = client.sourceDebug
        merged.module = client.module
        merged.outerClass = client.outerClass
        merged.outerMethod = client.outerMethod
        merged.outerMethodDesc = client.outerMethodDesc
        // merge client and server annotations
        merged.visibleAnnotations = client.visibleAnnotations?.toMutableList()?.apply {
            for (a in server.visibleAnnotations ?: listOf()) {
                if (!any { areAnnotationNodesEqual(it, a) }) {
                    add(a)
                }
            }
        } ?: server.visibleAnnotations
        merged.invisibleAnnotations = client.invisibleAnnotations?.toMutableList()?.apply {
            for (a in server.invisibleAnnotations ?: listOf()) {
                if (!any { areAnnotationNodesEqual(it, a) }) {
                    add(a)
                }
            }
        } ?: server.invisibleAnnotations
        merged.visibleTypeAnnotations = client.visibleTypeAnnotations?.toMutableList()?.apply {
            for (a in server.visibleTypeAnnotations ?: listOf()) {
                if (!any { areAnnotationNodesEqual(it, a) }) {
                    add(a)
                }
            }
        } ?: server.visibleTypeAnnotations
        merged.invisibleTypeAnnotations = client.invisibleTypeAnnotations?.toMutableList()?.apply {
            for (a in server.invisibleTypeAnnotations ?: listOf()) {
                if (!any { areAnnotationNodesEqual(it, a) }) {
                    add(a)
                }
            }
        } ?: server.invisibleTypeAnnotations
        merged.attrs = client.attrs?.toMutableList()?.apply {
            for (a in server.attrs ?: listOf()) {
                if (!any { areAttributesEqual(it, a) }) {
                    add(a)
                }
            }
        } ?: server.attrs
        // merge inner classes
        merged.innerClasses = client.innerClasses?.toMutableList()?.apply {
            for (a in server.innerClasses ?: listOf()) {
                if (!any { areInnerClassNodesEqual(it, a) }) {
                    add(a)
                }
            }
        } ?: server.innerClasses
        merged.nestHostClass = client.nestHostClass
        merged.nestMembers = client.nestMembers?.toMutableSet()
            ?.apply { addAll(server.nestMembers ?: setOf()) }
            ?.toList() ?: server.nestMembers
        merged.permittedSubclasses = client.permittedSubclasses?.toMutableSet()
            ?.apply { addAll(server.permittedSubclasses ?: setOf()) }
            ?.toList() ?: server.permittedSubclasses
        // merge record components
        merged.recordComponents = client.recordComponents?.toMutableList()?.apply {
            for (a in server.recordComponents ?: listOf()) {
                if (!any { areRecordComponentNodesEqual(it, a) }) {
                    add(a)
                }
            }
        } ?: server.recordComponents

        // merge fields
        val fields = client.fields.map { it to EnvType.CLIENT }.toMutableList()
        outer@ for (field in server.fields) {
            for (f in fields) {
                if (areFieldNodesEqual(f.first, field)) {
                    fields.remove(f)
                    field.access = selectWeakerAccess(f.first.access, field.access)
                    fields.add(field to EnvType.JOINED)
                    continue@outer
                }
            }
            fields.add(field to EnvType.SERVER)
        }
        fields.forEach { toMergedFields(it.first, it.second) }
        merged.fields = mutableListOf()
        for (f in fields) {
            merged.fields.add(f.first)
        }

        // merge methods
        val methods = client.methods.map { it to EnvType.CLIENT }.toMutableList()
        outer@ for (method in server.methods) {
            for (m in methods) {
                if (areMethodNodesEqual(m.first, method)) {
                    methods.remove(m)
                    method.access = selectWeakerAccess(m.first.access, method.access)
                    methods.add(method to EnvType.JOINED)
                    continue@outer
                }
            }
            methods.add(method to EnvType.SERVER)
        }
        methods.forEach { toMergedMethods(it.first, it.second) }
        merged.methods = mutableListOf()
        for (m in methods) {
            merged.methods.add(m.first)
        }
        toMergedClass(merged, EnvType.JOINED)
        return merged
    }

    companion object {
        fun areFieldNodesEqual(a: FieldNode, b: FieldNode): Boolean {
            if (a.name != b.name) return false
            if (a.desc != b.desc) return false
            if (a.value != b.value) return false
            // check static part of access
            if (a.access and Opcodes.ACC_STATIC != b.access and Opcodes.ACC_STATIC) return false
            val aVisibleAnnotations = a.visibleAnnotations?.toMutableList() ?: mutableListOf()
            val bVisibleAnnotations = b.visibleAnnotations?.toMutableList() ?: mutableListOf()
            if (aVisibleAnnotations.size != bVisibleAnnotations.size) return false
            aVisibleAnnotations.removeIf {
                bVisibleAnnotations.any { it2 ->
                    if (areAnnotationNodesEqual(it, it2)) {
                        bVisibleAnnotations.remove(it2)
                        true
                    } else {
                        false
                    }
                }
            }
            if (aVisibleAnnotations.isNotEmpty()) return false
            if (bVisibleAnnotations.isNotEmpty()) return false
            val aInvisibleAnnotations = a.invisibleAnnotations?.toMutableList() ?: mutableListOf()
            val bInvisibleAnnotations = b.invisibleAnnotations?.toMutableList() ?: mutableListOf()
            if (aInvisibleAnnotations.size != bInvisibleAnnotations.size) return false
            aInvisibleAnnotations.removeIf {
                bInvisibleAnnotations.any { it2 ->
                    if (areAnnotationNodesEqual(it, it2)) {
                        bInvisibleAnnotations.remove(it2)
                        true
                    } else {
                        false
                    }
                }
            }
            if (aInvisibleAnnotations.isNotEmpty()) return false
            if (bInvisibleAnnotations.isNotEmpty()) return false
            val aVisibleTypeAnnotations = a.visibleTypeAnnotations?.toMutableList() ?: mutableListOf()
            val bVisibleTypeAnnotations = b.visibleTypeAnnotations?.toMutableList() ?: mutableListOf()
            if (aVisibleTypeAnnotations.size != bVisibleTypeAnnotations.size) return false
            aVisibleTypeAnnotations.removeIf {
                bVisibleTypeAnnotations.any { it2 ->
                    if (areAnnotationNodesEqual(it, it2)) {
                        bVisibleTypeAnnotations.remove(it2)
                        true
                    } else {
                        false
                    }
                }
            }
            if (aVisibleTypeAnnotations.isNotEmpty()) return false
            if (bVisibleTypeAnnotations.isNotEmpty()) return false
            val aInvisibleTypeAnnotations = a.invisibleTypeAnnotations?.toMutableList() ?: mutableListOf()
            val bInvisibleTypeAnnotations = b.invisibleTypeAnnotations?.toMutableList() ?: mutableListOf()
            if (aInvisibleTypeAnnotations.size != bInvisibleTypeAnnotations.size) return false
            aInvisibleTypeAnnotations.removeIf {
                bInvisibleTypeAnnotations.any { it2 ->
                    if (areAnnotationNodesEqual(it, it2)) {
                        bInvisibleTypeAnnotations.remove(it2)
                        true
                    } else {
                        false
                    }
                }
            }
            if (aInvisibleTypeAnnotations.isNotEmpty()) return false
            if (bInvisibleTypeAnnotations.isNotEmpty()) return false
            val aAttributes = a.attrs?.toMutableList() ?: mutableListOf()
            val bAttributes = b.attrs?.toMutableList() ?: mutableListOf()
            if (aAttributes.size != bAttributes.size) return false
            aAttributes.removeIf {
                bAttributes.any { it2 ->
                    if (areAttributesEqual(it, it2)) {
                        bAttributes.remove(it2)
                        true
                    } else {
                        false
                    }
                }
            }
            if (aAttributes.isNotEmpty()) return false
            if (bAttributes.isNotEmpty()) return false
            return true
        }

        fun areMethodNodesEqual(a: MethodNode, b: MethodNode): Boolean {
            if (a.name != b.name) return false
            if (a.desc != b.desc) return false
            // check static part of access
            if (a.access and Opcodes.ACC_STATIC != b.access and Opcodes.ACC_STATIC) return false
//            val aParameters = a.parameters?.toMutableList() ?: mutableListOf()
//            val bParameters = b.parameters?.toMutableList() ?: mutableListOf()
//            if (aParameters.size != bParameters.size) return false
//            aParameters.removeIf {
//                bParameters.any { it2 ->
//                    areParamsEqual(it, it2)
//                }
//            }
//            if (aParameters.isNotEmpty()) return false
            val aVisibleAnnotations = a.visibleAnnotations?.toMutableList() ?: mutableListOf()
            val bVisibleAnnotations = b.visibleAnnotations?.toMutableList() ?: mutableListOf()
            if (aVisibleAnnotations.size != bVisibleAnnotations.size) return false
            aVisibleAnnotations.removeIf {
                bVisibleAnnotations.any { it2 ->
                    if (areAnnotationNodesEqual(it, it2)) {
                        bVisibleAnnotations.remove(it2)
                        true
                    } else {
                        false
                    }
                }
            }
            if (aVisibleAnnotations.isNotEmpty()) return false
            if (bVisibleAnnotations.isNotEmpty()) return false
            val aInvisibleAnnotations = a.invisibleAnnotations?.toMutableList() ?: mutableListOf()
            val bInvisibleAnnotations = b.invisibleAnnotations?.toMutableList() ?: mutableListOf()
            if (aInvisibleAnnotations.size != bInvisibleAnnotations.size) return false
            aInvisibleAnnotations.removeIf {
                bInvisibleAnnotations.any { it2 ->
                    if (areAnnotationNodesEqual(it, it2)) {
                        bInvisibleAnnotations.remove(it2)
                        true
                    } else {
                        false
                    }
                }
            }
            if (aInvisibleAnnotations.isNotEmpty()) return false
            if (bInvisibleAnnotations.isNotEmpty()) return false
            val aVisibleTypeAnnotations = a.visibleTypeAnnotations?.toMutableList() ?: mutableListOf()
            val bVisibleTypeAnnotations = b.visibleTypeAnnotations?.toMutableList() ?: mutableListOf()
            if (aVisibleTypeAnnotations.size != bVisibleTypeAnnotations.size) return false
            aVisibleTypeAnnotations.removeIf {
                bVisibleTypeAnnotations.any { it2 ->
                    if (areAnnotationNodesEqual(it, it2)) {
                        bVisibleTypeAnnotations.remove(it2)
                        true
                    } else {
                        false
                    }
                }
            }
            if (aVisibleTypeAnnotations.isNotEmpty()) return false
            if (bVisibleTypeAnnotations.isNotEmpty()) return false
            val aInvisibleTypeAnnotations = a.invisibleTypeAnnotations?.toMutableList() ?: mutableListOf()
            val bInvisibleTypeAnnotations = b.invisibleTypeAnnotations?.toMutableList() ?: mutableListOf()
            if (aInvisibleTypeAnnotations.size != bInvisibleTypeAnnotations.size) return false
            aInvisibleTypeAnnotations.removeIf {
                bInvisibleTypeAnnotations.any { it2 ->
                    if (areAnnotationNodesEqual(it, it2)) {
                        bInvisibleTypeAnnotations.remove(it2)
                        true
                    } else {
                        false
                    }
                }
            }
            if (aInvisibleTypeAnnotations.isNotEmpty()) return false
            if (bInvisibleTypeAnnotations.isNotEmpty()) return false
            val aAttributes = a.attrs?.toMutableList() ?: mutableListOf()
            val bAttributes = b.attrs?.toMutableList() ?: mutableListOf()
            if (aAttributes.size != bAttributes.size) return false
            aAttributes.removeIf {
                bAttributes.any { it2 ->
                    if (areAttributesEqual(it, it2)) {
                        bAttributes.remove(it2)
                        true
                    } else {
                        false
                    }
                }
            }
            if (aAttributes.isNotEmpty()) return false
            if (bAttributes.isNotEmpty()) return false

            // check content
            if (a.instructions.size() != b.instructions.size()) return false
            val aInstructions = a.instructions
            val bInstructions = b.instructions
            for (i in 0 until aInstructions.size()) {
                if (!areInstructionsEqual(
                        aInstructions[i],
                        bInstructions[i]
                    )
                ) throw IllegalStateException("Instructions are not equal: ${aInstructions[i]} != ${bInstructions[i]} at index $i in ${a.name} ${a.desc} (${a.instructions.size()} instructions) and ${b.name} ${b.desc} (${b.instructions.size()} instructions)")
            }

            return true
        }

        fun areAnnotationNodesEqual(a: AnnotationNode, b: AnnotationNode): Boolean {
            return a.desc == b.desc // TODO: check values, maybe?
        }

        fun areAttributesEqual(a: Attribute, b: Attribute): Boolean {
            return a.type == b.type // TODO: check values, maybe?
        }

        fun areInstructionsEqual(a: AbstractInsnNode, b: AbstractInsnNode): Boolean {
            if (a.opcode != b.opcode) return false
            if (a.type != b.type) return false
            return true // TODO: contents of instructions
        }

        fun selectWeakerAccess(a: Int, b: Int): Int {
            if (a == b) return a
            // should be final
            var access = a and Opcodes.ACC_FINAL.inv()
            if (a and Opcodes.ACC_FINAL != 0 && b and Opcodes.ACC_FINAL != 0) {
                access = access or Opcodes.ACC_FINAL
            }

            val aAccess = Access.from(a)
            val bAccess = Access.from(b)
            access = access and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED or Opcodes.ACC_PRIVATE).inv()
            access = access or Access.max(aAccess, bAccess).value

            // all other flags must be the same
            val other = (Opcodes.ACC_FINAL or Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED or Opcodes.ACC_PRIVATE).inv()
            if ((a and other) != (b and other)) {
                throw IllegalStateException("Other access is not the same: ${a.toString(16)} != ${b.toString(16)}")
            }
            return access
        }

        fun areClassMetadataEqual(a: ClassNode, b: ClassNode): Boolean {
            // require equal static
            if (a.access and Opcodes.ACC_STATIC != b.access and Opcodes.ACC_STATIC) return false
            if (a.name != b.name) return false
            if (a.superName != b.superName) return false
            if (a.outerClass != b.outerClass) return false
            if (a.outerMethod != b.outerMethod) return false
            if (a.outerMethodDesc != b.outerMethodDesc) return false
            if (a.nestHostClass != b.nestHostClass) return false
            return true
        }

        fun areInnerClassNodesEqual(a: InnerClassNode, b: InnerClassNode): Boolean {
            if (a.name != b.name) return false
            if (a.outerName != b.outerName) return false
            if (a.innerName != b.innerName) return false
            if (a.access != b.access) return false
            return true
        }

        fun areRecordComponentNodesEqual(a: RecordComponentNode, b: RecordComponentNode): Boolean {
            if (a.name != b.name) return false
            if (a.descriptor != b.descriptor) return false
            val aVisibleAnnotations = a.visibleAnnotations?.toMutableList() ?: mutableListOf()
            val bVisibleAnnotations = b.visibleAnnotations?.toMutableList() ?: mutableListOf()
            if (aVisibleAnnotations.size != bVisibleAnnotations.size) return false
            aVisibleAnnotations.removeIf {
                bVisibleAnnotations.any { it2 ->
                    if (areAnnotationNodesEqual(it, it2)) {
                        bVisibleAnnotations.remove(it2)
                        true
                    } else {
                        false
                    }
                }
            }
            if (aVisibleAnnotations.isNotEmpty()) return false
            if (bVisibleAnnotations.isNotEmpty()) return false
            val aInvisibleAnnotations = a.invisibleAnnotations?.toMutableList() ?: mutableListOf()
            val bInvisibleAnnotations = b.invisibleAnnotations?.toMutableList() ?: mutableListOf()
            if (aInvisibleAnnotations.size != bInvisibleAnnotations.size) return false
            aInvisibleAnnotations.removeIf {
                bInvisibleAnnotations.any { it2 ->
                    if (areAnnotationNodesEqual(it, it2)) {
                        bInvisibleAnnotations.remove(it2)
                        true
                    } else {
                        false
                    }
                }
            }
            if (aInvisibleAnnotations.isNotEmpty()) return false
            if (bInvisibleAnnotations.isNotEmpty()) return false
            val aVisibleTypeAnnotations = a.visibleTypeAnnotations?.toMutableList() ?: mutableListOf()
            val bVisibleTypeAnnotations = b.visibleTypeAnnotations?.toMutableList() ?: mutableListOf()
            if (aVisibleTypeAnnotations.size != bVisibleTypeAnnotations.size) return false
            aVisibleTypeAnnotations.removeIf {
                bVisibleTypeAnnotations.any { it2 ->
                    if (areAnnotationNodesEqual(it, it2)) {
                        bVisibleTypeAnnotations.remove(it2)
                        true
                    } else {
                        false
                    }
                }
            }
            if (aVisibleTypeAnnotations.isNotEmpty()) return false
            if (bVisibleTypeAnnotations.isNotEmpty()) return false
            val aInvisibleTypeAnnotations = a.invisibleTypeAnnotations?.toMutableList() ?: mutableListOf()
            val bInvisibleTypeAnnotations = b.invisibleTypeAnnotations?.toMutableList() ?: mutableListOf()
            if (aInvisibleTypeAnnotations.size != bInvisibleTypeAnnotations.size) return false
            aInvisibleTypeAnnotations.removeIf {
                bInvisibleTypeAnnotations.any { it2 ->
                    if (areAnnotationNodesEqual(it, it2)) {
                        bInvisibleTypeAnnotations.remove(it2)
                        true
                    } else {
                        false
                    }
                }
            }
            if (aInvisibleTypeAnnotations.isNotEmpty()) return false
            if (bInvisibleTypeAnnotations.isNotEmpty()) return false
            val aAttributes = a.attrs?.toMutableList() ?: mutableListOf()
            val bAttributes = b.attrs?.toMutableList() ?: mutableListOf()
            if (aAttributes.size != bAttributes.size) return false
            aAttributes.removeIf {
                bAttributes.any { it2 ->
                    if (areAttributesEqual(it, it2)) {
                        bAttributes.remove(it2)
                        true
                    } else {
                        false
                    }
                }
            }
            if (aAttributes.isNotEmpty()) return false
            if (bAttributes.isNotEmpty()) return false
            return true
        }
    }


    private enum class Access(val value: Int) {
        PRIVATE(Opcodes.ACC_PRIVATE),
        PACKAGE_PRIVATE(0),
        PROTECTED(Opcodes.ACC_PROTECTED),
        PUBLIC(Opcodes.ACC_PUBLIC),
        ;

        companion object {
            fun from(access: Int): Access {
                return when (access and (Opcodes.ACC_PRIVATE or Opcodes.ACC_PROTECTED or Opcodes.ACC_PUBLIC)) {
                    Opcodes.ACC_PRIVATE -> PRIVATE
                    Opcodes.ACC_PROTECTED -> PROTECTED
                    Opcodes.ACC_PUBLIC -> PUBLIC
                    else -> PACKAGE_PRIVATE
                }
            }

            fun max(a: Access, b: Access): Access {
                return values().last { it == a || it == b }
            }
        }

    }

}
