package com.proximity.ranker

/** Durable event state supplied by the application storage layer. */
interface InteractionEventLedger {
    /** Creates (or resumes) a pending event. False means it is already applied. */
    suspend fun begin(eventId: String): Boolean
    /** Marks an event applied only after its profile update has been persisted. */
    suspend fun complete(eventId: String)
    /** Removes a pending event whose post could not be resolved. */
    suspend fun abandon(eventId: String)
}
