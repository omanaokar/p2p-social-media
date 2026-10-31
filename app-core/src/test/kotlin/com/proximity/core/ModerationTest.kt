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
        val h = Harness(AppConfig(maxPostsPerWindow = 3, maxPostChars = 10, strictEducationFilter = false))
        val a = h.node("a")
        assertFailsWith<IllegalArgumentException> { a.core.posts.create("x".repeat(11)) }
        assertFailsWith<IllegalArgumentException> { a.core.posts.create("   ") }
        repeat(3) { a.core.posts.create("ok $it") }
        assertFailsWith<IllegalArgumentException> { a.core.posts.create("one more") }
        h.closeAll()
    }

    @Test
    fun `peers enforce the rate limit on a flooding author`() = runBlocking {
        val h = Harness(AppConfig(maxPostsPerWindow = 5, strictEducationFilter = false))
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
        val bad = a.core.posts.create("Algorithms lecture notes with bad advice")
        b.core.posts.watchFeedCandidates().awaitMatching { it.isNotEmpty() }

        b.core.posts.report(bad.id)
        b.core.posts.watchFeedCandidates().awaitMatching { it.isEmpty() }
        assertEquals(listOf(a.id), b.core.profiles.watchBlocked().first())

        a.core.posts.create("Still bad algorithms advice")
        settle()
        assertTrue(b.core.posts.watchFeedCandidates().first().isEmpty())
        assertFailsWith<IllegalArgumentException> { b.core.profiles.block(b.id) }
        h.closeAll()
    }

    @Test
    fun `deleting own post removes it locally`() = runBlocking {
        val h = Harness()
        val a = h.node("a")
        val post = a.core.posts.create("Python algorithms and data structures study notes")
        a.core.posts.delete(post.id)
        assertTrue(a.core.posts.watchFeedCandidates().first().isEmpty())
        h.closeAll()
    }

    @Test
    fun `publishing rejects non-educational, inappropriate, and uncertain posts`() = runBlocking {
        val h = Harness()
        val a = h.node("a")

        // Non-educational
        val exNonEdu = assertFailsWith<IllegalArgumentException> {
            a.core.posts.create("Going out for tacos and beer at the beach party this weekend! Weekend vibes.")
        }
        assertTrue(exNonEdu.message!!.contains("educational", ignoreCase = true))

        // Inappropriate / adult
        val exAdult = assertFailsWith<IllegalArgumentException> {
            a.core.posts.create("Check out my adult xxx nsfw photos and videos on onlyfans.")
        }
        assertTrue(exAdult.message!!.contains("educational", ignoreCase = true) || exAdult.message!!.contains("inappropriate", ignoreCase = true))

        // Misleading keywords (anti-educational dismissal)
        val exMisleading = assertFailsWith<IllegalArgumentException> {
            a.core.posts.create("Skipping calculus class today to play soccer with friends!")
        }
        assertTrue(exMisleading.message!!.contains("educational", ignoreCase = true))

        // Uncertain / Ambiguous
        val exUncertain = assertFailsWith<IllegalArgumentException> {
            a.core.posts.create("Hey guys, what do you think about this?")
        }
        assertTrue(exUncertain.message!!.contains("educational", ignoreCase = true))

        // Legitimate educational content succeeds
        val post = a.core.posts.create("A comprehensive tutorial on dynamic programming algorithms and memoization in Kotlin.")
        assertEquals("A comprehensive tutorial on dynamic programming algorithms and memoization in Kotlin.", post.body)

        h.closeAll()
    }

    @Test
    fun `incoming non-educational posts from peers are rejected during ingest and never visible in feed`() = runBlocking {
        val h = Harness()
        val a = h.node("a")
        val b = h.node("b")
        h.connect(a, b)

        val peerIdentity = LocalIdentity.load(InMemoryKeyStore())
        val nonEduObject = SignedObject.create(
            ObjectKind.POST,
            mapOf(
                "authorId" to peerIdentity.currentUser.id,
                "body" to "Having lunch and tacos with friends! Weekend vibes.",
                "createdAt" to h.clock.now().toEpochMilli(),
                "ttl" to Duration.ofHours(1).toMillis(),
            ),
            peerIdentity,
        )

        // Send non-educational post directly via sync message
        a.transport.send("b", SyncMessage.Objects(listOf(nonEduObject)).encode())
        settle()

        // Verify device B does not hold or display this post
        val feedB = b.core.posts.watchFeedCandidates().first()
        assertTrue(feedB.none { it.post.id == nonEduObject.id })

        h.closeAll()
    }
}
