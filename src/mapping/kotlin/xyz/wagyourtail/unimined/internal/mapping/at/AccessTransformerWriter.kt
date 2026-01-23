package xyz.wagyourtail.unimined.internal.mapping.at
//
//import net.fabricmc.accesswidener.AccessWidenerReader
//import net.fabricmc.accesswidener.AccessWidenerVisitor
//import java.io.BufferedWriter
//
//class AccessTransformerWriter(private val output: BufferedWriter): AccessWidenerVisitor, AutoCloseable {
//    private val classes = mutableMapOf<String, MutableSet<AccessType>>()
//    private val fields = mutableMapOf<String, MutableSet<AccessType>>()
//    private val methods = mutableMapOf<String, MutableSet<AccessType>>()
//
//    override fun visitClass(name: String, access: AccessWidenerReader.AccessType, transitive: Boolean) {
//        classes.getOrPut(name.replace("/", ".")) { mutableSetOf() }.addAll(
//            when (access) {
//                AccessWidenerReader.AccessType.ACCESSIBLE -> setOf(AccessType.PUBLIC)
//                AccessWidenerReader.AccessType.EXTENDABLE -> setOf(AccessType.PUBLIC, AccessType.UNFINAL)
//                AccessWidenerReader.AccessType.MUTABLE -> throw UnsupportedOperationException("AccessWidenerReader.AccessType.MUTABLE is not supported for classes")
//            }
//        )
//    }
//
//    override fun visitField(
//        owner: String,
//        name: String,
//        descriptor: String,
//        access: AccessWidenerReader.AccessType,
//        transitive: Boolean
//    ) {
//        val desc = "${owner.replace("/", ".")} $name"
//        fields.getOrPut(desc) { mutableSetOf() }.addAll(
//            when (access) {
//                AccessWidenerReader.AccessType.ACCESSIBLE -> setOf(AccessType.PUBLIC)
//                AccessWidenerReader.AccessType.EXTENDABLE -> throw UnsupportedOperationException("AccessWidenerReader.AccessType.EXTENDABLE is not supported for fields")
//                AccessWidenerReader.AccessType.MUTABLE -> setOf(AccessType.UNFINAL)
//            }
//        )
//    }
//
//    override fun visitMethod(
//        owner: String,
//        name: String,
//        descriptor: String,
//        access: AccessWidenerReader.AccessType,
//        transitive: Boolean
//    ) {
//        val desc = "${owner.replace("/", ".")} $name$descriptor"
//        methods.getOrPut(desc) { mutableSetOf() }.addAll(
//            when (access) {
//                AccessWidenerReader.AccessType.ACCESSIBLE -> setOf(AccessType.PUBLIC)
//                AccessWidenerReader.AccessType.EXTENDABLE -> setOf(AccessType.PROTECTED, AccessType.UNFINAL)
//                AccessWidenerReader.AccessType.MUTABLE -> throw UnsupportedOperationException("AccessWidenerReader.AccessType.MUTABLE is not supported for methods")
//            }
//        )
//    }
//
//    enum class AccessType(val str: String) {
//        PUBLIC("public"),
//        PROTECTED("protected"),
//        UNFINAL("-f"),
//    }
//
//    override fun close() {
//        classes.forEach { (name, access) ->
//            if (access == setOf(AccessType.UNFINAL)) access.add(AccessType.PUBLIC)
//            if (access.containsAll(setOf(AccessType.PUBLIC, AccessType.PROTECTED))) access.remove(AccessType.PROTECTED)
//            output.write(access.sortedBy { it.ordinal }.joinToString("") { it.str } + " $name\n")
//        }
//        fields.forEach { (desc, access) ->
//            if (access == setOf(AccessType.UNFINAL)) access.add(AccessType.PUBLIC)
//            if (access.containsAll(setOf(AccessType.PUBLIC, AccessType.PROTECTED))) access.remove(AccessType.PROTECTED)
//            output.write(access.sortedBy { it.ordinal }.joinToString("") { it.str } + " $desc\n")
//        }
//        methods.forEach { (desc, access) ->
//            if (access == setOf(AccessType.UNFINAL)) access.add(AccessType.PUBLIC)
//            if (access.containsAll(setOf(AccessType.PUBLIC, AccessType.PROTECTED))) access.remove(AccessType.PROTECTED)
//            output.write(access.sortedBy { it.ordinal }.joinToString("") { it.str } + " $desc\n")
//        }
//        output.close()
//    }
//}