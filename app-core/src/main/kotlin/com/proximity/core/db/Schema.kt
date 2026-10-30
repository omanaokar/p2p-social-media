package com.proximity.core.db

internal object Schema {
    /** Append a new list to migrate; never edit a shipped one. */
    val migrations: List<List<String>> = listOf(
        // v1
        listOf(
            """CREATE TABLE posts (
                id TEXT PRIMARY KEY,
                author_id TEXT NOT NULL,
                body TEXT NOT NULL,
                image BLOB,
                created_at INTEGER NOT NULL,
                ttl INTEGER NOT NULL,
                expires_at INTEGER NOT NULL,
                hop_count INTEGER NOT NULL,
                signature TEXT NOT NULL,
                received_at INTEGER NOT NULL,
                received_from TEXT,
                received_signal INTEGER,
                raw TEXT NOT NULL
            )""",
            "CREATE INDEX posts_by_expiry ON posts(expires_at)",
            "CREATE INDEX posts_by_author ON posts(author_id, created_at)",
            """CREATE TABLE comments (
                id TEXT PRIMARY KEY,
                post_id TEXT NOT NULL,
                author_id TEXT NOT NULL,
                body TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                hop_count INTEGER NOT NULL,
                signature TEXT NOT NULL,
                received_at INTEGER NOT NULL,
                raw TEXT NOT NULL
            )""",
            "CREATE INDEX comments_by_post ON comments(post_id, created_at)",
            "CREATE INDEX comments_by_author ON comments(author_id, created_at)",
            """CREATE TABLE reactions (
                id TEXT PRIMARY KEY,
                target_id TEXT NOT NULL,
                post_id TEXT NOT NULL,
                author_id TEXT NOT NULL,
                type TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                hop_count INTEGER NOT NULL,
                signature TEXT NOT NULL,
                raw TEXT NOT NULL,
                UNIQUE (target_id, author_id)
            )""",
            "CREATE INDEX reactions_by_post ON reactions(post_id)",
            "CREATE INDEX reactions_by_author ON reactions(author_id, created_at)",
            """CREATE TABLE profiles (
                public_key TEXT PRIMARY KEY,
                id TEXT NOT NULL UNIQUE,
                encryption_key TEXT NOT NULL,
                display_name TEXT,
                avatar BLOB,
                updated_at INTEGER NOT NULL,
                hop_count INTEGER NOT NULL,
                signature TEXT NOT NULL,
                raw TEXT NOT NULL,
                last_seen_at INTEGER,
                last_signal INTEGER
            )""",
            // status: PENDING | DELIVERED (sent by us), RECEIVED (to us),
            // RELAY (someone else's message we are carrying).
            """CREATE TABLE direct_messages (
                id TEXT PRIMARY KEY,
                from_id TEXT NOT NULL,
                to_id TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL,
                hop_count INTEGER NOT NULL,
                raw TEXT NOT NULL,
                body TEXT,
                status TEXT NOT NULL
            )""",
            "CREATE INDEX dms_by_from ON direct_messages(from_id, created_at)",
            "CREATE INDEX dms_by_to ON direct_messages(to_id, created_at)",
            """CREATE TABLE receipts (
                id TEXT PRIMARY KEY,
                message_id TEXT NOT NULL,
                from_id TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL,
                hop_count INTEGER NOT NULL,
                raw TEXT NOT NULL
            )""",
            "CREATE INDEX receipts_by_message ON receipts(message_id)",
            // Ids we never want again: deleted, reported, superseded, spam.
            "CREATE TABLE tombstones (id TEXT PRIMARY KEY, created_at INTEGER NOT NULL)",
            "CREATE TABLE blocked (user_id TEXT PRIMARY KEY, created_at INTEGER NOT NULL)",
        ),
        // v2: encrypted chat image bytes stored after decryption for the recipient.
        listOf("ALTER TABLE direct_messages ADD COLUMN image BLOB"),
    )
}
