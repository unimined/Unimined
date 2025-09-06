package xyz.wagyourtail.unimined.internal.mapping.aw

import net.fabricmc.accesswidener.AccessWidenerReader
import net.fabricmc.accesswidener.AccessWidenerVisitor
import net.fabricmc.accesswidener.AccessWidenerWriter

class AccessWidenerMerger(private val namespace: String): AccessWidenerVisitor {
    // Contains the actual transforms. Class names are as class-file internal binary names (forward slash is used
    // instead of period as the package separator).
    private val classAccess: HashMap<String, Access> = HashMap()
    private val methodAccess: HashMap<EntryTriple, Access> = HashMap()
    private val fieldAccess: HashMap<EntryTriple, Access> = HashMap()

    fun writeToAccessWidenerWriter(): AccessWidenerWriter {
        val writer = AccessWidenerWriter()

        writer.visitHeader(namespace)

        classAccess.forEach { (name, access) ->
            access as ClassAccess

            when (access) {
                ClassAccess.DEFAULT -> {}
                ClassAccess.ACCESSIBLE -> {
                    writer.visitClass(name, AccessWidenerReader.AccessType.ACCESSIBLE, false)
                }

                ClassAccess.EXTENDABLE -> {
                    writer.visitClass(name, AccessWidenerReader.AccessType.EXTENDABLE, false)
                }

                ClassAccess.ACCESSIBLE_EXTENDABLE -> {
                    writer.visitClass(name, AccessWidenerReader.AccessType.ACCESSIBLE, false)
                    writer.visitClass(name, AccessWidenerReader.AccessType.EXTENDABLE, false)
                }
            }
        }

        methodAccess.forEach { (triple, access) ->
            access as MethodAccess

            when (access) {
                MethodAccess.DEFAULT -> {}
                MethodAccess.ACCESSIBLE -> {
                    writer.visitMethod(
                        triple.owner,
                        triple.name,
                        triple.desc,
                        AccessWidenerReader.AccessType.ACCESSIBLE,
                        false
                    )
                }

                MethodAccess.EXTENDABLE -> {
                    writer.visitMethod(
                        triple.owner,
                        triple.name,
                        triple.desc,
                        AccessWidenerReader.AccessType.EXTENDABLE,
                        false
                    )
                }

                MethodAccess.ACCESSIBLE_EXTENDABLE -> {
                    writer.visitMethod(
                        triple.owner,
                        triple.name,
                        triple.desc,
                        AccessWidenerReader.AccessType.ACCESSIBLE,
                        false
                    )
                    writer.visitMethod(
                        triple.owner,
                        triple.name,
                        triple.desc,
                        AccessWidenerReader.AccessType.EXTENDABLE,
                        false
                    )
                }
            }
        }

        fieldAccess.forEach { (triple, access) ->
            access as FieldAccess

            when (access) {
                FieldAccess.DEFAULT -> {}
                FieldAccess.ACCESSIBLE -> {
                    writer.visitField(
                        triple.owner,
                        triple.name,
                        triple.desc,
                        AccessWidenerReader.AccessType.ACCESSIBLE,
                        false
                    )
                }

                FieldAccess.MUTABLE -> {
                    writer.visitField(
                        triple.owner,
                        triple.name,
                        triple.desc,
                        AccessWidenerReader.AccessType.MUTABLE,
                        false
                    )
                }

                FieldAccess.ACCESSIBLE_MUTABLE -> {
                    writer.visitField(
                        triple.owner,
                        triple.name,
                        triple.desc,
                        AccessWidenerReader.AccessType.ACCESSIBLE,
                        false
                    )
                    writer.visitField(
                        triple.owner,
                        triple.name,
                        triple.desc,
                        AccessWidenerReader.AccessType.MUTABLE,
                        false
                    )
                }
            }
        }

        return writer
    }

    override fun visitHeader(namespace: String) {
        if (this.namespace != namespace) {
            throw RuntimeException(String.format("Namespace mismatch, expected %s got %s", this.namespace, namespace))
        }
    }

    override fun visitClass(name: String, access: AccessWidenerReader.AccessType, transitive: Boolean) {
        classAccess[name] = applyAccess(access, classAccess.getOrDefault(name, ClassAccess.DEFAULT), null)
    }

    override fun visitMethod(
        owner: String,
        name: String,
        descriptor: String,
        access: AccessWidenerReader.AccessType,
        transitive: Boolean
    ) {
        addOrMerge(
            methodAccess,
            EntryTriple(owner, name, descriptor),
            access,
            MethodAccess.DEFAULT
        )
    }

    override fun visitField(
        owner: String,
        name: String,
        descriptor: String,
        access: AccessWidenerReader.AccessType,
        transitive: Boolean
    ) {
        addOrMerge(
            fieldAccess,
            EntryTriple(owner, name, descriptor),
            access,
            FieldAccess.DEFAULT
        )
    }

    private fun addOrMerge(
        map: MutableMap<EntryTriple, Access>,
        entry: EntryTriple,
        access: AccessWidenerReader.AccessType,
        defaultAccess: Access
    ) {
        map[entry] = applyAccess(access, map.getOrDefault(entry, defaultAccess), entry)
    }

    private fun applyAccess(
        input: AccessWidenerReader.AccessType,
        access: Access,
        entryTriple: EntryTriple?
    ): Access {
        return when (input) {
            AccessWidenerReader.AccessType.ACCESSIBLE -> {
                makeClassAccessible(entryTriple)
                access.makeAccessible()
            }

            AccessWidenerReader.AccessType.EXTENDABLE -> {
                makeClassExtendable(entryTriple)
                access.makeExtendable()
            }

            AccessWidenerReader.AccessType.MUTABLE -> access.makeMutable()
        }
    }

    private fun makeClassAccessible(entryTriple: EntryTriple?) {
        if (entryTriple == null) return
        classAccess[entryTriple.owner] = applyAccess(
            AccessWidenerReader.AccessType.ACCESSIBLE,
            classAccess.getOrDefault(entryTriple.owner, ClassAccess.DEFAULT),
            null
        )
    }

    private fun makeClassExtendable(entryTriple: EntryTriple?) {
        if (entryTriple == null) return
        classAccess[entryTriple.owner] = applyAccess(
            AccessWidenerReader.AccessType.EXTENDABLE,
            classAccess.getOrDefault(entryTriple.owner, ClassAccess.DEFAULT),
            null
        )
    }

    class EntryTriple(val owner: String, val name: String, val desc: String) {
        override fun toString(): String {
            return "EntryTriple{owner=$owner,name=$name,desc=$desc}"
        }

        override fun hashCode(): Int {
            return owner.hashCode() * 37 + name.hashCode() * 19 + desc.hashCode()
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as EntryTriple

            if (owner != other.owner) return false
            if (name != other.name) return false
            if (desc != other.desc) return false

            return true
        }
    }

    interface Access {
        fun makeAccessible(): Access
        fun makeExtendable(): Access
        fun makeMutable(): Access
    }

    enum class ClassAccess: Access {
        DEFAULT,
        ACCESSIBLE,
        EXTENDABLE,
        ACCESSIBLE_EXTENDABLE;

        override fun makeAccessible(): Access {
            return if (this == EXTENDABLE || this == ACCESSIBLE_EXTENDABLE) {
                ACCESSIBLE_EXTENDABLE
            } else ACCESSIBLE
        }

        override fun makeExtendable(): Access {
            return if (this == ACCESSIBLE || this == ACCESSIBLE_EXTENDABLE) {
                ACCESSIBLE_EXTENDABLE
            } else EXTENDABLE
        }

        override fun makeMutable(): Access {
            throw UnsupportedOperationException("Classes cannot be made mutable")
        }
    }

    enum class MethodAccess: Access {
        DEFAULT,
        ACCESSIBLE,
        EXTENDABLE,
        ACCESSIBLE_EXTENDABLE;

        override fun makeAccessible(): Access {
            return if (this == EXTENDABLE || this == ACCESSIBLE_EXTENDABLE) {
                ACCESSIBLE_EXTENDABLE
            } else ACCESSIBLE
        }

        override fun makeExtendable(): Access {
            return if (this == ACCESSIBLE || this == ACCESSIBLE_EXTENDABLE) {
                ACCESSIBLE_EXTENDABLE
            } else EXTENDABLE
        }

        override fun makeMutable(): Access {
            throw UnsupportedOperationException("Methods cannot be made mutable")
        }
    }

    enum class FieldAccess: Access {
        DEFAULT,
        ACCESSIBLE,
        MUTABLE,
        ACCESSIBLE_MUTABLE;

        override fun makeAccessible(): Access {
            return if (this == MUTABLE || this == ACCESSIBLE_MUTABLE) {
                ACCESSIBLE_MUTABLE
            } else ACCESSIBLE
        }

        override fun makeExtendable(): Access {
            throw UnsupportedOperationException("Fields cannot be made extendable")
        }

        override fun makeMutable(): Access {
            return if (this == ACCESSIBLE || this == ACCESSIBLE_MUTABLE) {
                ACCESSIBLE_MUTABLE
            } else MUTABLE
        }
    }
}