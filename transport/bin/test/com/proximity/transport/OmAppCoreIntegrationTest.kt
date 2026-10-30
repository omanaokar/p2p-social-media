package com.proximity.transport

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.proximity.contracts.FeedCandidate
import com.proximity.core.AppConfig
import com.proximity.core.AppCore
import com.proximity.core.identity.InMemoryKeyStore
import com.proximity.transport.nearby.InProcessNearbyDriver
import com.proximity.transport.nearby.NearbyTransport
import com.proximity.transport.nearby.RadioMedium
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

private suspend fun <T> Flow<T>.awaitMatching(timeoutMs: Long = 10_000, predicate: (T) -> Boolean): T =
    withTimeout(timeoutMs) { first(predicate) }

class OmAppCoreIntegrationTest {

    @Test
    fun `NearbyTransport powers AppCore post sync and direct messaging between two devices`() = runBlocking {
        val medium = RadioMedium()

        // Setup Device A
        val driverA = InProcessNearbyDriver("ep-a", "Phone-A", medium)
        val transportA = NearbyTransport("Phone-A", driver = driverA)
        val coreA = AppCore.open(
            transport = transportA,
            keyStore = InMemoryKeyStore(),
            driver = BundledSQLiteDriver(),
            databasePath = null, // in-memory
            config = AppConfig(),
        )

        // Setup Device B
        val driverB = InProcessNearbyDriver("ep-b", "Phone-B", medium)
        val transportB = NearbyTransport("Phone-B", driver = driverB)
        val coreB = AppCore.open(
            transport = transportB,
            keyStore = InMemoryKeyStore(),
            driver = BundledSQLiteDriver(),
            databasePath = null,
            config = AppConfig(),
        )

        coreA.start()
        coreB.start()

        // Device A writes a post
        val post = coreA.posts.create("Post broadcast over Harshil's NearbyTransport!")

        // Device B should receive the post through the sync engine over NearbyTransport!
        val candidates = coreB.posts.watchFeedCandidates().awaitMatching(10_000) { list: List<FeedCandidate> ->
            list.any { it.post.id == post.id }
        }
        val receivedPostCandidate = candidates.single { it.post.id == post.id }

        assertEquals("Post broadcast over Harshil's NearbyTransport!", receivedPostCandidate.post.body)
        assertEquals(coreA.identity.currentUser.id, receivedPostCandidate.post.authorId)
        assertEquals(1, receivedPostCandidate.post.hopCount)

        // Direct Messaging test over the P2P transport link
        val message = coreA.messages.send(coreB.identity.currentUser.id, "Direct message over P2P mesh")
        assertEquals(coreB.identity.currentUser.id, message.toId)

        coreA.close()
        coreB.close()
        transportA.stop()
        transportB.stop()
    }
}

