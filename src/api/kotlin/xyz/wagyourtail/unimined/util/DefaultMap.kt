package xyz.wagyourtail.unimined.util

// https://discuss.kotlinlang.org/t/map-withdefault-not-defaulting/7691/2
// doing it anyway
class DefaultMap<T, U>(val initializer: DefaultMap<T, U>.(T) -> U, val map: MutableMap<T, U> = mutableMapOf()) : MutableMap<T, U> by map {

    class NeverException : Exception()

    override fun get(key: T): U {
        if (!containsKey(key)) {
            map[key] = initializer(key)
        }
        @Suppress("UNCHECKED_CAST")
        return map[key] as U
    }

}

fun <T, U> defaultedMapOf(initializer: DefaultMap<T, U>.(T) -> U): DefaultMap<T, U> = DefaultMap(initializer)

fun <T, U> defaultedMapOf(map: MutableMap<T, U>, initializer: DefaultMap<T, U>.(T) -> U): DefaultMap<T, U> = DefaultMap(initializer, map)