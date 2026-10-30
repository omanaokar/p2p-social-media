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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModerationTest {

    @Test
    fun `local limits on size and rate`() = runBlocking {
        val h = Harness(AppConfig(maxPostsPerWindow = 3, maxPostChars = 10))
        val a = h.node("a")
        assertFailsWith<IllegalArgumentException> { a.core.posts.create("x".repeat(11)) }
        assertFailsWith<IllegalArgumentException> { a.core.posts.create("   ") }
        repeat(3) { a.core.posts.create("ok $it") }
        assertFailsWith<IllegalArgumentException> { a.core.posts.create("one more") }
        h.closeAll()
    }

    @Test
    fun `peers enforce the rate limit on a flooding author`() = runBlocking {
        val h = Harness(AppConfig(maxPostsPerWindow = 5))
        val b = h.node("b")
        val spammerTransport = h.network.createTransport("spammer")
        spammerTransport.start()
        h.network.connect(spammerTransport, b.transport)

        val spammer = LocalIdentity.load(InMemoryKeyStore())
        val posts = (0 until 12).map { i ->
            SignedObject.create(
                ObjectKind.POST,
                mapOf(
                    "authorId" to spammer.currentUser.id,
                    "body" to "buy now $i",
                    "createdAt" to h.clock.now().toEpochMilli() + i,
                    "ttl" to Duration.ofHours(1).toMillis(),
                ),
                spammer,
            )
        }
        spammerTransport.send("b", SyncMessage.Objects(posts).encode())
        b.core.posts.watchFeedCandidates().awaitMatching { it.size == 5 }
        settle()
        assertEquals(5, b.core.posts.watchFeedCandidates().first().size)
        h.closeAll()
    }

    @Test
    fun `reporting hides the post and blocks the author for good`() = runBlocking {
        val h = Harness()
        val a = h.node("a")
        val b = h.node("b")
        h.connect(a, b)
        val bad = a.core.posts.create("rude")
        b.core.posts.watchFeedCandidates().awaitMatching { it.isNotEmpty() }

        b.core.posts.report(bad.id)
        b.core.posts.watchFeedCandidates().awaitMatching { it.isEmpty() }
        assertEquals(listOf(a.id), b.core.profiles.watchBlocked().first())

        a.core.posts.create("still rude")
        settle()
        assertTrue(b.core.posts.watchFeedCandidates().first().isEmpty())
        assertFailsWith<IllegalArgumentException> { b.core.profiles.block(b.id) }
        h.closeAll()
    }

    @Test
    fun `deleting own post removes it locally`() = runBlocking {
        val h = Harness()
        val a = h.node("a")
        val post = a.core.posts.create("oops")
        a.core.posts.delete(post.id)
        assertTrue(a.core.posts.watchFeedCandidates().first().isEmpty())
        h.closeAll()
    }
}
