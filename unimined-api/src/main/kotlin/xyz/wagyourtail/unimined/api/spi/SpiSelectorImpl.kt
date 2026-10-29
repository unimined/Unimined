package xyz.wagyourtail.unimined.api.spi

internal class SpiSelectorImpl<T : Supports<S>, S>(private val kind: String) : SpiSelector<T, S> {

    private val registered = mutableListOf<T>()

    override fun register(impl: T) {
        require(registered.none { it.name == impl.name }) {
            "duplicate $kind registered under name '${impl.name}'"
        }
        registered.add(impl)
    }

    override fun select(spec: S, override: String?): T {
        if (override != null) {
            return registered.firstOrNull { it.name == override }
                ?: throw SpiSelectionException(
                    "unknown $kind '$override'; registered: ${names()}"
                )
        }
        val candidates = registered.filter { it.supports(spec) }
        return when (candidates.size) {
            0 -> throw SpiSelectionException(
                "no $kind supports $spec; registered: ${names()}"
            )

            1 -> candidates.single()
            else -> {
                val top = candidates.maxOf { it.priority }
                candidates.singleOrNull { it.priority == top }
                    ?: throw SpiSelectionException(
                        "ambiguous $kind for $spec: " +
                                "${candidates.joinToString { it.name }}; " +
                                "disambiguate via DSL override"
                    )
            }
        }
    }

    private fun names() = registered.joinToString { it.name }.ifEmpty { "<none>" }

}
