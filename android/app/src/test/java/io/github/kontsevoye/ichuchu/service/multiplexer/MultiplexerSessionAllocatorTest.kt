package io.github.kontsevoye.ichuchu.service.multiplexer

import org.junit.Assert.assertEquals
import org.junit.Test

class MultiplexerSessionAllocatorTest {
    @Test
    fun reusesHighestDetachedIchuchuSessionOutsideLocalTabs() {
        val reusable = MultiplexerSessionAllocator.reusableDetachedIchuchuSessionName(
            remoteSessions = listOf(
                RemoteMultiplexerSession("ichuchu-1", attached = false),
                RemoteMultiplexerSession("ichuchu-2", attached = false),
                RemoteMultiplexerSession("ichuchu-3", attached = false),
                RemoteMultiplexerSession("ichuchu-5", attached = true),
                RemoteMultiplexerSession("ichuchu-7-extra", attached = false),
            ),
            localSessionNames = listOf("ichuchu-3"),
        )

        assertEquals("ichuchu-2", reusable)
    }

    @Test
    fun doesNotReuseAttachedOrLocalIchuchuSessions() {
        val reusable = MultiplexerSessionAllocator.reusableDetachedIchuchuSessionName(
            remoteSessions = listOf(
                RemoteMultiplexerSession("ichuchu-1", attached = true),
                RemoteMultiplexerSession("ichuchu-2", attached = false),
                RemoteMultiplexerSession("work", attached = false),
            ),
            localSessionNames = listOf("ichuchu-2"),
        )

        assertEquals(null, reusable)
    }

    @Test
    fun skipsUsedRemoteAndLocalNames() {
        val next = MultiplexerSessionAllocator.nextIchuchuSessionName(
            remoteSessions = listOf(
                RemoteMultiplexerSession("ichuchu-1", attached = false),
                RemoteMultiplexerSession("main", attached = true),
                RemoteMultiplexerSession("ichuchu-3", attached = false),
            ),
            localSessionNames = listOf("ichuchu-2"),
        )

        assertEquals("ichuchu-4", next)
    }

    @Test
    fun ignoresPrefixCollisions() {
        val next = MultiplexerSessionAllocator.nextIchuchuSessionName(
            remoteSessions = listOf(RemoteMultiplexerSession("ichuchu-2", attached = false)),
            localSessionNames = listOf("ichuchu-1-extra"),
        )

        assertEquals("ichuchu-1", next)
    }
}
