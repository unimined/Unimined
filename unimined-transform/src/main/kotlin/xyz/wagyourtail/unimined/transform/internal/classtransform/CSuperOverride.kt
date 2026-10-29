package xyz.wagyourtail.unimined.transform.internal.classtransform

/**
 * Copies the annotated transformer method into the transformed class
 * renamed after the *superclass* method with descriptor [value] —
 * for overriding super members whose names are only known at apply
 * time (obf/srg), where a fixed `@COverride` name can't be written.
 * The transformer method itself is not copied under its own name.
 *
 * Handled by [CSuperOverrideAnnotationHandler]; register via
 * `TransformerManager.addCustomAnnotationHandler(handler, HandlerPosition.PRE)`.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class CSuperOverride(
    /** JVM descriptor of the super method to override, e.g. `()Ljava/util/Set;`. */
    val value: String,
    /** only generate the override when the target doesn't already declare a method with this descriptor. */
    val ifAbsent: Boolean = false
)
