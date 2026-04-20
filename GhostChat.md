# GhostChat — Prototype Spec (v0.2)

> **Goal:** A working ephemeral messenger prototype that handles **100k concurrent WebSocket connections** on a single Linux node. Keep it simple. No E2EE, no Protobuf, no Lottie — just a fast, functional burn-after-reading chat.
>
> **Changes from v0.1:** Fixed routing logic for single-node, added backpressure policy, fixed offline message race, clarified reconnect behavior, bumped user ID length, updated WebSocket library path.

---

## 1. Architecture Overview

```
┌──────────────┐        WSS        ┌─────────────────────┐
│ Android App  │ ◄──────────────► │   Go Backend         │
│ (Jetpack     │    JSON/WS       │   (coder/websocket)  │
│  Compose +   │                  │                      │
│  Ktor WS)    │                  │   ┌───────────────┐  │
└──────────────┘                  │   │ Redis 7+      │  │
                                  │   │ - Offline buf │  │
                                  │   │ - TTL 6hr     │  │
                                  │   └───────────────┘  │
                                  └─────────────────────┘
```

**Single server target:** 1 Linux node, 100k concurrent WebSocket connections. No Redis Pub/Sub in prototype — added later when multi-node is needed.

---

## 2. Tech Stack

| Component       | Technology                  | Why                                         |
|-----------------|-----------------------------|---------------------------------------------|
| Backend         | Go 1.22+ (`github.com/coder/websocket`) | Goroutine-per-conn scales well     |
| Message broker  | Redis 7+ (storage only)     | In-memory, native TTL, LMPOP for atomicity |
| Transport       | WebSocket over TLS (WSS)    | Bi-directional, low overhead                |
| Message format  | JSON                        | Simple, debuggable, good enough for proto   |
| Android UI      | Jetpack Compose + Ktor WS   | Modern, reactive, built-in WS support       |
| Identity        | UUID v4 (first 12 chars)    | Avoids birthday collisions up to ~millions  |

> **Note:** `nhooyr/websocket` was renamed to `github.com/coder/websocket` in 2024. Use the new path.

---

## 3. Backend (Go)

### 3.1 Project Structure

```
ghostchat-server/
├── main.go              # Entry point, HTTP server, WebSocket upgrade
├── hub.go               # Connection registry + message routing
├── client.go            # Per-connection read/write loops + send channel
├── redis.go             # Redis client wrapper (offline storage)
├── types.go             # Message structs
├── go.mod
└── go.sum
```

### 3.2 Message Format (JSON)

All messages between client and server use this envelope:

```json
{
  "type": "msg",
  "from": "a1b2c3d4e5f6",
  "to": "e5f6g7h8i9j0",
  "body": "hello world",
  "ts": 1718000000
}
```

**Message types:**

| `type`     | Direction       | Purpose                            |
|------------|-----------------|------------------------------------|
| `msg`      | client → server | Send a chat message                |
| `msg`      | server → client | Deliver a chat message             |
| `ack`      | server → client | Confirm message received by server |
| `history`  | server → client | Deliver offline message batch      |
| `register` | client → server | Announce user ID on connect        |

### 3.3 Connection Flow

```
1. Client opens WSS connection to /ws
2. Client sends: {"type": "register", "from": "<user_id>"}
3. Server registers connection in local map: connMap[user_id] = client
   → If user_id already has an entry, close old conn first (reconnect case)
4. Server drains offline messages atomically:
   → Loop: LPOP offline:<user_id> until empty
   → Send all as {"type": "history", "messages": [...]}
5. Client is now live. Incoming messages route directly.
```

**Note:** Use `LPOP` in a loop (or `LMPOP offline:<id> COUNT 100`) instead of `LRANGE` + `DEL`. This avoids losing messages that arrive between the two calls.

### 3.4 Message Routing Logic (Single-Node Prototype)

```
Sender sends {"type": "msg", "to": "recipient_id", "body": "..."}

1. Server stamps server-side timestamp (ignore client ts).
2. Look up recipient_id in local connMap:
   → If found: enqueue to recipient's send channel (see 3.7)
   → If NOT found: LPUSH offline:<recipient_id> <json_blob>
                   EXPIRE offline:<recipient_id> 21600  (rolling 6hr)
3. Send ACK back to sender: {"type": "ack", "ts": <server_ts>}
```

**No Redis Pub/Sub in the prototype.** Single-node means "not in local map = offline." When multi-node is added later, insert a PUBLISH step between local-map-miss and LPUSH.

### 3.5 Hub (Connection Registry)

```go
type Hub struct {
    mu    sync.RWMutex
    conns map[string]*Client   // user_id → client
}
```

- Use `sync.RWMutex` — reads (routing lookups) vastly outnumber writes.
- On disconnect: remove from map, close send channel.
- On reconnect with same user_id: close the old client, replace with new.

### 3.6 Redis Usage

Use `github.com/redis/go-redis/v9`.

| Operation | Redis Command | Purpose |
|-----------|--------------|---------|
| Offline store | `LPUSH offline:<id> <blob>` | Buffer messages for offline users |
| Offline TTL | `EXPIRE offline:<id> 21600` | Rolling 6hr window (refreshed each push) |
| Offline drain | `LMPOP 1 offline:<id> LEFT COUNT 100` (loop) | Atomic pop on reconnect |

**Redis config (redis.conf):**

```
maxmemory 4gb
maxmemory-policy allkeys-lru
save ""
appendonly no
```

- `save ""` and `appendonly no` disable ALL disk persistence — pure RAM.
- `allkeys-lru` evicts oldest keys under memory pressure. **Known trade-off:** offline messages may be evicted before the 6hr TTL if memory fills. This is acceptable for an ephemeral prototype.

### 3.7 Backpressure (Per-Client Send Channel)

**Critical:** do not write directly to a WebSocket connection from the routing goroutine. One slow client will block senders.

Each `Client` gets a bounded send channel:

```go
type Client struct {
    userID string
    conn   *websocket.Conn
    send   chan []byte   // buffered, size 64
}
```

- Routing goroutine does **non-blocking send**: `select { case c.send <- msg: default: disconnect }`.
- A dedicated write goroutine per client drains `send` and writes to the conn.
- If the channel is full, the client is too slow — close the connection and let them reconnect. Drop the message (prototype-level policy; offline buffer will catch it on reconnect).

### 3.8 Known Races (Prototype-Acceptable)

Document these so they're intentional, not surprises:

1. **Reconnect race:** If a message is LPUSHed while the user is mid-handshake, it ends up in offline buffer but the user just drained it. It will be delivered on their *next* reconnect. For a prototype, acceptable.
2. **Registration is unauthenticated:** Any client can claim any user_id. Known issue, deferred to auth milestone.

### 3.9 Graceful Shutdown

```
On SIGINT/SIGTERM:
1. Stop accepting new connections
2. Close all WebSocket connections with CloseGoingAway
3. Close Redis connection
4. Exit
```

---

## 4. Linux Tuning (100k Connections)

Apply to `/etc/sysctl.conf` and reload with `sysctl -p`:

```bash
# Max open file descriptors
fs.file-max = 2097152

# Ephemeral port range
net.ipv4.ip_local_port_range = 1024 65535

# TCP buffer sizes
net.core.rmem_max = 16777216
net.core.wmem_max = 16777216
net.ipv4.tcp_rmem = 4096 87380 16777216
net.ipv4.tcp_wmem = 4096 65536 16777216

# Connection backlog
net.ipv4.tcp_max_syn_backlog = 65536
net.core.somaxconn = 65536
```

Per-process limits:

```bash
# /etc/security/limits.conf
* soft nofile 1048576
* hard nofile 1048576
```

Or in the systemd unit file:

```ini
[Service]
LimitNOFILE=1048576
```

### 4.1 Memory Budget (Realistic)

Plan for roughly:

- **~50KB per idle WebSocket connection** (goroutine stacks + read/write buffers)
- **100k connections ≈ 5GB** for the Go process alone
- **+ 4GB** Redis budget
- **Total: ~9–10GB RAM** minimum for the target load

If your test node has less, scale down the target (e.g. 50k) first.

### 4.2 tmpfs (Optional RAM Disk)

For zero-trace operation, run Redis data dir on tmpfs:

```bash
mount -t tmpfs -o size=8G tmpfs /mnt/ghostchat_data
```

Power off or reboot = all data gone.

---

## 5. Android Client

### 5.1 Project Structure

```
app/
├── MainActivity.kt
├── ui/
│   ├── ChatScreen.kt         # Main chat UI
│   ├── ContactsScreen.kt     # Simple contact list
│   └── theme/Theme.kt
├── data/
│   ├── WebSocketClient.kt    # Ktor WS connection manager
│   ├── MessageRepository.kt  # In-memory message store
│   └── UserPrefs.kt          # UUID storage (SharedPreferences)
├── model/
│   └── Message.kt
└── viewmodel/
    └── ChatViewModel.kt
```

### 5.2 Identity

On first launch:
1. Generate `UUID.randomUUID().toString().replace("-", "").take(12)` — 12-char hex ID.
2. Store in `SharedPreferences` (plain, not encrypted — prototype).
3. Display to user so they can share it.

**Why 12 chars:** 8 chars starts hitting birthday collisions around 65k users. 12 chars gives collision-free operation well past a million users.

### 5.3 WebSocket Connection

Use **Ktor** `io.ktor:ktor-client-websockets`.

```
Connect to: wss://<server>/ws
On open:    send {"type": "register", "from": "<my_id>"}
On message: parse JSON, update ViewModel
On close:   reconnect with exponential backoff (1s, 2s, 4s, max 30s)
```

**Reconnect rules:**
- On every successful reconnect, re-send the `register` message (server treats it as a fresh registration).
- Queue outbound messages locally while disconnected; flush after re-registration completes.
- On process death, in-flight unsent messages are lost. Acceptable for ephemeral prototype.

### 5.4 Chat UI (Jetpack Compose)

Minimal screens:

1. **Home Screen:**
   - Show user's own ID (tappable to copy)
   - Text field to enter a recipient ID
   - List of recent conversations (in-memory only)

2. **Chat Screen:**
   - Message list (LazyColumn, bottom-aligned)
   - Text input + send button
   - Messages stored in ViewModel only — process death = cleared history (intentional; this is the "ephemeral" part)

### 5.5 Message Data Class

```kotlin
data class Message(
    val type: String = "msg",
    val from: String,
    val to: String,
    val body: String,
    val ts: Long = System.currentTimeMillis() / 1000
)
```

Use `kotlinx.serialization` for JSON.

---

## 6. What This Prototype Does NOT Include

| Feature | Why deferred |
|---------|-------------|
| End-to-End Encryption | Complex to implement correctly; TLS is sufficient for prototype |
| Authenticated registration | Anyone can claim any user_id — known issue |
| Protobuf | JSON is fine for prototype |
| Lottie animations | UX polish, not functional |
| Rate limiting / abuse prevention | Not needed until real users |
| Multi-node scaling | Single-node only; add Redis Pub/Sub later |
| User blocking | Social feature, not core |
| Push notifications (FCM) | Prototype uses persistent WS only |
| Message read receipts | Nice-to-have, not MVP |
| Group chats | 1:1 only |
| File/image sharing | Text only |

---

## 7. How to Run

### Backend

```bash
# Prerequisites: Go 1.22+, Redis 7+
cd ghostchat-server
go mod tidy
go run .

# Server listens on :8080
# WebSocket endpoint: ws://localhost:8080/ws
```

### Redis

```bash
redis-server --maxmemory 4gb --maxmemory-policy allkeys-lru \
  --save "" --appendonly no --port 6379
```

### Android

```bash
# Open in Android Studio
# Update SERVER_URL in WebSocketClient.kt to your server IP
# Build and run on device/emulator
```

---

## 8. Load Testing

Two scenarios — connection capacity AND message throughput.

### 8.1 Connection Capacity (k6)

```javascript
import ws from 'k6/ws';
import { check } from 'k6';

export const options = {
  stages: [
    { duration: '2m', target: 50000 },
    { duration: '2m', target: 100000 },
    { duration: '5m', target: 100000 },
    { duration: '1m', target: 0 },
  ],
};

export default function () {
  const userId = `user_${__VU}`;
  const url = 'ws://YOUR_SERVER:8080/ws';

  const res = ws.connect(url, {}, function (socket) {
    socket.on('open', () => {
      socket.send(JSON.stringify({ type: 'register', from: userId }));
    });
    socket.setTimeout(() => socket.close(), 300000);
  });

  check(res, { 'ws connected': (r) => r && r.status === 101 });
}
```

### 8.2 Message Throughput

Separate scenario: 10k connection pairs exchanging one message every 5 seconds. Measure:
- p50, p99 end-to-end latency
- Dropped messages (compare sent count to received count)
- Go process memory + CPU
- Redis memory

**Success criteria:**
- 100k concurrent WS connections sustained for 5 minutes
- Message delivery latency p99 < 100ms under active traffic
- Memory usage < 10GB (Go + Redis combined)
- Zero dropped messages under steady state (not counting intentional slow-client disconnects from 3.7)

---

## 9. Build Order

1. **Go backend** — WebSocket handler + Hub + per-client send channel + Redis offline store (~500 lines)
2. **Test with wscat** — manual WebSocket testing before building the app
3. **Android client** — Compose UI + Ktor WebSocket (~6 files)
4. **Load test with k6** — connection capacity, then throughput
5. **Iterate** — fix bottlenecks found in load testing

Ship the prototype, validate with load tests, then layer in authentication, multi-node, and encryption.
