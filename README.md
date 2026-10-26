# Proximity

Serverless social app: your feed is posts from people physically near you.
Android-first, Kotlin.

## Modules

| Module      | Owner   | What                                                                 |
|-------------|---------|----------------------------------------------------------------------|
| `contracts` | all     | Shared interfaces: `Transport`, repositories, `Ranker`, data models.  |
| `app-core`  | Om      | SQLite storage, identity, posts/comments/reactions, DMs, sync, calls. |
| `transport` | Harshil | P2P networking, Nearby Connections (P2P_CLUSTER), chunking, peer table with signal strength, discovery duty cycling, battery policy, simulator & mocks. |

All three are plain Kotlin/JVM libraries, so they build and test without a device.
The Android app module depends on them.

> Detailed engineering documentation and handoff for AI agents and teammates can be found in [AGENT_HANDOFF.md](file:///c:/Users/harsh/Desktop/p2p-social-media/AGENT_HANDOFF.md).

```bash
./gradlew test
# Or test specific modules:
./gradlew :app-core:test
./gradlew :transport:test
```

## Using transport and app-core from the Android app

```kotlin
// Initialize Harshil's NearbyTransport with Android driver:
val driver = AndroidNearbyDriver(contextProvider = { context })
val nearbyTransport = NearbyTransport(
    localEndpointName = "MyPhone",
    config = TransportConfig(
        serviceId = "com.proximity.p2p",
        maxSimultaneousLinks = 6,
        chunkSizeBytes = 24 * 1024,
        dutyCycleEnabled = true,
    ),
    driver = driver,
)

// Initialize Om's AppCore:
val core = AppCore.open(
    transport = nearbyTransport,                 // Harshil's Transport implementation
    keyStore = myAndroidKeyStore,                // implement KeyStore (EncryptedSharedPreferences / Keystore)
    driver = BundledSQLiteDriver(),              // androidx.sqlite:sqlite-bundled
    databasePath = context.getDatabasePath("proximity.db").path,
    mediaEngineFactory = null,                   // calls are a stretch goal
)

nearbyTransport.start()
core.start()

core.posts.watchFeedCandidates()   // Flow<List<FeedCandidate>> -> Neil's Ranker -> Sarthak's feed
core.posts.create("hello")
core.messages.send(userId, "hi")
core.profiles.watchNearbyPeers()
```

For testing without hardware, `com.proximity.transport.mock.MockTransport` and `com.proximity.transport.simulation.TransportSimulator` simulate phones joining, moving in 2D space, signal degradation, and walking away.

## P2P Transport Layer Design (Harshil)

- **Strategy:** Google Nearby Connections `P2P_CLUSTER` (M:N mesh network) running simultaneous advertising and discovery so any phone discovers any other phone.
- **Connection Lifecycle & Collision Resolution:**
  - Enforces a cap on simultaneous links (`maxSimultaneousLinks = 6`).
  - Deterministic tie-breaker for simultaneous discovery collisions (`localName < remoteName` initiates, remote accepts) preventing duplicate connections or race conditions.
  - Auto-reconnect with exponential backoff and jitter on unexpected link drops.
- **Reliable Chunking & Reassembly:**
  - Nearby Connections byte payload limit is 32 KB. Payloads exceeding `chunkSizeBytes` (24 KB) are transparently split into sequence-numbered chunk frames with CRC32 checksums and a unique 64-bit transfer ID.
  - The receiver validates CRC32 integrity, ignores duplicate chunks, handles out-of-order arrival, and purges expired partial buffers. Delivers opaque `ByteArray` to the upper layers.
- **Peer Table & Proximity Signal:**
  - Maintains connected peers, connection timestamps, bytes sent/received, and radio RSSI.
  - Exponentially Weighted Moving Average (EWMA) smoothing on signal strength to eliminate radio noise.
  - Categorizes into proximity tiers (`IMMEDIATE`, `NEAR`, `FAR`) for Neil's recommendation engine.
- **Battery Policy & Duty Cycling:**
  - `DutyCycleController` alternates discovery active scan windows (15s) and idle sleep windows (30s).
  - Adaptive power profiles (`NORMAL`, `BATTERY_SAVER`, `AGGRESSIVE_DISCOVERY`).
  - `NearbyForegroundServiceDescriptor` and Android Manifest declarations for Android 8.0 - 15+ foreground service execution (`FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE`) with live scan/peer count notification.
- **Testing & Simulator:**
  - `InProcessNearbyDriver`: Full in-memory radio medium for running real `NearbyTransport` without devices.
  - `MockTransport`: Interactive mock for Week 1 milestones.
  - `TransportSimulator`: 2D physics and log-distance path loss RSSI simulator modeling the 4-phone room demo and walk-away scenarios.
- **Opaque Bytes:** Transport never parses posts or messages; it treats every payload strictly as opaque bytes.

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
