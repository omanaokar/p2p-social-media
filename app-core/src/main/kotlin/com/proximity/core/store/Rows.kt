package com.proximity.core.store

import com.proximity.contracts.ChatMessage
import com.proximity.contracts.Comment
import com.proximity.contracts.MessageStatus
import com.proximity.contracts.Post
import com.proximity.contracts.Profile
import com.proximity.contracts.Reaction
import com.proximity.core.db.Row
import com.proximity.core.db.blob
import com.proximity.core.db.int
import com.proximity.core.db.long
import com.proximity.core.db.optStr
import com.proximity.core.db.str
import java.time.Duration
import java.time.Instant

private fun Row.instant(key: String): Instant = Instant.ofEpochMilli(long(key))

internal fun Row.toPost() = Post(
    id = str("id"),
    authorId = str("author_id"),
    body = str("body"),
    image = blob("image"),
    createdAt = instant("created_at"),
    ttl = Duration.ofMillis(long("ttl")),
    hopCount = int("hop_count"),
    signature = str("signature"),
)

internal fun Row.toComment() = Comment(
    id = str("id"),
    postId = str("post_id"),
    authorId = str("author_id"),
    body = str("body"),
    createdAt = instant("created_at"),
    signature = str("signature"),
)

internal fun Row.toReaction() = Reaction(
    id = str("id"),
    targetId = str("target_id"),
    postId = str("post_id"),
    authorId = str("author_id"),
    type = str("type"),
    createdAt = instant("created_at"),
    signature = str("signature"),
)

internal fun Row.toProfile() = Profile(
    id = str("id"),
    publicKey = str("public_key"),
    encryptionKey = str("encryption_key"),
    displayName = optStr("display_name"),
    avatar = blob("avatar"),
    updatedAt = instant("updated_at"),
    signature = str("signature"),
)

internal fun Row.toChatMessage() = ChatMessage(
    id = str("id"),
    fromId = str("from_id"),
    toId = str("to_id"),
    text = optStr("body").orEmpty(),
    createdAt = instant("created_at"),
    status = MessageStatus.valueOf(str("status")),
    image = blob("image"),
)
