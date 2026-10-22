package com.proximity.core

import com.proximity.core.identity.InMemoryKeyStore
import com.proximity.core.identity.LocalIdentity
import com.proximity.core.wire.ObjectKind
import com.proximity.core.wire.SignedObject
import com.proximity.core.wire.SyncMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncTest {

    @Test
    fun `post reaches a connected peer with signature checked and hop count bumped`() = runBlocking {
        val h = Harness()
        val a = h.node("a")
        val b = h.node("b")
        h.connect(a, b, signal = -40)

        val post = a.core.posts.create("hello from a")
        val received = b.core.posts.watchFeedCandidates().awaitMatching { list -> list.any { it.post.id == post.id } }
            .single { it.post.id == post.id }

        assertEquals("hello from a", received.post.body)
        assertEquals(a.id, received.post.authorId)
        assertEquals(1, received.post.hopCount)
        assertEquals(a.id, received.receivedFrom)
        assertEquals(-40, received.receivedSignalStrength)
        assertEquals(false, received.isMine)
        h.closeAll()
    }

    @Test
    fun `posts written before meeting sync during the handshake`() = runBlocking {
        val h = Harness()
        val a = h.node("a")
        val b = h.node("b")
        val p1 = a.core.posts.create("one")
        val p2 = b.core.posts.create("two")

        h.connect(a, b)

        a.core.posts.watchFeedCandidates().awaitMatching { list -> list.map { it.post.id }.containsAll(listOf(p1.id, p2.id)) }
        b.core.posts.watchFeedCandidates().awaitMatching { list -> list.map { it.post.id }.containsAll(listOf(p1.id, p2.id)) }
        // Profiles travel in the hello, so authors have names.
        a.core.profiles.updateMyProfile(displayName = "Alice")
        b.core.posts.watchFeedCandidates().awaitMatching { list -> list.any { it.author?.displayName == "Alice" } }
        h.closeAll()
    }

    @Test
    fun `posts relay up to the hop limit and no further`() = runBlocking {
        val h = Harness(AppConfig(maxRelayHops = 2))
        val chain = (0..4).map { h.node("n$it") }
        chain.zipWithNext().forEach { (x, y) -> h.connect(x, y) }

        val post = chain[0].core.posts.create("ripple")
        for (i in 1..3) {
            val copy = chain[i].core.posts.watchFeedCandidates().awaitMatching { list -> list.any { it.post.id == post.id } }
            assertEquals(i, copy.single().post.hopCount, "hop count at n$i")
        }
        settle()
        assertTrue(chain[4].core.posts.watchFeedCandidates().first().isEmpty(), "n4 is 4 links away and must not get it")
        h.closeAll()
    }

    @Test
    fun `comments and reactions sync and the latest reaction wins`() = runBlocking {
        val h = Harness()
        val a = h.node("a")
        val b = h.node("b")
        h.connect(a, b)
        val post = a.core.posts.create("rate my lunch")
        b.core.posts.watchFeedCandidates().awaitMatching { it.isNotEmpty() }

        val comment = b.core.comments.add(post.id, "looks great")
        b.core.reactions.add(post.id, "like")
        b.core.reactions.add(post.id, "love")
        a.core.reactions.add(comment.id, "like")

        a.core.comments.watchForPost(post.id).awaitMatching { list -> list.any { it.comment.id == comment.id && !it.isMine } }
        a.core.posts.watchFeedCandidates().awaitMatching { list ->
            list.single().let { it.commentCount == 1 && it.reactionCounts == mapOf("love" to 1) }
        }
        b.core.reactions.watchForPost(post.id).awaitMatching { summaries ->
            summaries[comment.id]?.counts == mapOf("like" to 1) && summaries[post.id]?.myReaction == "love"
        }

        b.core.reactions.remove(post.id)
        a.core.posts.watchFeedCandidates().awaitMatching { it.single().reactionCounts.isEmpty() }
        h.closeAll()
    }

    @Test
    fun `expired posts are cleaned up and never handed to new peers`() = runBlocking {
        val h = Harness()
        val a = h.node("a")
        val b = h.node("b")
        a.core.posts.create("short lived")
        h.clock.advance(Duration.ofHours(7))
        a.core.runCleanup()
        assertTrue(a.core.posts.watchFeedCandidates().first().isEmpty())

        h.connect(a, b)
        settle()
        assertTrue(b.core.posts.watchFeedCandidates().first().isEmpty())
        h.closeAll()
    }

    @Test
    fun `forged and tampered objects are rejected`() = runBlocking {
        val h = Harness()
        val b = h.node("b")
        val rogueTransport = h.network.createTransport("rogue")
        rogueTransport.start()
        h.network.connect(rogueTransport, b.transport)

        val victim = LocalIdentity.load(InMemoryKeyStore())
        val rogue = LocalIdentity.load(InMemoryKeyStore())
        val now = h.clock.now().toEpochMilli()
        fun post(author: String, body: String, signer: LocalIdentity) = SignedObject.create(
            ObjectKind.POST,
            mapOf("authorId" to author, "body" to body, "createdAt" to now, "ttl" to Duration.ofHours(1).toMillis()),
            signer,
        )

        // Claims to be the victim but is signed by the rogue key.
        val forged = post(victim.currentUser.id, "I owe you money", rogue)
        // Validly signed, then the body is edited in transit.
        val original = post(rogue.currentUser.id, "original", rogue).toJson()
        val tampered = SignedObject.fromJson(
            kotlinx.serialization.json.JsonObject(
                original + ("fields" to kotlinx.serialization.json.JsonObject(
                    (original["fields"] as kotlinx.serialization.json.JsonObject) +
                        ("body" to kotlinx.serialization.json.JsonPrimitive("edited")),
                )),
            ),
        )
        val honest = post(rogue.currentUser.id, "honest", rogue)

        rogueTransport.send("b", SyncMessage.Objects(listOf(forged, tampered)).encode())
        rogueTransport.send("b", SyncMessage.Objects(listOf(honest)).encode())

        val feed = b.core.posts.watchFeedCandidates().awaitMatching { list -> list.any { it.post.body == "honest" } }
        assertEquals(listOf("honest"), feed.map { it.post.body })
        h.closeAll()
    }

    @Test
    fun `peers that walk away stop receiving and catch up when they return`() = runBlocking {
        val h = Harness()
        val a = h.node("a")
        val b = h.node("b")
        h.connect(a, b)
        b.core.profiles.watchNearbyPeers().awaitMatching { list -> list.any { it.userId == a.id } }

        h.disconnect(a, b)
        b.core.profiles.watchNearbyPeers().awaitMatching { it.isEmpty() }
        val post = a.core.posts.create("while you were away")
        settle()
        assertTrue(b.core.posts.watchFeedCandidates().first().isEmpty())

        h.connect(a, b)
        b.core.posts.watchFeedCandidates().awaitMatching { list -> list.any { it.post.id == post.id } }
        h.closeAll()
    }
}
