package com.proximity.contracts

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant

// Owned by the application layer (Om). The UI talks only to these.

interface PostRepository {
    /** Signs, stores and starts spreading a new post. */
    suspend fun create(body: String, image: ByteArray? = null): Post

    /**
     * Every live post this device holds (visibility rule (b): anything still
     * within its TTL), unranked. Pass the list to the Ranker.
     */
    fun watchFeedCandidates(): Flow<List<FeedCandidate>>

    /**
     * Removes the post from this device. Copies already on other phones live
     * until their TTL runs out.
     */
    suspend fun delete(postId: String)

    /** Hides the post locally, never accepts it again and, by default, blocks the author. */
    suspend fun report(postId: String, blockAuthor: Boolean = true)
}

interface CommentRepository {
    suspend fun add(postId: String, body: String): Comment
    fun watchForPost(postId: String): Flow<List<CommentView>>
}

interface ReactionRepository {
    /** Sets this user's reaction on a post or comment, replacing any earlier one. */
    suspend fun add(targetId: String, type: String): Reaction
    suspend fun remove(targetId: String): Reaction

    /** Reaction summaries for the post and each of its comments, keyed by target id. */
    fun watchForPost(postId: String): Flow<Map<String, ReactionSummary>>
}

interface MessageRepository {
    /**
     * Encrypts and queues a message. If the recipient is not nearby it is held
     * and carried by peers until it reaches them or expires.
     *
     * @throws IllegalStateException if the recipient's profile has never been seen.
     */
    suspend fun send(toUserId: String, text: String): ChatMessage
    fun watchConversation(peerUserId: String): Flow<List<ChatMessage>>
    fun watchInbox(): Flow<List<Conversation>>
}

interface ProfileRepository {
    /** Null arguments keep the current value. */
    suspend fun updateMyProfile(displayName: String? = null, avatar: ByteArray? = null): Profile
    fun watchMyProfile(): Flow<Profile>
    suspend fun getProfile(userId: String): PeerProfile?

    /** Peers directly connected right now whose profile is known. */
    fun watchNearbyPeers(): Flow<List<PeerProfile>>

    suspend fun block(userId: String)
    suspend fun unblock(userId: String)
    fun watchBlocked(): Flow<List<String>>
}

interface IdentityService {
    val currentUser: LocalUser
    fun sign(data: ByteArray): String
    fun verify(data: ByteArray, signature: String, authorId: String): Boolean
}

enum class CallPhase { IDLE, OUTGOING, INCOMING, CONNECTING, ACTIVE, ENDED }

data class CallState(
    val phase: CallPhase,
    val callId: String? = null,
    /** User id of the other party. */
    val peerId: String? = null,
    /** Set once the call becomes active. */
    val startedAt: Instant? = null,
    val error: String? = null,
) {
    companion object {
        val IDLE = CallState(CallPhase.IDLE)
    }
}

/** Stretch goal: 1 to 1 voice calls, signalled over the transport link. */
interface CallService {
    /** The callee must be directly connected. */
    suspend fun startCall(userId: String)
    suspend fun accept()
    suspend fun hangup()
    val state: StateFlow<CallState>
}
