package com.proximity.core

import com.proximity.core.identity.InMemoryKeyStore
import com.proximity.core.identity.LocalIdentity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class IdentityAndStorageTest {

    @Test
    fun `keys are generated once and reused`() {
        val store = InMemoryKeyStore()
        val first = LocalIdentity.load(store)
        val again = LocalIdentity.load(store)
        val other = LocalIdentity.load(InMemoryKeyStore())
        assertEquals(first.currentUser, again.currentUser)
        assertNotEquals(first.currentUser.id, other.currentUser.id)
        assertEquals(64, first.currentUser.id.length)
    }

    @Test
    fun `signatures verify only for the right author and data`() {
        val alice = LocalIdentity.load(InMemoryKeyStore())
        val bob = LocalIdentity.load(InMemoryKeyStore())
        val data = "hello".toByteArray()
        val sig = alice.sign(data)
        assertTrue(bob.verify(data, sig, alice.currentUser.id))
        assertFalse(bob.verify("hellO".toByteArray(), sig, alice.currentUser.id))
        assertFalse(bob.verify(data, sig, bob.currentUser.id))
        assertFalse(bob.verify(data, "not base64!", alice.currentUser.id))
        assertFalse(bob.verify(data, sig, "zz"))
    }

    @Test
    fun `data and identity survive an app restart`(): Unit = runBlocking {
        val dir = Files.createTempDirectory("proximity")
        val path = dir.resolve("app.db").toString()
        val keys = InMemoryKeyStore()
        val h = Harness()

        val first = h.node("a", keyStore = keys, databasePath = path)
        first.core.profiles.updateMyProfile(displayName = "Om")
        val post = first.core.posts.create("persisted")
        first.core.close()

        val reopened = Harness().node("a2", keyStore = keys, databasePath = path)
        assertEquals(first.id, reopened.id)
        assertEquals("Om", reopened.core.profiles.watchMyProfile().first().displayName)
        assertEquals(listOf(post.id), reopened.core.posts.watchFeedCandidates().first().map { it.post.id })
        reopened.core.close()
        dir.toFile().deleteRecursively()
    }
}
