package com.proximity.core

import java.time.Duration
import java.time.Instant

/** Tunables for the application layer. Defaults follow the architecture doc. */
data class AppConfig(
    /** How long a post lives. Signed into the post, so the author's value wins. */
    val postTtl: Duration = Duration.ofHours(6),
    /** Posts from peers asking for a longer life than this are rejected. */
    val maxPostTtl: Duration = Duration.ofHours(24),
    /** A post reaches peers up to this many relays away from its author. */
    val maxRelayHops: Int = 2,
    /**
     * Direct messages are carried longer and further so store and forward has
     * a real chance of reaching someone who walked away.
     */
    val dmTtl: Duration = Duration.ofHours(72),
    val dmMaxRelayHops: Int = 6,
    /** Profiles of people with no live content are dropped after this long. */
    val profileRetention: Duration = Duration.ofDays(7),
    val maxPostChars: Int = 2000,
    val maxCommentChars: Int = 1000,
    val maxMessageChars: Int = 4000,
    val maxDisplayNameChars: Int = 50,
    val maxImageBytes: Int = 256 * 1024,
    val maxAvatarBytes: Int = 64 * 1024,
    /** Spam guard: per author, objects created within this window are counted. */
    val rateLimitWindow: Duration = Duration.ofMinutes(10),
    val maxPostsPerWindow: Int = 10,
    /** Comments, reactions and direct messages combined. */
    val maxOtherPerWindow: Int = 60,
    /** Objects dated further in the future than this are rejected. */
    val maxClockSkew: Duration = Duration.ofMinutes(5),
    val cleanupInterval: Duration = Duration.ofMinutes(5),
    val maxObjectsPerMessage: Int = 20,
    /** Don't ask a second peer for an object we requested this recently. */
    val wantRetryAfter: Duration = Duration.ofSeconds(30),
) {
    val maxPostHopCount: Int get() = maxRelayHops + 1
    val maxDmHopCount: Int get() = dmMaxRelayHops + 1
}

fun interface Clock {
    fun now(): Instant

    companion object {
        val SYSTEM = Clock { Instant.now() }
    }
}
