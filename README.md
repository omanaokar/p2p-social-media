# Proximity

Serverless social app: your feed is posts from people physically near you.
Android-first, Kotlin.

## Modules

| Module      | Owner | What                                                                 |
|-------------|-------|----------------------------------------------------------------------|
| `contracts` | all   | Shared interfaces: `Transport`, repositories, `Ranker`, data models.  |
| `app-core`  | Om    | SQLite storage, identity, posts/comments/reactions, DMs, sync, calls. |

Both are plain Kotlin/JVM libraries, so they build and test without a device.
The Android app module depends on them.

```bash
./gradlew :app-core:test
```

## Using app-core from the Android app

```kotlin
val core = AppCore.open(
    transport = nearbyTransport,                 // Harshil's Transport implementation
    keyStore = myAndroidKeyStore,                // implement KeyStore (EncryptedSharedPreferences / Keystore)
    driver = BundledSQLiteDriver(),              // androidx.sqlite:sqlite-bundled
    databasePath = context.getDatabasePath("proximity.db").path,
    mediaEngineFactory = null,                   // calls are a stretch goal
)
core.start()

core.posts.watchFeedCandidates()   // Flow<List<FeedCandidate>> -> Neil's Ranker -> Sarthak's feed
core.posts.create("hello")
core.messages.send(userId, "hi")
core.profiles.watchNearbyPeers()
```

Until the real transport lands, `com.proximity.core.testing.InMemoryNetwork` simulates phones joining and leaving.

## Decisions taken in app-core (review as a team)

- **Serialization:** JSON. Every synced object is a `SignedObject`: `id` is the SHA-256 of the canonical JSON content, and `sig` is an Ed25519 signature over the same bytes. The hop count sits outside the signature because relays bump it.
- **Visibility rule (b):** the feed shows any post received within its TTL (default 6 h).
- **Hops:** posts travel up to 2 relays (3 links). DMs travel up to 6 relays and live 72 h, so store-and-forward has a chance.
- **Sync:** on connect, both sides send `hello` (signed profile plus the ids of everything forwardable). Each side then sends `want` for what it lacks and gets `objs` back. Newly stored objects are re-announced to other peers with `have`, so content spreads in waves.
- **DMs:** X25519 → HKDF-SHA256 → ChaCha20-Poly1305. Profiles carry each user's X25519 key, so you can only DM someone whose profile you've received. The recipient sends back a signed **receipt**. Carriers drop the message once they see it, and the sender's copy flips to `DELIVERED`.
- **Data model additions:** `Reaction.postId` lets reactions expire with their post. `Profile.encryptionKey` was added for DMs.
- **Spam guard:** size caps, a TTL cap, clock-skew checks, per-author rate limits (10 posts and 60 other objects per 10 min), and a local block list.

## Known gaps

- Deleting a post only removes it from your own phone; other copies expire on their TTL. A signed delete would fix this.
- A peer can replay someone else's public profile in its `hello`. Contents are still signature-checked, but link identity is not proven. A challenge-response would fix this.
- DMs have no forward secrecy.
- Calls: signalling works, and media goes through the `MediaEngine` interface (WebRTC on Android). WebRTC needs an IP path between phones, which Nearby Connections does not provide.
