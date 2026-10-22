package com.proximity.core

import com.proximity.contracts.CallPhase
import com.proximity.core.testing.FakeMediaEngine
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CallTest {

    @Test
    fun `call rings, connects, exchanges candidates and hangs up`() = runBlocking {
        val h = Harness()
        val callerMedia = mutableListOf<FakeMediaEngine>()
        val calleeMedia = mutableListOf<FakeMediaEngine>()
        val a = h.node("a", media = { FakeMediaEngine().also { callerMedia += it } })
        val b = h.node("b", media = { FakeMediaEngine().also { calleeMedia += it } })
        h.connect(a, b)
        a.core.profiles.watchNearbyPeers().awaitMatching { list -> list.any { it.userId == b.id } }
        b.core.profiles.watchNearbyPeers().awaitMatching { list -> list.any { it.userId == a.id } }

        val aCalls = assertNotNull(a.core.calls)
        val bCalls = assertNotNull(b.core.calls)
        aCalls.startCall(b.id)
        val ringing = bCalls.state.awaitMatching { it.phase == CallPhase.INCOMING }
        assertEquals(a.id, ringing.peerId)

        bCalls.accept()
        bCalls.state.awaitMatching { it.phase == CallPhase.ACTIVE }
        val active = aCalls.state.awaitMatching { it.phase == CallPhase.ACTIVE }
        assertNotNull(active.startedAt)
        assertEquals(listOf("candidate-from-caller"), calleeMedia.single().remoteCandidates)

        aCalls.hangup()
        bCalls.state.awaitMatching { it.phase == CallPhase.ENDED }
        assertTrue(callerMedia.single().closed)
        h.closeAll()
    }

    @Test
    fun `declined and out of range calls end`() = runBlocking {
        val h = Harness()
        val a = h.node("a", media = { FakeMediaEngine() })
        val b = h.node("b", media = { FakeMediaEngine() })
        val aCalls = assertNotNull(a.core.calls)
        val bCalls = assertNotNull(b.core.calls)
        assertFailsWith<IllegalStateException> { aCalls.startCall(b.id) }

        h.connect(a, b)
        a.core.profiles.watchNearbyPeers().awaitMatching { it.isNotEmpty() }
        b.core.profiles.watchNearbyPeers().awaitMatching { it.isNotEmpty() }
        aCalls.startCall(b.id)
        bCalls.state.awaitMatching { it.phase == CallPhase.INCOMING }
        bCalls.hangup()
        assertEquals("Declined", aCalls.state.awaitMatching { it.phase == CallPhase.ENDED }.error)

        aCalls.startCall(b.id)
        bCalls.state.awaitMatching { it.phase == CallPhase.INCOMING }
        h.disconnect(a, b)
        assertEquals("Peer went out of range", aCalls.state.awaitMatching { it.phase == CallPhase.ENDED }.error)
        h.closeAll()
    }
}
