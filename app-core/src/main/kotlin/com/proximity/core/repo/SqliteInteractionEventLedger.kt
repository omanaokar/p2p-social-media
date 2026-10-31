package com.proximity.core.repo

import com.proximity.core.db.AppDatabase
import com.proximity.core.db.optStr
import com.proximity.core.db.str
import com.proximity.ranker.InteractionEventLedger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** SQLite state for interaction delivery. PENDING events are recovered from the profile file on retry. */
internal class SqliteInteractionEventLedger(
    private val db: AppDatabase,
    private val io: CoroutineDispatcher,
    private val userId: String,
) : InteractionEventLedger {
    override suspend fun begin(eventId: String): Boolean = withContext(io) {
        db.transaction {
            val existing = db.query("SELECT state, user_id FROM processed_interaction_events WHERE event_id = ?", eventId)
                .firstOrNull()
            val state = existing?.str("state")
            val owner = existing?.optStr("user_id")
            when {
                state == "APPLIED" -> false
                // A signed object ID is globally unique. Never resume another identity's pending event.
                state == "PENDING" && owner == userId -> true
                // v3 entries have no owner. Treat them as applied rather than risking a cross-identity update.
                state == "PENDING" -> false
                state == null -> {
                    db.execute(
                        "INSERT INTO processed_interaction_events (event_id, user_id, state) VALUES (?, ?, 'PENDING')",
                        eventId, userId,
                    )
                    true
                }
                else -> error("Unknown interaction event state: $state")
            }
        }
    }

    override suspend fun complete(eventId: String) = withContext(io) {
        db.execute("UPDATE processed_interaction_events SET state = 'APPLIED' WHERE event_id = ? AND user_id = ?", eventId, userId)
    }

    override suspend fun abandon(eventId: String) = withContext(io) {
        db.execute("DELETE FROM processed_interaction_events WHERE event_id = ? AND user_id = ? AND state = 'PENDING'", eventId, userId)
    }
}
