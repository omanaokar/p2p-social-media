package com.proximity.core

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.proximity.contracts.InteractionType
import com.proximity.core.db.Schema
import com.proximity.core.identity.InMemoryKeyStore
import com.proximity.core.testing.InMemoryNetwork
import com.proximity.ranker.recommendation.PeerRecommendationContext
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AppCoreRecommendationIntegrationTest {
    @Test
    fun `reaction and comment repository events update the persistent interest profile`() = runBlocking {
        val h = Harness()
        val node = h.node("learner")
        val post = node.core.posts.create("Kotlin algorithms tutorial covering graph traversal")

        node.core.reactions.add(post.id, "like")
        val afterReaction = node.core.recommendations.profileStore.getProfile()
        assertTrue(afterReaction.interactionCounts.values.sum() > 0)

        node.core.comments.add(post.id, "This data structures explanation is helpful")
        assertTrue(node.core.recommendations.profileStore.getProfile().interactionCounts.values.sum() > afterReaction.interactionCounts.values.sum())
        h.closeAll()
    }

    @Test
    fun `duplicate event dispatch and uncached post interaction are safe`() = runBlocking {
        val h = Harness()
        val node = h.node("learner")
        // Never collect/rank the feed: the repository must resolve this stored post itself.
        val post = node.core.posts.create("Kotlin programming lesson about binary search trees")
        node.core.reactions.add(post.id, "like")
        val afterRepositoryEvent = node.core.recommendations.profileStore.getProfile().interactionCounts.values.sum()
        assertTrue(afterRepositoryEvent > 0)

        node.core.recommendations.recordFeedInteraction(post, InteractionType.REACT, eventId = "same-event")
        val afterFirstDuplicateDispatch = node.core.recommendations.profileStore.getProfile().interactionCounts.values.sum()
        node.core.recommendations.recordFeedInteraction(post, InteractionType.REACT, eventId = "same-event")
        assertTrue(afterFirstDuplicateDispatch > afterRepositoryEvent)
        assertEquals(afterFirstDuplicateDispatch, node.core.recommendations.profileStore.getProfile().interactionCounts.values.sum())
        h.closeAll()
    }

    @Test
    fun `reaction and comment events stay idempotent after AppCore restart`() = runBlocking {
        val directory = Files.createTempDirectory("proximity-events")
        val databasePath = directory.resolve("app.db").toString()
        val keyStore = InMemoryKeyStore()
        val network = InMemoryNetwork()
        val first = AppCore.open(network.createTransport("first"), keyStore, BundledSQLiteDriver(), databasePath)
        val post = first.posts.create("Kotlin algorithms tutorial on graph traversal")
        val reaction = first.reactions.add(post.id, "like")
        val comment = first.comments.add(post.id, "Useful data structures explanation")
        val firstCount = first.recommendationSystem.profileStore.getProfile().interactionCounts.values.sum()
        first.close()

        val reopened = AppCore.open(network.createTransport("second"), keyStore, BundledSQLiteDriver(), databasePath)
        reopened.recommendationSystem.recordFeedInteraction(post, InteractionType.REACT, eventId = reaction.id)
        reopened.recommendationSystem.recordFeedInteraction(post, InteractionType.COMMENT, eventId = comment.id)
        assertEquals(firstCount, reopened.recommendationSystem.profileStore.getProfile().interactionCounts.values.sum())
        reopened.close()
    }

    @Test
    fun `different event IDs on one post are both recorded and failed repository operations are ignored`() = runBlocking {
        val h = Harness()
        val node = h.node("learner")
        val post = node.core.posts.create("Programming lesson on binary search trees")
        node.core.recommendationSystem.recordFeedInteraction(post, InteractionType.REACT, eventId = "first")
        val afterFirst = node.core.recommendationSystem.profileStore.getProfile().interactionCounts.values.sum()
        node.core.recommendationSystem.recordFeedInteraction(post, InteractionType.REACT, eventId = "second")
        assertTrue(node.core.recommendationSystem.profileStore.getProfile().interactionCounts.values.sum() > afterFirst)

        val beforeFailure = node.core.recommendationSystem.profileStore.getProfile().interactionCounts.values.sum()
        assertFailsWith<IllegalStateException> { runBlocking { node.core.comments.add("missing", "cannot persist") } }
        assertFailsWith<IllegalStateException> { runBlocking { node.core.reactions.add("missing", "like") } }
        assertEquals(beforeFailure, node.core.recommendationSystem.profileStore.getProfile().interactionCounts.values.sum())
        h.closeAll()
    }

    @Test
    fun `interest profile survives reopening AppCore with a file backed database`() = runBlocking {
        val directory = Files.createTempDirectory("proximity-core-test")
        val databasePath = directory.resolve("app.db").toString()
        val keyStore = InMemoryKeyStore()
        val network = InMemoryNetwork()
        val first = AppCore.open(network.createTransport("first"), keyStore, BundledSQLiteDriver(), databasePath)
        val post = first.posts.create("Algorithms lesson explaining graph traversal")
        first.reactions.add(post.id, "like")
        assertTrue(first.recommendations.profileStore.getProfile().interactionCounts.isNotEmpty())
        first.close()

        val reopened = AppCore.open(network.createTransport("second"), keyStore, BundledSQLiteDriver(), databasePath)
        assertTrue(reopened.recommendations.profileStore.getProfile().interactionCounts.isNotEmpty())
        reopened.close()
    }

    @Test
    fun `peer liked post IDs feed AppCore peer recommendations`() = runBlocking {
        val h = Harness()
        val a = h.node("a")
        val b = h.node("b")
        h.connect(a, b)
        val post = a.core.posts.create("Machine learning tutorial on gradient descent optimization")
        val candidateFeed = b.core.posts.watchFeedCandidates().awaitMatching { list ->
            list.any { candidate -> candidate.post.id == post.id && candidate.author != null }
        }
        a.core.reactions.add(post.id, "like")
        b.core.reactions.add(post.id, "like")
        b.core.reactions.watchForPost(post.id).awaitMatching { summaries ->
            summaries[post.id]?.counts?.get("like") == 2
        }
        val candidateProfile = candidateFeed.single { it.post.id == post.id }.author!!

        assertEquals(setOf(post.id), b.core.reactions.getReactedPostIds(a.id))
        assertEquals(setOf(post.id), b.core.reactions.getPeerReactedPostIds()[a.id])
        val recommendations = b.core.recommendPeers(
            PeerRecommendationContext(
                currentUserId = b.id,
                candidateProfiles = listOf(candidateProfile),
                candidatePosts = candidateFeed,
            )
        )
        assertEquals(a.id, recommendations.single().profile.publicKey)
        assertTrue(recommendations.single().reason.contains("shared liked educational post"))

        b.core.profiles.block(a.id)
        assertTrue(b.core.reactions.getPeerReactedPostIds()[a.id] == null)
        assertTrue(b.core.recommendPeers(
            PeerRecommendationContext(currentUserId = b.id, candidateProfiles = listOf(candidateProfile), blockedUserIds = setOf(a.id))
        ).isEmpty())
        assertTrue(b.core.recommendPeers(
            PeerRecommendationContext(currentUserId = b.id, candidateProfiles = listOf(candidateProfile), existingFriendIds = setOf(a.id))
        ).isEmpty())
        h.closeAll()
    }

    @Test
    fun `v1 databases migrate peer index and interaction ledger`() = runBlocking {
        val directory = Files.createTempDirectory("proximity-v1")
        val databasePath = directory.resolve("app.db").toString()
        val driver = BundledSQLiteDriver()
        driver.open(databasePath).use { connection ->
            Schema.migrations.first().forEach { sql -> connection.prepare(sql).use { statement -> statement.step() } }
            connection.prepare("PRAGMA user_version = 1").use { it.step() }
        }
        val core = AppCore.open(InMemoryNetwork().createTransport("node"), InMemoryKeyStore(), driver, databasePath)
        core.close()
        driver.open(databasePath).use { connection ->
            val version = connection.prepare("PRAGMA user_version").use { statement -> statement.step(); statement.getLong(0) }
            val names = connection.prepare("SELECT name FROM sqlite_master WHERE type IN ('table', 'index')").use { statement ->
                buildSet { while (statement.step()) add(statement.getText(0)) }
            }
            assertEquals(4, version)
            assertTrue("reactions_by_author_type_post" in names)
            assertTrue("processed_interaction_events" in names)
        }
    }

    @Test
    fun `two identities using one database path have isolated profiles`() = runBlocking {
        val directory = Files.createTempDirectory("proximity-identities")
        val databasePath = directory.resolve("app.db").toString()
        val network = InMemoryNetwork()
        val first = AppCore.open(network.createTransport("first"), InMemoryKeyStore(), BundledSQLiteDriver(), databasePath)
        val post = first.posts.create("Algorithms tutorial on graph traversal")
        first.reactions.add(post.id, "like")
        assertTrue(first.recommendationSystem.profileStore.getProfile().interactionCounts.isNotEmpty())
        first.close()

        val second = AppCore.open(network.createTransport("second"), InMemoryKeyStore(), BundledSQLiteDriver(), databasePath)
        assertTrue(second.recommendationSystem.profileStore.getProfile().interactionCounts.isEmpty())
        second.close()
    }
}
