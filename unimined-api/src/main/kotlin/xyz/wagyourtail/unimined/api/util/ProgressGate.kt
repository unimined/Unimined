package xyz.wagyourtail.unimined.api.util

import java.util.concurrent.atomic.AtomicLong

/**
 * Inline, thread-safe progress reporter for hot loops — call [tick] (or
 * [tickTo]) from the worker threads doing the work; [report] runs at most
 * once per [intervalMillis] from whichever thread crosses the boundary, so
 * there is no background thread and no log spam. Pair with a lifecycle
 * start/done line so long phases never leave the build log silent.
 */
class ProgressGate(
    private val intervalMillis: Long = 2000,
    private val report: (elapsedMillis: Long, done: Long) -> Unit
) {

    private val started = System.nanoTime()
    private val lastReport = AtomicLong(started)
    private val done = AtomicLong()

    /** Record [n] completed units of work; emits at most one report per interval. */
    fun tick(n: Long = 1) {
        tickTo(done.addAndGet(n))
    }

    /** Set the completed-unit count absolutely (when the worker knows its index). */
    fun tickTo(count: Long) {
        done.set(count)
        val now = System.nanoTime()
        val last = lastReport.get()
        if (now - last >= intervalMillis * 1_000_000 && lastReport.compareAndSet(last, now)) {
            report((now - started) / 1_000_000, count)
        }
    }

    /** Total recorded units — for the caller's done line. */
    fun count() = done.get()

    /** Wall-clock ms since construction. */
    fun elapsedMillis() = (System.nanoTime() - started) / 1_000_000

}
