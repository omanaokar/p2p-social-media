# Agent Handoff: P2P Networking & Connectivity Layer (`:transport`)

> **For Agents & Engineers:** This document provides a comprehensive technical handoff of the P2P networking and connectivity layer built for the Proximity project. It explains the system architecture, component responsibilities, integration contracts, design decisions, and how to verify and extend the code.

---

## 1. Context & Ownership

* **Layer:** Layer 4 — Transport Layer (P2P Networking & Connectivity).
* **Owner:** Harshil.
* **Contract Interface:** [`Transport`](file:///c:/Users/harsh/Desktop/p2p-social-media/contracts/src/main/kotlin/com/proximity/contracts/Transport.kt) in `:contracts`.
* **Consumer:** Om's Application Layer (`:app-core`) via [`AppCore.open(transport = ...)`](file:///c:/Users/harsh/Desktop/p2p-social-media/app-core/src/main/kotlin/com/proximity/core/AppCore.kt).
* **Downstream Consumers:** Sarthak's UI (`:ui`) for peer lists and scanning state; Neil's Local Recommendation Engine (`:ranker`) for proximity signals.
* **Core Rule:** The transport layer treats **every payload strictly as opaque bytes**. It never inspects, deserializes, or parses posts, comments, or DMs.

---

## 2. Architecture Overview

```mermaid
flowchart TD
    subgraph AppCore["Application Layer (Om)"]
        SyncEngine["SyncEngine / ObjectStore"]
    end

    subgraph TransportModule[":transport Module (Harshil)"]
        NearbyTransport["NearbyTransport (implements Transport)"]
        
        subgraph Framing["Framing & Chunking"]
            Chunker["PayloadChunker\n(24KB slices, CRC32, Transfer ID)"]
            Assembler["PayloadAssembler\n(Reassembly, Timeout, Dedup)"]
            Codec["FrameCodec\n(Binary wire protocol)"]
        end

        subgraph Management["Peer & Lifecycle Management"]
            PeerTable["PeerTable\n(EWMA RSSI Smoothing, Proximity Tiers)"]
            Lifecycle["ConnectionLifecycleManager\n(Max 6 links, Collision tie-breaker, Backoff)"]
            DutyCycle["DutyCycleController\n(15s Scan / 30s Sleep, Battery Profiles)"]
        end

        subgraph Drivers["Radio Drivers"]
            InProcessDriver["InProcessNearbyDriver + RadioMedium\n(JVM testing & CI)"]
            AndroidDriver["AndroidNearbyDriver\n(Google Play Services Nearby Connections)"]
        end

        subgraph TestingMocks["Testing & Simulation"]
            MockTransport["MockTransport\n(Interactive mock for week 1)"]
            Simulator["TransportSimulator\n(2D spatial physics & walk-away demo)"]
        end
    end

    SyncEngine <-->|send / onReceive (opaque bytes)| NearbyTransport
    NearbyTransport --> Chunker
    NearbyTransport --> Assembler
    Chunker --> Codec
    Assembler --> Codec
    NearbyTransport --> PeerTable
    NearbyTransport --> Lifecycle
    NearbyTransport --> DutyCycle
    NearbyTransport <--> Drivers
```

---

## 3. Directory & File Manifest

All new code is located in [`transport/`](file:///c:/Users/harsh/Desktop/p2p-social-media/transport):

| Package / Path | File | Purpose |
|---|---|---|
| `com.proximity.transport` | [`TransportConfig.kt`](file:///c:/Users/harsh/Desktop/p2p-social-media/transport/src/main/kotlin/com/proximity/transport/TransportConfig.kt) | Centralized configuration (service ID, chunk sizes, link caps, duty cycling intervals, backoff). |
| `com.proximity.transport.framing` | [`Frame.kt`](file:///c:/Users/harsh/Desktop/p2p-social-media/transport/src/main/kotlin/com/proximity/transport/framing/Frame.kt) | Sealed hierarchy for `SingleFrame`, `ChunkFrame`, `HeartbeatFrame`, and `AckFrame`. |
| `com.proximity.transport.framing` | [`FrameCodec.kt`](file:///c:/Users/harsh/Desktop/p2p-social-media/transport/src/main/kotlin/com/proximity/transport/framing/FrameCodec.kt) | Zero-copy binary framing protocol with magic byte (`0x50`), versioning, and length headers. |
| `com.proximity.transport.framing` | [`PayloadChunker.kt`](file:///c:/Users/harsh/Desktop/p2p-social-media/transport/src/main/kotlin/com/proximity/transport/framing/PayloadChunker.kt) | Splits large byte arrays (> 24 KB, e.g. images) into sequenced chunks with CRC32 integrity checksums. |
| `com.proximity.transport.framing` | [`PayloadAssembler.kt`](file:///c:/Users/harsh/Desktop/p2p-social-media/transport/src/main/kotlin/com/proximity/transport/framing/PayloadAssembler.kt) | Reassembles incoming chunks, verifies CRC32, ignores duplicates, and cleans up expired partial buffers. |
| `com.proximity.transport.peer` | [`PeerTable.kt`](file:///c:/Users/harsh/Desktop/p2p-social-media/transport/src/main/kotlin/com/proximity/transport/peer/PeerTable.kt) | Thread-safe peer tracking, emitting `peers`, `onPeerConnected`, `onPeerLost`, EWMA signal smoothing, and `ProximityTier`. |
| `com.proximity.transport.connection` | [`ConnectionLifecycleManager.kt`](file:///c:/Users/harsh/Desktop/p2p-social-media/transport/src/main/kotlin/com/proximity/transport/connection/ConnectionLifecycleManager.kt) | Enforces 6-link cap, deterministic collision tie-breaker (`localName < remoteName`), and exponential backoff retry. |
| `com.proximity.transport.battery` | [`DutyCycleController.kt`](file:///c:/Users/harsh/Desktop/p2p-social-media/transport/src/main/kotlin/com/proximity/transport/battery/DutyCycleController.kt) | Alternates active scanning (15s) and sleep (30s) windows with battery power profiles (`NORMAL`, `BATTERY_SAVER`, `AGGRESSIVE_DISCOVERY`). |
| `com.proximity.transport.nearby` | [`NearbyDriver.kt`](file:///c:/Users/harsh/Desktop/p2p-social-media/transport/src/main/kotlin/com/proximity/transport/nearby/NearbyDriver.kt) | Driver abstraction decoupling the transport state machine from Google Play Services. |
| `com.proximity.transport.nearby` | [`NearbyTransport.kt`](file:///c:/Users/harsh/Desktop/p2p-social-media/transport/src/main/kotlin/com/proximity/transport/nearby/NearbyTransport.kt) | Core engine implementing `com.proximity.contracts.Transport`. |
| `com.proximity.transport.nearby` | [`InProcessNearbyDriver.kt`](file:///c:/Users/harsh/Desktop/p2p-social-media/transport/src/main/kotlin/com/proximity/transport/nearby/InProcessNearbyDriver.kt) | In-process multi-phone radio medium for JVM tests and emulators. |
| `com.proximity.transport.android` | [`NearbyPermissions.kt`](file:///c:/Users/harsh/Desktop/p2p-social-media/transport/src/main/kotlin/com/proximity/transport/android/NearbyPermissions.kt) | Runtime permissions for Android 12+ (API 31+: `BLUETOOTH_SCAN/ADVERTISE/CONNECT`, API 33+: `NEARBY_WIFI_DEVICES`), legacy Android, and UI rationale text. |
| `com.proximity.transport.android` | [`NearbyForegroundServiceDescriptor.kt`](file:///c:/Users/harsh/Desktop/p2p-social-media/transport/src/main/kotlin/com/proximity/transport/android/NearbyForegroundServiceDescriptor.kt) | Foreground service constants, notification channel config, and live scanning text formatting. |
| `com.proximity.transport.android` | [`AndroidNearbyDriver.kt`](file:///c:/Users/harsh/Desktop/p2p-social-media/transport/src/main/kotlin/com/proximity/transport/android/AndroidNearbyDriver.kt) | Bridge connecting `NearbyDriver` to Android Google Play Services Nearby Connections (`P2P_CLUSTER`). |
| `resources` | [`AndroidManifest_snippet.xml`](file:///c:/Users/harsh/Desktop/p2p-social-media/transport/src/main/resources/AndroidManifest_snippet.xml) | Ready-to-copy Android manifest declarations for permissions and foreground service. |
| `com.proximity.transport.mock` | [`MockTransport.kt`](file:///c:/Users/harsh/Desktop/p2p-social-media/transport/src/main/kotlin/com/proximity/transport/mock/MockTransport.kt) | Scriptable mock transport delivered for Week 1 so UI, sync, and ranker can build without devices. |
| `com.proximity.transport.simulation` | [`TransportSimulator.kt`](file:///c:/Users/harsh/Desktop/p2p-social-media/transport/src/main/kotlin/com/proximity/transport/simulation/TransportSimulator.kt) | 2D coordinates, log-distance path loss RSSI ($RSSI = RSSI_0 - 10 \cdot n \cdot \log_{10}(d)$), and walk-away demo helper. |

---

## 4. Key Architectural Mechanisms

### 4.1. Chunking & Wire Protocol
Google Nearby Connections limits single-byte payloads to 32 KB (`MAX_BYTES_DATA_SIZE = 32768`). Posts with compressed images or bundled sync batches exceed this limit.
* **Threshold:** Payloads $\le 24\text{ KB}$ are transmitted as a single `SingleFrame`.
* **Chunking:** Payloads $> 24\text{ KB}$ are broken down into $N$ slices of 24 KB.
* **Chunk Frame Header:**
  * 1 byte: Magic byte (`0x50` = ASCII 'P')
  * 1 byte: Version (`0x01`)
  * 1 byte: Frame Type (`0x02` for chunk)
  * 8 bytes: `transferId` (unique 64-bit ID)
  * 4 bytes: `totalPayloadLength`
  * 2 bytes: `totalChunks`
  * 2 bytes: `chunkIndex` (0-based)
  * 8 bytes: `totalCrc32` (CRC32 of the complete original payload)
  * 4 bytes: `chunkDataLength`
  * $K$ bytes: raw chunk data
* **Reassembly:** [`PayloadAssembler`](file:///c:/Users/harsh/Desktop/p2p-social-media/transport/src/main/kotlin/com/proximity/transport/framing/PayloadAssembler.kt) buffers chunks per `(peerId, transferId)`. When all chunks arrive, it validates the CRC32 checksum. If corrupted, it is discarded. An assembly timeout (default 30s) automatically purges incomplete transfers from departed peers.

### 4.2. Connection Lifecycle & Collision Tie-Breaking
When Phone A and Phone B run simultaneous discovery and advertising, both discover each other at the same time:
* **The Problem:** If both issue `requestConnection` simultaneously, Nearby Connections fails with `STATUS_ALREADY_CONNECTED_TO_ENDPOINT` or enters conflicting states.
* **The Solution:** [`ConnectionLifecycleManager`](file:///c:/Users/harsh/Desktop/p2p-social-media/transport/src/main/kotlin/com/proximity/transport/connection/ConnectionLifecycleManager.kt) enforces a deterministic tie-breaker:
  $$\text{shouldInitiate} = (\text{localEndpointName} < \text{remoteEndpointName})$$
  The node with the lower name initiates connection; the node with the higher name waits and accepts. This eliminates collision races.
* **Simultaneous Links Cap:** Capped at `maxSimultaneousLinks = 6`. Further discovery requests are held or rejected.
* **Exponential Backoff:** If a link drops unexpectedly, reconnect attempts follow $delay = \min(initial \cdot 2^{attempt}, max) + jitter$, preventing connection thrashing.

### 4.3. Peer Table & EWMA Signal Smoothing
Neil's recommendation engine uses signal strength as a proximity ranking feature:
* **EWMA Smoothing:** Raw Bluetooth/Wi-Fi RSSI values fluctuate wildly due to multipath fading. We apply an Exponentially Weighted Moving Average:
  $$S_t = \alpha \cdot Y_t + (1 - \alpha) \cdot S_{t-1}$$
  with default $\alpha = 0.3$.
* **Proximity Tiers:** Classified into `IMMEDIATE` ($\ge -60\text{ dBm}$), `NEAR` ($-75$ to $-60\text{ dBm}$), and `FAR` ($< -75\text{ dBm}$).

### 4.4. Battery Policy & Duty Cycling
Continuous scanning drains phone batteries rapidly:
* [`DutyCycleController`](file:///c:/Users/harsh/Desktop/p2p-social-media/transport/src/main/kotlin/com/proximity/transport/battery/DutyCycleController.kt) alternates between active discovery (15s) and idle sleep (30s).
* Advertising remains active or in low-power mode throughout so the device can always be discovered by others.
* Power profiles adjust intervals dynamically based on device battery state.

---

## 5. Integration With `app-core`

The integration was verified in [`OmAppCoreIntegrationTest.kt`](file:///c:/Users/harsh/Desktop/p2p-social-media/transport/src/test/kotlin/com/proximity/transport/OmAppCoreIntegrationTest.kt):

```kotlin
// Device A
val driverA = InProcessNearbyDriver("ep-a", "Phone-A", medium)
val transportA = NearbyTransport("Phone-A", driver = driverA)
val coreA = AppCore.open(
    transport = transportA,
    keyStore = InMemoryKeyStore(),
    driver = BundledSQLiteDriver(),
    databasePath = null,
)

// Device B
val driverB = InProcessNearbyDriver("ep-b", "Phone-B", medium)
val transportB = NearbyTransport("Phone-B", driver = driverB)
val coreB = AppCore.open(
    transport = transportB,
    keyStore = InMemoryKeyStore(),
    driver = BundledSQLiteDriver(),
    databasePath = null,
)

coreA.start()
coreB.start()

// Post created on A syncs via transport to B:
val post = coreA.posts.create("Hello over P2P mesh!")
val feedAtB = coreB.posts.watchFeedCandidates().awaitMatching { list ->
    list.any { it.post.id == post.id }
}

// Direct message sent from A delivers to B:
coreA.messages.send(coreB.identity.currentUser.id, "Hi peer!")
```

---

## 6. Verification & Test Commands

The project builds with Gradle 9.5 and Kotlin 2.3.0. To execute all unit and integration tests:

```powershell
# Set JAVA_HOME to JDK 21 (Android Studio JBR):
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"

# Run all tests across contracts, app-core, and transport:
.\gradlew test

# Or run transport tests only:
.\gradlew :transport:test
```

### Test Suites Included in `:transport`:
1. **`FrameCodecTest`**: Encodes/decodes all frame types, rejects invalid magic byte and malformed buffers.
2. **`PayloadChunkingAndReassemblyTest`**: Validates chunking of 250 KB images, out-of-order reassembly, deduplication, CRC32 mismatch rejection, and timeout purging.
3. **`PeerTableTest`**: Peer add/remove events, EWMA signal smoothing calculations, and bytes tracking.
4. **`ConnectionLifecycleTest`**: Cap of 6 links, deterministic tie-breaking, and exponential backoff retry.
5. **`DutyCycleTest`**: Discovery/sleep phase transitions and continuous scan mode.
6. **`NearbyTransportTest`**: Multi-node discovery, chunked message delivery, and the 4-phone simulator walk-away test.
7. **`OmAppCoreIntegrationTest`**: Full end-to-end integration between `NearbyTransport` and Om's `AppCore`.

---

## 7. Next Steps for Other Teammates

1. **For Sarthak (UI):**
   * Use [`NearbyPermissions.requiredRuntimePermissions()`](file:///c:/Users/harsh/Desktop/p2p-social-media/transport/src/main/kotlin/com/proximity/transport/android/NearbyPermissions.kt) and [`NearbyPermissions.getRationale()`](file:///c:/Users/harsh/Desktop/p2p-social-media/transport/src/main/kotlin/com/proximity/transport/android/NearbyPermissions.kt) to construct permission screens.
   * Bind scanning status indicators to [`NearbyForegroundServiceDescriptor.formatNotificationText()`](file:///c:/Users/harsh/Desktop/p2p-social-media/transport/src/main/kotlin/com/proximity/transport/android/NearbyForegroundServiceDescriptor.kt) or transport peer counts.
   * Use [`MockTransport`](file:///c:/Users/harsh/Desktop/p2p-social-media/transport/src/main/kotlin/com/proximity/transport/mock/MockTransport.kt) for widget testing without real radios.
2. **For Neil (Ranking):**
   * Inspect [`TransportPeer.signalStrength`](file:///c:/Users/harsh/Desktop/p2p-social-media/contracts/src/main/kotlin/com/proximity/contracts/Transport.kt) and [`ProximityTier`](file:///c:/Users/harsh/Desktop/p2p-social-media/transport/src/main/kotlin/com/proximity/transport/peer/PeerTable.kt) from the peer table for ranking proximity scoring.
3. **For Om (App-Core):**
   * Production integration is ready: pass `NearbyTransport` directly into `AppCore.open(transport = nearbyTransport, ...)`.

