package com.proximity.contracts

import java.time.Duration
import java.time.Instant

/**
 * A signed, immutable post. [id] is the hash of the signed content, so two
 * copies of the same post always share an id.
 */
class Post(
    val id: String,
    /** Hex-encoded Ed25519 public key of the author. */
    val authorId: String,
    val body: String,
    /** Small compressed image, or null for text-only posts. */
    val image: ByteArray?,
    val createdAt: Instant,
    val ttl: Duration,
    /**
     * How many links this copy travelled to reach this device. 0 means the
     * post was written here. Not covered by the signature, since relays bump it.
     */
    val hopCount: Int,
    val signature: String,
) {
    val expiresAt: Instant get() = createdAt.plus(ttl)
}

data class Comment(
    val id: String,
    val postId: String,
    val authorId: String,
    val body: String,
    val createdAt: Instant,
    val signature: String,
)

/**
 * One reaction per author per target; the newest one wins. A reaction with
 * type [REMOVED] clears the author's earlier reaction.
 */
data class Reaction(
    val id: String,
    /** The post or comment being reacted to. */
    val targetId: String,
    /**
     * The post the target belongs to (equal to [targetId] for post reactions).
     * Lets reactions expire together with their post.
     */
    val postId: String,
    val authorId: String,
    val type: String,
    val createdAt: Instant,
    val signature: String,
) {
    companion object {
        const val REMOVED = "none"
    }
}

/**
 * Signed public profile. Carries the X25519 key other users need to send
 * encrypted direct messages to this user.
 */
class Profile(
    val id: String,
    /** The user id (hex Ed25519 public key). */
    val publicKey: String,
    /** Hex X25519 public key used for direct message encryption. */
    val encryptionKey: String,
    val displayName: String?,
    val avatar: ByteArray?,
    val updatedAt: Instant,
    val signature: String,
)

/** A profile plus what this device has observed about that person locally. */
class PeerProfile(
    val profile: Profile,
    val lastSeenAt: Instant?,
    val lastSignalStrength: Int?,
    /** True while the peer is directly connected to this device. */
    val isNearby: Boolean,
) {
    val userId: String get() = profile.publicKey
}

/** Everything the ranker may want to know about a stored post. */
class FeedCandidate(
    val post: Post,
    val author: Profile?,
    val receivedAt: Instant,
    /** User id of the peer that handed us this post, if known. */
    val receivedFrom: String?,
    /** Signal strength of that peer at the moment we received the post. */
    val receivedSignalStrength: Int?,
    /** Counts as seen by this device only; there is no global view. */
    val commentCount: Int,
    val reactionCounts: Map<String, Int>,
    val myReaction: String?,
    val isMine: Boolean,
)

class CommentView(val comment: Comment, val author: Profile?, val isMine: Boolean)

data class ReactionSummary(val counts: Map<String, Int>, val myReaction: String?)

enum class MessageStatus {
    /** Sent by us, no delivery receipt seen yet. */
    PENDING,

    /** Sent by us, the recipient's signed receipt has reached this device. */
    DELIVERED,

    /** Received by us. */
    RECEIVED,
}

/** A decrypted direct message as the UI sees it. */
data class ChatMessage(
    val id: String,
    val fromId: String,
    val toId: String,
    val text: String,
    val createdAt: Instant,
    val status: MessageStatus,
    val image: ByteArray? = null,
) {
    val isMine: Boolean get() = status != MessageStatus.RECEIVED
}

class Conversation(val peerId: String, val peer: Profile?, val lastMessage: ChatMessage)

data class LocalUser(
    /** Hex Ed25519 public key. */
    val id: String,
    /** Hex X25519 public key. */
    val encryptionKey: String,
)
