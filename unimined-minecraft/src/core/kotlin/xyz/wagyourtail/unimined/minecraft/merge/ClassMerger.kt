package xyz.wagyourtail.unimined.minecraft.merge

import org.objectweb.asm.Attribute
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*
import xyz.wagyourtail.unimined.mapping.EnvType

class ClassMerger(
    val toMergedClass: (ClassNode, EnvType) -> Unit = { _, _ -> },
    val toMergedFields: (FieldNode, EnvType) -> Unit = { _, _ -> },
    val toMergedMethods: (MethodNode, EnvType) -> Unit = { _, _ -> },
    /** conflict-resolution seam — divergent-member strategies
     *  (dispatch/rename) supply a policy; see [ClassMergePolicy] */
    val policy: ClassMergePolicy = ClassMergePolicy.STRICT
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
        val metadataDiffs = ClassNodeEquality.classMetadataDiffs(client, server)
        if (metadataDiffs.isNotEmpty()) {
            return policy.resolveClassMetadata(metadataDiffs, client, server)
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
                if (!any { ClassNodeEquality.areAnnotationNodesEqual(it, a) }) {
                    add(a)
                }
            }
        } ?: server.visibleAnnotations
        merged.invisibleAnnotations = client.invisibleAnnotations?.toMutableList()?.apply {
            for (a in server.invisibleAnnotations ?: listOf()) {
                if (!any { ClassNodeEquality.areAnnotationNodesEqual(it, a) }) {
                    add(a)
                }
            }
        } ?: server.invisibleAnnotations
        merged.visibleTypeAnnotations = client.visibleTypeAnnotations?.toMutableList()?.apply {
            for (a in server.visibleTypeAnnotations ?: listOf()) {
                if (!any { ClassNodeEquality.areAnnotationNodesEqual(it, a) }) {
                    add(a)
                }
            }
        } ?: server.visibleTypeAnnotations
        merged.invisibleTypeAnnotations = client.invisibleTypeAnnotations?.toMutableList()?.apply {
            for (a in server.invisibleTypeAnnotations ?: listOf()) {
                if (!any { ClassNodeEquality.areAnnotationNodesEqual(it, a) }) {
                    add(a)
                }
            }
        } ?: server.invisibleTypeAnnotations
        merged.attrs = client.attrs?.toMutableList()?.apply {
            for (a in server.attrs ?: listOf()) {
                if (!any { ClassNodeEquality.areAttributesEqual(it, a) }) {
                    add(a)
                }
            }
        } ?: server.attrs
        // merge inner classes
        merged.innerClasses = client.innerClasses?.toMutableList()?.apply {
            for (a in server.innerClasses ?: listOf()) {
                if (!any { ClassNodeEquality.areInnerClassNodesEqual(it, a) }) {
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
                if (!any { ClassNodeEquality.areRecordComponentNodesEqual(it, a) }) {
                    add(a)
                }
            }
        } ?: server.recordComponents

        // merge fields
        val fields = client.fields.map { it to EnvType.CLIENT }.toMutableList()
        outer@ for (field in server.fields) {
            for (f in fields) {
                if (f.first.name == field.name && f.first.desc == field.desc) {
                    fields.remove(f)
                    fields.add(policy.resolveField(f.first, field) to EnvType.JOINED)
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

        // merge methods — same name+desc is the same method slot:
        // divergent impls are legitimate on split-era versions
        // (client code carries rendering calls the server lacks);
        // server wins conflicts, client fills server-absent slots
        val methods = client.methods.map { it to EnvType.CLIENT }.toMutableList()
        outer@ for (method in server.methods) {
            for (m in methods) {
                if (m.first.name == method.name && m.first.desc == method.desc) {
                    methods.remove(m)
                    methods.add(policy.resolveMethod(m.first, method) to EnvType.JOINED)
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

            // all other flags must be the same — divergent member
            // flags (e.g. static on one side only) mean the merged
            // node keeps the server's flags, matching the kept node
            val other = (Opcodes.ACC_FINAL or Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED or Opcodes.ACC_PRIVATE).inv()
            if ((a and other) != (b and other)) {
                return b
            }
            return access
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
