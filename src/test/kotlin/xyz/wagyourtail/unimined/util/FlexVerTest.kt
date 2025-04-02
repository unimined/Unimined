package xyz.wagyourtail.unimined.util

import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import xyz.wagyourtail.unimined.util.FlexVerComparator.compare
import xyz.wagyourtail.unimined.util.FlexVerComparator.decompose
import java.io.IOException
import java.nio.file.Files
import java.util.*
import java.util.stream.Stream
import kotlin.io.path.toPath
import kotlin.test.assertEquals

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FlexVerTest {
    companion object {
        val ENABLED_TESTS: Array<String> = arrayOf("test_vectors.txt", "large.txt")
    }

    @ParameterizedTest(name = "{0} {2} {1}")
    @MethodSource("getEqualityTests")
    fun testEquality(a: String, b: String, expected: Ordering) {
        println((formatDecomposedString(a) + " " + expected) + " " + formatDecomposedString(b))

        val c: Ordering = Ordering.fromComparison(compare(a, b))
        val c2: Ordering = Ordering.fromComparison(compare(b, a))


        // When inverting the operands we're comparing, the ordering should be inverted too
        assertEquals(c2.invert(), c, "Comparison method violates its general contract! ($a <=> $b is not commutative)")

        assertEquals(expected, c, "Ordering.fromComparison produced $a $c $b")
    }

    private fun formatDecomposedString(str: String): String {
        val sb = StringBuilder("[")
        for (c in decompose(str)) {
            val color: Int = when (c) {
                is FlexVerComparator.NumericVersionComponent -> 96
                is FlexVerComparator.SemVerPrereleaseVersionComponent -> 93
                else -> 95
            }
            sb.append("\u001B[").append(color).append("m")
            sb.append(c.toString())
        }
        if (str.contains("+")) {
            sb.append("\u001B[90m")
            sb.append(str.substring(str.indexOf('+')))
        }
        sb.append("\u001B[0m]")
        return sb.toString()
    }

    @Throws(IOException::class)
    fun getEqualityTests(): Stream<Arguments> {
        val lines: MutableList<String> = ArrayList()

        for (test in ENABLED_TESTS) {
            lines.addAll(Files.readAllLines(this::class.java.getResource("/$test")?.toURI()?.toPath()))
        }

        return lines.stream()
            .filter { line: String -> !line.startsWith("#") }
            .filter { line: String -> line.isNotEmpty() }
            .map { line ->
                val split = line.split(" ".toRegex()).toTypedArray()
                require(split.size == 3) { "Line formatted incorrectly, expected 2 spaces: $line" }
                Arguments.of(split[0], split[2], Ordering.fromString(split[1]))
            }
    }
}

enum class Ordering(val charRepresentation: String) {
    LESS("<"),
    EQUAL("="),
    GREATER(">");

    override fun toString(): String {
        return charRepresentation
    }

    fun invert(): Ordering {
        return when (this) {
            LESS -> GREATER
            EQUAL -> this
            GREATER -> LESS
        }
    }

    companion object {
        fun fromString(str: String): Ordering {
            return Arrays.stream(values())
                .filter { ord -> ord.charRepresentation == str }
                .findFirst()
                .orElseThrow { NoSuchElementException("'$str' is not a valid ordering") }
        }

        /**
         * Converts an integer returned by a method like [FlexVerComparator.compare] to an [Ordering]
         */
        fun fromComparison(i: Int): Ordering {
            return when {
                i < 0 -> LESS
                i == 0 -> EQUAL
                else -> GREATER
            }
        }
    }
}
