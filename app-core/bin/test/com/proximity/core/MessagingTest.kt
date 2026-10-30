package com.proximity.core

import com.proximity.contracts.MessageStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MessagingTest {

    @Test
    fun `direct message is end to end encrypted and acknowledged`() = runBlocking {
        val h = Harness()
        val a = h.node("a")
        val b = h.node("b")
        h.connect(a, b)
        a.core.profiles.watchNearbyPeers().awaitMatching { list -> list.any { it.userId == b.id } }

        val sent = a.core.messages.send(b.id, "hi b")
        assertEquals(MessageStatus.PENDING, sent.status)

        val inbox = b.core.messages.watchInbox().awaitMatching { it.isNotEmpty() }.single()
        assertEquals(a.id, inbox.peerId)
        assertEquals("hi b", inbox.lastMessage.text)
        assertEquals(MessageStatus.RECEIVED, inbox.lastMessage.status)

        a.core.messages.watchConversation(b.id).awaitMatching { it.single().status == MessageStatus.DELIVERED }
        h.closeAll()
    }

    @Test
    fun `message is stored and forwarded through a carrier who cannot read it`() = runBlocking {
        val h = Harness()
        val a = h.node("a")
        val carrier = h.node("carrier")
        val c = h.node("c")

        // a and c meet once so a learns c's encryption key, then part ways.
        h.connect(a, c)
        a.core.profiles.watchNearbyPeers().awaitMatching { list -> list.any { it.userId == c.id } }
        h.disconnect(a, c)

        h.connect(a, carrier)
        carrier.core.profiles.watchNearbyPeers().awaitMatching { list -> list.any { it.userId == a.id } }
        a.core.messages.send(c.id, "meet at the fountain")
        settle()
        // The carrier holds it but it is not part of the carrier's conversations.
        assertTrue(carrier.core.messages.watchInbox().first().isEmpty())
        h.disconnect(a, carrier)

        // Later the carrier bumps into c.
        h.connect(carrier, c)
        val received = c.core.messages.watchConversation(a.id).awaitMatching { it.isNotEmpty() }.single()
        assertEquals("meet at the fountain", received.text)

        // The receipt rides back through the carrier on the next encounter.
        h.connect(carrier, a)
        a.core.messages.watchConversation(c.id).awaitMatching { it.single().status == MessageStatus.DELIVERED }
        h.closeAll()
    }

    @Test
    fun `cannot message someone never met`() = runBlocking {
        val h = Harness()
        val a = h.node("a")
        assertFailsWith<IllegalStateException> { a.core.messages.send("ab".repeat(32), "hello?") }
        h.closeAll()
    }
}
