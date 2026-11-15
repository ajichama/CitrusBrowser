package io.github.ajichama.citrusbrowser.adblock.core

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AdBlockLoadGateTest {
    @Test
    fun overlappingLoadsReleaseBothTicketsAndOnlyPublishTheNewestResult() {
        val gate = AdBlockLoadGate()
        val first = gate.begin()
        val second = gate.begin()
        var published = ""
        gate.publish(second) { published = "new" }
        gate.publish(first) { published = "old" }
        assertEquals("new", published)
        gate.finish(first)
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor.submit { first.await() }.get(1, TimeUnit.SECONDS)
            gate.finish(second)
            executor.submit { second.await(); gate.await() }.get(1, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun failedNewestLoadDoesNotPublishAnOlderResultOrLeaveAWaiterBlocked() {
        val gate = AdBlockLoadGate()
        val first = gate.begin()
        val second = gate.begin()
        gate.finish(second)
        var changed = false
        gate.publish(first) { changed = true }
        gate.finish(first)
        assertFalse(changed)
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor.submit { first.await(); second.await(); gate.await() }.get(1, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }
}
