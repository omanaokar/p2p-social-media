package com.proximity.core.repo

import com.proximity.contracts.IdentityService
import com.proximity.contracts.PeerProfile
import com.proximity.contracts.Profile
import com.proximity.contracts.ProfileRepository
import com.proximity.core.Clock
import com.proximity.core.db.AppDatabase
import com.proximity.core.db.blob
import com.proximity.core.db.long
import com.proximity.core.db.optInt
import com.proximity.core.db.optLong
import com.proximity.core.db.optStr
import com.proximity.core.db.str
import com.proximity.core.store.ObjectStore
import com.proximity.core.store.toProfile
import com.proximity.core.sync.PeerTable
import com.proximity.core.sync.SyncEngine
import com.proximity.core.toBase64
import com.proximity.core.wire.ObjectKind
import com.proximity.core.wire.SignedObject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.time.Instant

class ProfileRepositoryImpl(
    private val db: AppDatabase,
    private val store: ObjectStore,
    private val sync: SyncEngine,
    private val peers: PeerTable,
    private val identity: IdentityService,
    private val clock: Clock,
    private val io: CoroutineDispatcher,
) : ProfileRepository {

    /** Creates a blank signed profile on first launch; peers need it to learn our encryption key. */
    suspend fun ensureProfile() {
        if (store.profileObject(identity.currentUser.id) == null) updateMyProfile()
    }

    override suspend fun updateMyProfile(displayName: String?, avatar: ByteArray?): Profile = withContext(io) {
        val user = identity.currentUser
        val previous = db.query("SELECT display_name, avatar, updated_at FROM profiles WHERE public_key = ?", user.id).firstOrNull()
        val updatedAt = maxOf(clock.now().toEpochMilli(), (previous?.long("updated_at") ?: Long.MIN_VALUE) + 1)
        val obj = SignedObject.create(
            ObjectKind.PROFILE,
            mapOf(
                "publicKey" to user.id,
                "encryptionKey" to user.encryptionKey,
                "displayName" to (displayName ?: previous?.optStr("display_name")),
                "avatar" to (avatar ?: previous?.blob("avatar"))?.toBase64(),
                "updatedAt" to updatedAt,
            ),
            identity,
        )
        store.saveLocal(obj)
        sync.publish(obj)
        myProfile()
    }

    private fun myProfile(): Profile =
        db.query("SELECT * FROM profiles WHERE public_key = ?", identity.currentUser.id).first().toProfile()

    override fun watchMyProfile(): Flow<Profile> = db.watch(setOf("profiles")) { myProfile() }.flowOn(io)

    override suspend fun getProfile(userId: String): PeerProfile? = withContext(io) {
        db.query("SELECT * FROM profiles WHERE public_key = ?", userId).firstOrNull()?.let { row ->
            PeerProfile(
                profile = row.toProfile(),
                lastSeenAt = row.optLong("last_seen_at")?.let(Instant::ofEpochMilli),
                lastSignalStrength = row.optInt("last_signal"),
                isNearby = userId in peers.nearbyUserIds,
            )
        }
    }

    override fun watchNearbyPeers(): Flow<List<PeerProfile>> = db.watch(setOf("profiles", "blocked"), peers.changes) {
        val signals = peers.all.filter { it.userId != null && it.userId != identity.currentUser.id }
            .associate { it.userId!! to it.signalStrength }
        if (signals.isEmpty()) return@watch emptyList()
        db.query(
            """SELECT * FROM profiles
               WHERE public_key IN (${signals.keys.joinToString { "?" }})
                 AND public_key NOT IN (SELECT user_id FROM blocked)""",
            *signals.keys.toTypedArray(),
        ).map { row ->
            PeerProfile(
                profile = row.toProfile(),
                lastSeenAt = clock.now(),
                lastSignalStrength = signals[row.str("public_key")] ?: row.optInt("last_signal"),
                isNearby = true,
            )
        }.sortedByDescending { it.lastSignalStrength ?: Int.MIN_VALUE }
    }.flowOn(io)

    override suspend fun block(userId: String) = withContext(io) { store.block(userId) }

    override suspend fun unblock(userId: String) = withContext(io) { store.unblock(userId) }

    override fun watchBlocked(): Flow<List<String>> = db.watch(setOf("blocked")) {
        db.query("SELECT user_id FROM blocked ORDER BY created_at").map { it.str("user_id") }
    }.flowOn(io)
}
