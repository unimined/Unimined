package xyz.wagyourtail.unimined.api.spi

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [IdeSyncTasks] registry semantics: contributions are per-project
 * and returned in registration order.
 */
class IdeSyncTasksTest {

    @Test
    fun `contributions are per-project and ordered`() {
        val a = ProjectBuilder.builder().build()
        val b = ProjectBuilder.builder().build()
        val t1 = a.tasks.register("t1")
        val t2 = a.tasks.register("t2")
        val t3 = b.tasks.register("t3")
        IdeSyncTasks.contribute(a, t1)
        IdeSyncTasks.contribute(b, t3)
        IdeSyncTasks.contribute(a, t2)
        assertEquals(listOf(t1, t2), IdeSyncTasks.contributions(a))
        assertEquals(listOf(t3), IdeSyncTasks.contributions(b))
        assertTrue(
            IdeSyncTasks.contributions(ProjectBuilder.builder().build())
                .isEmpty()
        )
    }

}
