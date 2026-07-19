# Concrete Production Plan — Distributed 1:1 Messaging System

> Everything remaining in the current PoC, what must be built, tuned, and hardened to take this architecture to production at scale.

---

## Table of Contents

1. [Current PoC Gaps — Code Level](#1-current-poc-gaps--code-level)
2. [Message Reliability & Delivery Guarantees](#2-message-reliability--delivery-guarantees)
3. [Redis — Presence & State Management](#3-redis--presence--state-management)
4. [NATS — Cross-Region Messaging](#4-nats--cross-region-messaging)
5. [gRPC — Intra-Region Communication](#5-grpc--intra-region-communication)
8. [Kubernetes Orchestration](#8-kubernetes-orchestration)
9. [Security](#9-security)
13. [DNS & Geo-Routing](#13-dns--geo-routing--how-clients-find-the-right-region)
14. [Client Reconnection Strategy](#14-client-reconnection-strategy)
15. [Push Notifications — Offline Users](#15-push-notifications--offline-users)
17. [Database Schema & Message Persistence](#17-database-schema--message-persistence)
18. [Compression & Payload Optimization](#18-compression--payload-optimization)
19. [End-to-End Encryption (E2EE)](#19-end-to-end-encryption-e2ee)
22. [Backup & Disaster Recovery](#22-backup--disaster-recovery)
23. [Data Compliance (GDPR / Privacy)](#23-data-compliance-gdpr--privacy)
24. [Protocol Versioning](#24-protocol-versioning)
25. [Typing Indicators & Presence Broadcasting](#25-typing-indicators--presence-broadcasting)

---

## 1. Current PoC Gaps — Code Level

### 1.1 No Message Persistence
- **Current**: Messages exist only in transit. If delivery fails, the message is gone forever.
- **Required**: Write every message to a persistent store (PostgreSQL / DynamoDB / Cassandra) BEFORE attempting delivery.
- **Flow should be**:
  1. Client sends message via Socket.IO
  2. Server writes message to DB with status `PENDING`
  3. Server attempts real-time delivery (socket / gRPC / NATS)
  4. On successful delivery, update status to `DELIVERED`
  5. On client read, update status to `READ`
- **Why**: Without this, any network blip, server crash, or recipient disconnect = permanent message loss.

### 1.2 No Offline Message Queue
- **Current**: If `redis.get("online:{userId}")` returns null, message is logged and dropped.
- **Required**: Push undelivered messages to an offline queue.
- **Options**:
  - Redis List: `RPUSH offline:{userId} {messageJSON}` — fast, volatile
  - Database table: `pending_messages(to_user, message, created_at)` — durable
  - NATS JetStream: publish to a durable consumer per user — best of both worlds
- **On reconnect**: Drain the offline queue and deliver all pending messages to the user.

### 1.3 Race Condition on Presence
- **Current**: Between `redis.get("online:{userId}")` and `io.to(socketId).emit()`, the user could disconnect.
- **Scenario**:
  1. Redis says user is on server R1.S1 with socketId `abc123`
  2. User disconnects from R1.S1 (socket closed, Redis key deleted)
  3. Message is emitted to `abc123` — goes nowhere, silently lost
- **Fix**:
  - Socket.IO `emit()` with acknowledgment callback — if no ack within 2s, fallback to offline queue
  - Or: Check `localSockets.has(userId)` before emitting and if missing, push to offline queue
  - Or: Use Socket.IO rooms per userId — `io.to("user:{userId}")` which is a no-op if room is empty (still need offline fallback)

### 1.4 No Delivery Acknowledgment
- **Current**: Sender fires a message and has no idea if it was delivered.
- **Required**: Three-tier acknowledgment:
  1. **Sent** ✓ — Server received the message (Socket.IO ack callback)
  2. **Delivered** ✓✓ — Recipient's device received the message
  3. **Read** ✓✓ (blue) — Recipient opened the conversation
- **Implementation**:
  - Socket.IO supports callback-based acks: `socket.emit("message", data, (ack) => { ... })`
  - Recipient sends back a `"message_delivered"` event with `messageId`
  - Route the delivery receipt back to sender using the same routing logic

### 1.5 No Retry / Dead-Letter Queue
- **Current**: If gRPC send fails or NATS publish fails, the error is logged and message is lost.
- **Required**:
  - **Retry with exponential backoff**: 1s → 2s → 4s → 8s → max 30s
  - **Max retries**: 5 attempts
  - **Dead-letter queue**: After max retries, push to a DLQ (NATS JetStream DLQ or SQS)
  - **DLQ processor**: Background worker that periodically retries DLQ messages or alerts ops

### 1.6 No Graceful Shutdown
- **Current**: If a server is killed (SIGTERM), Redis presence entries become stale, gRPC streams break uncleanly, NATS subscriptions hang.
- **Required**:
  ```
  On SIGTERM / SIGINT:
  1. Stop accepting new WebSocket connections
  2. Close NATS subscription (nc.drain())
  3. Close all gRPC pool connections (grpcPool.closeAll())
  4. For each connected user:
     a. Delete their Redis presence key
     b. Push any in-flight messages to offline queue
  5. Close HTTP server (httpServer.close())
  6. Close Redis connection (redis.quit())
  7. Exit process
  ```
- **K8s**: Set `terminationGracePeriodSeconds: 30` in the pod spec to give time for cleanup.

### 1.7 No User Disconnect Cleanup Race
- **Current**: `socket.on("disconnect")` deletes Redis key, but what if the user reconnects to a different server before the delete runs?
- **Scenario**:
  1. User disconnects from R1.S1
  2. User immediately reconnects to R1.S2 (Redis key updated to R1.S2)
  3. R1.S1's disconnect handler fires LATE and deletes the Redis key
  4. User appears offline despite being connected on R1.S2
- **Fix**: Use conditional delete — only delete if the stored `serverId` + `socketId` match this server's values:
  ```
  Redis Lua script:
  if redis.call("GET", key) matches current server's data then
    redis.call("DEL", key)
  end
  ```

### 1.8 No Message Ordering Guarantee
- **Current**: If two messages are sent rapidly, they could arrive out of order (especially cross-region via NATS).
- **Fix**:
  - Include a `sequence` number per conversation (monotonically increasing)
  - Client-side: buffer incoming messages and reorder by sequence before displaying
  - Server-side: Use NATS JetStream with ordered consumers for guaranteed ordering

### 1.9 No Duplicate Message Prevention
- **Current**: If gRPC stream reconnects and re-sends, or NATS redelivers, duplicate messages appear.
- **Fix**:
  - Every message has a unique `messageId` (UUID, already in proto)
  - Recipient tracks last N `messageId`s received (in-memory set or Redis set with TTL)
  - If duplicate `messageId` arrives, skip delivery
  - NATS JetStream has built-in dedup with `Nats-Msg-Id` header

### 1.10 No Input Validation
- **Current**: `socket.on("message", data)` — `data` is trusted blindly. No validation of `to`, `message`, `messageId` fields.
- **Required**:
  - Validate `data.to` exists and is a valid userId format
  - Validate `data.message` is a non-empty string, max length (e.g., 4096 chars)
  - Validate `data.messageId` is a valid UUID
  - Sanitize message content (prevent XSS if rendered in a web client)
  - Rate limit per user (e.g., max 30 messages/sec)

---

## 2. Message Reliability & Delivery Guarantees

### 2.1 At-Least-Once Delivery
- **Goal**: Every message is delivered at least once (duplicates are acceptable, missed messages are not).
- **Implementation**:
  1. Persist message to DB on receipt
  2. Attempt real-time delivery
  3. If delivery fails → retry from DB
  4. On recipient ack → mark as delivered
  5. Background job: scan for `PENDING` messages older than 30s → retry
- **Dedup on client**: Client tracks `messageId`s and ignores duplicates

### 2.2 Exactly-Once Delivery (harder)
- Only needed if you upgrade beyond chat (e.g., financial transactions)
- Requires idempotency keys + distributed transactions
- **Not recommended for v1** — at-least-once + client dedup is sufficient

### 2.3 Message TTL
- Messages older than X days could be archived/deleted
- Set TTL in the database (e.g., DynamoDB TTL, PostgreSQL `created_at + interval`)
- Offline queue entries should have a TTL (e.g., 7 days) — don't deliver 30-day-old messages on reconnect

---

## 3. Redis — Presence & State Management

### 3.1 Current Issues
- Single Redis instance — single point of failure
- No TTL on presence keys — server crash = permanent stale entries
- No Redis Cluster — can't scale beyond ~300K ops/sec
- Presence data stored as JSON string — wasteful, slower to parse

### 3.2 Production Configuration

#### TTL on Presence Keys
```
SET online:{userId} {data} EX 300
```
- 5-minute TTL, refreshed by client heartbeat every 60s
- If server crashes, keys auto-expire in 5 minutes max
- Client sends periodic `"heartbeat"` event → server does `EXPIRE online:{userId} 300`

#### Use Redis Hash Instead of JSON String
```
HSET online:{userId} serverId "r1.s1" region "r1" socketId "abc123"
EXPIRE online:{userId} 300
```
- Faster to read individual fields: `HGET online:{userId} region`
- No JSON parse overhead

#### AWS ElastiCache Global Datastore
- **Primary cluster**: in the "write" region (e.g., us-east-1)
- **Read replicas**: in every other region
- **Presence reads**: Always hit local replica (<1ms)
- **Presence writes**: Go to primary, replicate async (~100-200ms lag)
- **Acceptable**: User appears online with 200ms delay in other regions
- **Configuration**:
  - Node type: `cache.r7g.xlarge` (26GB RAM, 2 vCPU) × 3 shards
  - Multi-AZ enabled
  - Automatic failover enabled

#### Conditional Delete (Lua Script)
```lua
-- Prevent stale delete race condition
local data = redis.call('GET', KEYS[1])
if data then
  local parsed = cjson.decode(data)
  if parsed.serverId == ARGV[1] and parsed.socketId == ARGV[2] then
    redis.call('DEL', KEYS[1])
    return 1
  end
end
return 0
```

### 3.3 Redis Sentinel vs Cluster
| Feature | Sentinel | Cluster |
|---|---|---|
| Use case | HA for single master | Horizontal scaling |
| Sharding | No | Yes (16384 hash slots) |
| Throughput | ~300K ops/sec | ~300K × N shards |
| **Recommendation** | For <100K users | **For 100K+ users** |

---

## 4. NATS — Cross-Region Messaging

### 4.1 Current Issues
- Using basic NATS pub/sub — fire-and-forget, no persistence
- If target server is down when message is published → message lost
- No message ordering guarantee across regions
- No dead-letter handling

### 4.2 Upgrade to NATS JetStream
- **JetStream** = NATS with persistence, replay, and guaranteed delivery
- Each server creates a **durable consumer** for its subject (`r1.s1`)
- If server is down, messages accumulate in the stream and are delivered on reconnect
- Built-in dedup via `Nats-Msg-Id` header

#### Stream Configuration
```
Stream: CROSS_REGION_MESSAGES
  Subjects: r1.*, r2.*
  Storage: File (persistent)
  Retention: WorkQueue (delete after ack)
  MaxAge: 7 days
  MaxMsgs: 10,000,000
  Replicas: 3
```

#### Consumer per Server
```
Consumer: r1-s1-consumer
  Durable: "r1-s1"
  FilterSubject: "r1.r1.s1"
  AckPolicy: Explicit
  MaxDeliver: 5 (retry 5 times)
  AckWait: 10s
  DeliverPolicy: All (replay missed messages on restart)
```

### 4.3 NATS Cloud (Synadia NGS)
- Managed global NATS super-cluster
- Regions auto-connected via leaf nodes
- JetStream enabled globally
- Built-in monitoring dashboard
- **Pricing**: Based on message volume + connections
- **No infra management** — just connect and publish

### 4.4 NATS Tuning
- `PendingMsgLimit`: 1,000,000 (per subscription buffer)
- `MaxReconnects`: -1 (infinite reconnects)
- `ReconnectWait`: 2s with jitter
- `ReconnectBufSize`: 32MB (buffer messages during reconnect)
- Enable TLS for cross-region traffic
- Use `nats.HeaderMsgID` for dedup

---

## 5. gRPC — Intra-Region Communication

### 5.1 Current Issues
- Basic bidi stream with no error handling beyond reconnect
- No keepalive configuration
- No connection health checks
- No flow control / backpressure
- AsyncQueue can grow unbounded in memory

### 5.2 Production gRPC Configuration

#### Keepalive Settings
```go
grpc.KeepaliveParams(keepalive.ServerParameters{
    MaxConnectionIdle:     5 * time.Minute,   // Close idle connections
    MaxConnectionAge:      30 * time.Minute,  // Force reconnect periodically
    MaxConnectionAgeGrace: 10 * time.Second,  // Grace period for in-flight RPCs
    Time:                  10 * time.Second,  // Ping interval
    Timeout:               3 * time.Second,   // Ping timeout
})
```

#### Client-Side Keepalive
```go
grpc.WithKeepaliveParams(keepalive.ClientParameters{
    Time:                10 * time.Second,
    Timeout:             3 * time.Second,
    PermitWithoutStream: true,  // Keepalive even when no active streams
})
```

#### Connection Pool Improvements
- **Bounded queue**: Max 10,000 pending messages per connection. If full, push to NATS as fallback.
- **Health check**: Periodic ping on the bidi stream. If no pong in 5s, close and reconnect.
- **Exponential backoff on reconnect**: 100ms → 200ms → 400ms → ... → max 30s
- **Circuit breaker**: If a peer fails 5 times in 60s, stop trying for 30s (half-open → try one → full open)

#### gRPC Load Balancing (100 Nodes per Region)
- **Don't mesh fully**: 100 nodes = 9,900 potential connections
- **Use consistent hashing**: Hash `userId` to determine which server handles them
  - Only connect to the specific server that owns the recipient
  - Connection pool max size = ~10-20 active connections (not 99)
- **Alternative**: Use K8s headless service + gRPC client-side load balancing
  ```go
  grpc.Dial("dns:///chat-grpc.default.svc.cluster.local:4001",
      grpc.WithDefaultServiceConfig(`{"loadBalancingPolicy":"round_robin"}`),
  )
  ```

### 5.3 Proto Improvements
```protobuf
syntax = "proto3";

package InternalCommunicationPackage;

service InternalCommunicationService {
    // Bidi stream for real-time message forwarding
    rpc ForwardMessages (stream ForwardedMessage) returns (stream DeliveryAck);
    
    // Unary RPC for health checks
    rpc HealthCheck (HealthRequest) returns (HealthResponse);
}

message ForwardedMessage {
    string message_id = 1;
    string to_user_id = 2;
    string from_user_id = 3;
    string message_content = 4;
    int64 timestamp = 5;
    int64 sequence = 6;  // For ordering
}

message DeliveryAck {
    string message_id = 1;
    enum Status {
        DELIVERED = 0;
        USER_NOT_FOUND = 1;
        FAILED = 2;
    }
    Status status = 2;
}

message HealthRequest {}
message HealthResponse {
    string server_id = 1;
    int32 active_connections = 2;
    int64 uptime_seconds = 3;
}
```

---

## 6. Go Migration — What Changes

### 6.1 Why Go Over Node.js/Bun
| Aspect | Bun/Node.js | Go |
|---|---|---|
| Concurrency model | Single-threaded event loop | Goroutines (M:N scheduler) |
| Connections per process | ~10-30K (practical) | ~500K-1M |
| Memory per connection | ~50-100KB | ~8-20KB (goroutine stack) |
| CPU utilization | Single core (cluster mode for multi) | All cores natively |
| gRPC support | Connect-RPC (HTTP based) | Native gRPC (HTTP/2, optimized) |
| WebSocket libraries | Socket.IO (heavy) | gorilla/websocket, nhooyr/websocket (lightweight) |
| Binary size | N/A (needs runtime) | Single static binary |

### 6.2 Key Go Libraries
- **WebSocket**: `github.com/coder/websocket` (successor to nhooyr) or `github.com/gorilla/websocket`
- **gRPC**: `google.golang.org/grpc` (native, first-class)
- **NATS**: `github.com/nats-io/nats.go`
- **Redis**: `github.com/redis/go-redis/v9`
- **Proto**: `google.golang.org/protobuf`
- **Logging**: `go.uber.org/zap` (structured, zero-alloc)
- **Metrics**: `github.com/prometheus/client_golang`
- **Config**: `github.com/spf13/viper` or env vars

### 6.3 Go Server Structure
```
/cmd
  /server           # main.go — entry point
/internal
  /config           # server configuration, env parsing
  /handler          # WebSocket message handlers
  /presence         # Redis presence manager
  /routing          # Message routing logic (local/gRPC/NATS)
  /grpcpool         # Lazy gRPC connection pool
  /grpcserver       # gRPC service implementation
  /natsbridge       # NATS publisher + subscriber
  /middleware        # Auth, rate limiting, logging
/proto
  /internal_comm    # .proto files + generated Go code
/deployments
  /k8s              # Helm charts / K8s manifests
  /docker           # Dockerfiles
/scripts
  /tuning           # sysctl.conf, limits.conf
go.mod
go.sum
```

### 6.4 Go-Specific Optimizations
- **Goroutine pool**: Don't spawn unbounded goroutines per message. Use a worker pool (e.g., `github.com/panjf2000/ants`) with max 10K workers.
- **Sync.Pool**: Reuse message buffers and JSON encoder/decoder objects to reduce GC pressure.
- **Zero-copy**: Use `io.Reader` interfaces instead of `[]byte` copies where possible.
- **GOGC tuning**: Set `GOGC=200` or higher to reduce GC frequency at cost of more memory.
- **GOMEMLIMIT**: Set to 80% of container memory to prevent OOM kills.
- **pprof**: Always enable `net/http/pprof` on a separate debug port (6060) for production profiling.

### 6.5 WebSocket vs Socket.IO in Go
- **Drop Socket.IO**: It's a Node.js-centric protocol with overhead (handshake, fallback transports, namespace encoding).
- **Use raw WebSocket**: In Go, use a plain WebSocket with a simple JSON/Protobuf message protocol.
- **Custom protocol over WebSocket**:
  ```json
  // Client → Server
  {"type": "message", "to": "user123", "message": "hello", "messageId": "uuid"}
  
  // Server → Client
  {"type": "friend_message", "from": "user456", "message": "hello", "messageId": "uuid"}
  {"type": "ack", "messageId": "uuid", "status": "delivered"}
  {"type": "error", "code": "USER_OFFLINE", "messageId": "uuid"}
  ```
- **Or use Protobuf over WebSocket**: Binary frames, smaller payloads, faster parsing.

---

## 7. OS & Kernel Tuning (Linux)

### 7.1 File Descriptor Limits

#### `/etc/security/limits.conf`
```
# Per-user limits (for the app user)
chatapp    soft    nofile    10000000
chatapp    hard    nofile    10000000
chatapp    soft    nproc     unlimited
chatapp    hard    nproc     unlimited
```

#### `/etc/sysctl.conf`
```
# System-wide FD limit
fs.file-max = 10000000
fs.nr_open = 10000000
```

### 7.2 Network Stack Tuning

#### `/etc/sysctl.conf` (continued)
```
# ── TCP Connection Handling ──────────────────────
# Accept queue size (prevent dropped connections under burst)
net.core.somaxconn = 65535

# SYN queue size
net.ipv4.tcp_max_syn_backlog = 65535

# Packet backlog before kernel drops
net.core.netdev_max_backlog = 50000

# ── Port Range ───────────────────────────────────
# More ephemeral ports for outbound connections (gRPC, NATS, Redis)
net.ipv4.ip_local_port_range = 1024 65535

# ── TCP Reuse & Recycling ────────────────────────
# Reuse TIME_WAIT sockets
net.ipv4.tcp_tw_reuse = 1

# Faster FIN timeout (free dead sockets)
net.ipv4.tcp_fin_timeout = 15

# ── TCP Buffers ──────────────────────────────────
# Socket receive buffer (min, default, max)
net.core.rmem_default = 262144
net.core.rmem_max = 16777216
net.ipv4.tcp_rmem = 4096 262144 16777216

# Socket write buffer (min, default, max)
net.core.wmem_default = 262144
net.core.wmem_max = 16777216
net.ipv4.tcp_wmem = 4096 262144 16777216

# ── TCP Keepalive ────────────────────────────────
# Detect dead connections faster
net.ipv4.tcp_keepalive_time = 300
net.ipv4.tcp_keepalive_intvl = 30
net.ipv4.tcp_keepalive_probes = 5

# ── Congestion Control ──────────────────────────
# BBR gives better throughput on lossy networks (cross-region)
net.core.default_qdisc = fq
net.ipv4.tcp_congestion_control = bbr

# ── Connection Tracking ─────────────────────────
# Increase conntrack table for massive connection counts
net.netfilter.nf_conntrack_max = 2000000
net.netfilter.nf_conntrack_tcp_timeout_established = 600
net.netfilter.nf_conntrack_tcp_timeout_time_wait = 30
```

### 7.3 epoll Specifics
- Go's `netpoller` uses **epoll** on Linux by default — no configuration needed.
- Go automatically uses edge-triggered epoll (`EPOLLET`) for non-blocking I/O.
- Each goroutine blocking on network I/O is parked (uses 0 CPU) until epoll wakes it.
- **No need for manual epoll tuning** — Go handles this optimally.

### 7.4 Memory Tuning
```
# Disable swap (prevent latency spikes from swapping)
vm.swappiness = 1

# Allow overcommit (Go manages its own memory)
vm.overcommit_memory = 1

# Transparent Huge Pages — disable for Go (causes latency spikes)
echo never > /sys/kernel/mm/transparent_hugepage/enabled
```

### 7.5 io_uring (Future Optimization)
- Available on Linux 5.1+
- ~30% throughput improvement over epoll for high-connection-count servers
- Go does NOT use io_uring natively yet (as of Go 1.23)
- Can use via `github.com/iceber/iouring-go` but experimental
- **Recommendation**: Stick with epoll for now, adopt io_uring when Go runtime supports it natively

### 7.6 Docker / Container Considerations
```dockerfile
# In Dockerfile — must set ulimits at container level too
# docker run --ulimit nofile=10000000:10000000

# K8s pod spec:
# securityContext:
#   sysctls:
#     - name: net.core.somaxconn
#       value: "65535"
#     - name: net.ipv4.ip_local_port_range
#       value: "1024 65535"
```

---

## 8. Kubernetes Orchestration

### 8.1 Architecture in K8s

```
Namespace: chat-system

Per Region (e.g., us-east-1):
├── StatefulSet: chat-server (100 replicas)
│   ├── Container: chat-app (Go binary)
│   ├── Ports: 3000 (WebSocket), 4000 (gRPC), 6060 (pprof)
│   └── Resources: 4 CPU, 16GB RAM per pod
├── Service: chat-ws (LoadBalancer, sticky sessions)
│   └── Exposes port 3000 for WebSocket clients
├── Service: chat-grpc (ClusterIP, headless)
│   └── Exposes port 4000 for intra-region gRPC
├── ElastiCache: Redis Cluster (3 shards, 2 replicas each)
└── NATS: Managed (Synadia NGS) or StatefulSet (3-node JetStream cluster)
```

### 8.2 StatefulSet vs Deployment
- **Use StatefulSet** (not Deployment):
  - Each pod gets a stable identity: `chat-server-0`, `chat-server-1`, etc.
  - Stable network identity = stable gRPC addressing
  - Ordered rolling updates (drain connections from one pod at a time)
  - Pod DNS: `chat-server-0.chat-grpc.chat-system.svc.cluster.local`

### 8.3 WebSocket Sticky Sessions
- WebSocket connections are long-lived — can't round-robin
- **Option A**: AWS ALB with sticky sessions (cookie-based)
- **Option B**: Nginx Ingress with `nginx.ingress.kubernetes.io/affinity: "cookie"`
- **Option C**: Use client-side connection to a specific pod via subdomain routing

### 8.4 Service Configuration

#### Headless gRPC Service (for intra-region)
```yaml
apiVersion: v1
kind: Service
metadata:
  name: chat-grpc
spec:
  clusterIP: None  # Headless — returns all pod IPs
  selector:
    app: chat-server
  ports:
    - name: grpc
      port: 4000
      targetPort: 4000
```

#### WebSocket Service (external)
```yaml
apiVersion: v1
kind: Service
metadata:
  name: chat-ws
  annotations:
    service.beta.kubernetes.io/aws-load-balancer-type: "nlb"
    service.beta.kubernetes.io/aws-load-balancer-cross-zone-load-balancing-enabled: "true"
spec:
  type: LoadBalancer
  selector:
    app: chat-server
  ports:
    - name: ws
      port: 443
      targetPort: 3000
  sessionAffinity: ClientIP
  sessionAffinityConfig:
    clientIP:
      timeoutSeconds: 3600
```

### 8.5 Health Probes
```yaml
livenessProbe:
  httpGet:
    path: /healthz
    port: 3000
  initialDelaySeconds: 5
  periodSeconds: 10
  failureThreshold: 3

readinessProbe:
  httpGet:
    path: /readyz
    port: 3000
  initialDelaySeconds: 5
  periodSeconds: 5
  failureThreshold: 2

# /healthz = "is the process alive?" (check goroutine count, memory)
# /readyz = "can it accept connections?" (check Redis, NATS, gRPC connectivity)
```

### 8.6 Pod Disruption Budget
```yaml
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: chat-server-pdb
spec:
  minAvailable: 90%   # At most 10 pods can be down during rolling update
  selector:
    matchLabels:
      app: chat-server
```

### 8.7 Horizontal Pod Autoscaler
```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: chat-server-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: StatefulSet
    name: chat-server
  minReplicas: 10
  maxReplicas: 200
  metrics:
    - type: Pods
      pods:
        metric:
          name: websocket_active_connections
        target:
          type: AverageValue
          averageValue: "50000"  # Scale when avg connections per pod > 50K
```

### 8.8 Pre-Stop Hook (Graceful Shutdown)
```yaml
lifecycle:
  preStop:
    exec:
      command:
        - /bin/sh
        - -c
        - |
          # Signal the app to start draining
          kill -SIGTERM 1
          # Wait for connections to drain (max 25s, leave 5s for K8s grace period)
          sleep 25
terminationGracePeriodSeconds: 30
```

### 8.9 Node Affinity & Topology
```yaml
# Spread pods across AZs for high availability
topologySpreadConstraints:
  - maxSkew: 1
    topologyKey: topology.kubernetes.io/zone
    whenUnsatisfiable: DoNotSchedule
    labelSelector:
      matchLabels:
        app: chat-server
```

---

## 9. Security

### 9.1 Authentication
- **Current**: `socket.handshake.query["user_id"]` — anyone can impersonate any user.
- **Required**: JWT-based authentication
  ```
  Client connects with: ws://server:3000?token=eyJhbG...
  Server:
    1. Extract JWT from query param or Authorization header
    2. Verify signature (RS256 with public key)
    3. Extract userId from JWT claims
    4. Reject expired tokens (check `exp` claim)
    5. Store userId from verified token — never trust client-supplied userId
  ```

### 9.2 Authorization
- Verify sender has permission to message recipient
- Check block lists, friend lists, privacy settings
- Rate limit per user: max 30 messages/sec, max 100 connections/sec

### 9.3 Transport Security
- **WebSocket**: WSS (TLS) — never plain WS in production
- **gRPC**: mTLS between servers (mutual TLS — both sides verify certificates)
- **NATS**: TLS + NKey authentication
- **Redis**: TLS + AUTH password + VPC-only access (no public endpoint)

### 9.4 Rate Limiting
```
Per user:
  - Message send: 30/sec, 500/min
  - Connection attempts: 5/min
  - Heartbeat: 1/min (expected), alert if > 5/min

Per IP:
  - Connection attempts: 100/min
  - Total WebSocket connections: 10 per IP (prevent connection exhaustion)
```

### 9.5 Input Sanitization
- Max message length: 4096 characters
- Strip HTML tags if rendered in web client
- Validate messageId is UUID format
- Validate userId format (alphanumeric, max 64 chars)
- Reject malformed JSON payloads

---

## 10. Observability & Monitoring

### 10.1 Metrics (Prometheus)

#### Custom Metrics to Export
```
# Connections
websocket_active_connections{server_id, region}          gauge
websocket_connections_total{server_id, region}            counter
websocket_disconnections_total{server_id, region, reason} counter

# Messages
messages_sent_total{server_id, transport}                 counter  # transport: local/grpc/nats
messages_delivered_total{server_id, transport}             counter
messages_failed_total{server_id, transport, reason}        counter
message_delivery_latency_seconds{transport}               histogram

# gRPC Pool
grpc_pool_active_connections{server_id, target_server}    gauge
grpc_pool_reconnections_total{server_id, target_server}   counter
grpc_pool_queue_depth{server_id, target_server}           gauge

# Redis
redis_presence_ops_total{server_id, operation}            counter  # operation: get/set/del
redis_presence_latency_seconds{operation}                  histogram

# NATS
nats_messages_published_total{server_id, subject}         counter
nats_messages_received_total{server_id, subject}          counter
nats_publish_latency_seconds{}                            histogram

# System
go_goroutines{server_id}                                  gauge
process_open_fds{server_id}                               gauge
process_resident_memory_bytes{server_id}                  gauge
```

### 10.2 Logging (Structured)
- Use `zap` (Go) for structured JSON logging
- Log levels: DEBUG, INFO, WARN, ERROR
- **Log on**:
  - User connect/disconnect (INFO)
  - Message routing decision (DEBUG)
  - Delivery failure (WARN)
  - gRPC pool reconnect (WARN)
  - NATS reconnect (WARN)
  - Panic recovery (ERROR)
- **Don't log**: Message content (privacy), every heartbeat (volume)
- Ship logs to: CloudWatch / Loki / ELK

### 10.3 Distributed Tracing (OpenTelemetry)
- Trace a message from send → routing → transport → delivery
- Trace ID propagated through: Socket.IO → Redis → gRPC/NATS → delivery
- Use `go.opentelemetry.io/otel` SDK
- Export to: Jaeger / Tempo / AWS X-Ray

### 10.4 Alerting Rules
```yaml
# Alert if message delivery latency > 5s (p99)
- alert: HighMessageLatency
  expr: histogram_quantile(0.99, message_delivery_latency_seconds) > 5
  for: 5m

# Alert if connections dropping fast
- alert: MassDisconnection
  expr: rate(websocket_disconnections_total[1m]) > 1000
  for: 1m

# Alert if gRPC pool has >5000 queued messages (backpressure)
- alert: GrpcPoolBackpressure
  expr: grpc_pool_queue_depth > 5000
  for: 2m

# Alert if Redis is unreachable
- alert: RedisDown
  expr: up{job="redis"} == 0
  for: 30s

# Alert if any server has 0 connections (might be broken)
- alert: ServerNoConnections
  expr: websocket_active_connections == 0
  for: 5m
```

### 10.5 Dashboard (Grafana)
- **Overview**: Total connections, messages/sec, regional split
- **Per-server**: Connection count, message rate, gRPC pool status, memory, goroutines
- **Message flow**: Sankey diagram of local vs gRPC vs NATS routing
- **Latency**: p50, p95, p99 delivery latency by transport type
- **Errors**: Failed deliveries, DLQ depth, reconnection rate

---

## 11. Testing Strategy

### 11.1 Unit Tests
- Message routing logic: given presence data, assert correct transport is chosen
- AsyncQueue / GrpcPool: push/pop, close, reconnect behavior
- Redis presence: set, get, conditional delete, TTL expiry
- Input validation: malformed data, oversized messages, invalid userIds

### 11.2 Integration Tests
- Two servers in same region: send message between users on different servers → verify gRPC delivery
- Two servers in different regions: send message → verify NATS delivery
- User goes offline: send message → verify offline queue → user reconnects → verify delivery
- Server restart: user was on server → server restarts → verify presence cleanup + reconnect

### 11.3 Load Testing
- **Tool**: `k6` or `artillery` for WebSocket load testing
- **Scenarios**:
  - 10K concurrent connections per server, 100 msgs/sec each
  - Burst: 0 → 50K connections in 30 seconds
  - Cross-region: 10K users in R1 messaging 10K users in R2 simultaneously
- **Measure**: p99 latency, message loss rate, memory growth, CPU usage

### 11.4 Chaos Testing
- **Kill a server mid-traffic**: Verify messages reroute, presence cleans up, clients reconnect
- **Kill Redis**: Verify graceful degradation (server logs errors, doesn't crash)
- **Kill NATS**: Verify cross-region messages buffer and retry
- **Network partition**: Split regions, verify intra-region still works, cross-region buffers
- **Tools**: `chaos-mesh` (K8s native), `litmus` (K8s), `tc` (Linux traffic control for latency injection)

### 11.5 Soak Testing
- Run at 50% capacity for 72 hours
- Watch for: memory leaks, goroutine leaks, FD leaks, Redis connection pool exhaustion
- Monitor GC pause times (should stay <5ms)

---

## 12. Cost Estimation

### 12.1 Per Region (100 Nodes)

| Resource | Spec | Monthly Cost (AWS us-east-1) |
|---|---|---|
| Chat servers | 100 × c5.4xlarge (16 vCPU, 32GB) | ~$49,000 |
| Redis Cluster | 3 × cache.r7g.xlarge (26GB) | ~$2,400 |
| NATS Cloud | Synadia NGS Professional | ~$500-2,000 |
| ALB/NLB | Network Load Balancer | ~$500 |
| Data transfer | ~50TB/month inter-AZ | ~$500 |
| **Subtotal per region** | | **~$53,000/month** |

### 12.2 Total (2 Regions)

| Item | Cost |
|---|---|
| 2 regions × $53K | ~$106,000/month |
| Cross-region data transfer (~10TB) | ~$900/month |
| Redis Global Datastore replication | ~$500/month |
| Monitoring (CloudWatch, Grafana Cloud) | ~$1,000/month |
| **Total** | **~$108,000/month** |

### 12.3 Cost Optimization
- **Spot instances** for non-critical servers: 60-70% savings (~$30K/month saved)
- **Reserved instances** (1-year): ~40% savings
- **Right-sizing**: Start with 10 nodes, scale to 100 based on actual load
- **ARM instances** (c7g): 20% cheaper, Go compiles natively for ARM
- **Realistic start**: 10 nodes/region = ~$11K/month total

---

## 13. DNS & Geo-Routing — How Clients Find the Right Region

### 13.1 Current Gap
- No mechanism for clients to discover which region/server to connect to.
- Hardcoded server addresses won't work in production.

### 13.2 Production Setup

#### AWS Route 53 Latency-Based Routing
```
chat-ws.yourapp.com
├── us-east-1 → NLB (R1 servers)    [latency-based]
├── eu-west-1 → NLB (R2 servers)    [latency-based]
└── Health checks on each NLB
```
- Client connects to `wss://chat-ws.yourapp.com` — Route 53 resolves to the closest region automatically.
- If a region goes down, health check fails → Route 53 reroutes to surviving region.

#### Connection Endpoint API
- Before opening WebSocket, client calls:
  ```
  GET https://api.yourapp.com/chat/connect
  Response: { "wsUrl": "wss://r1-chat.yourapp.com", "region": "r1" }
  ```
- This lets you do intelligent routing (e.g., route VIP users to dedicated servers, route by shard).

#### CloudFront / Global Accelerator
- **AWS Global Accelerator**: Anycast IP → routes TCP to nearest region via AWS backbone (lower latency than public internet).
- Better than Route 53 alone because it uses AWS's private network, not public DNS resolution.

---

## 14. Client Reconnection Strategy

### 14.1 Current Gap
- No client reconnection logic. If WebSocket drops, the client has no guidance on how to reconnect.

### 14.2 Client-Side Reconnection Protocol
```
On disconnect:
1. Wait 1s (initial backoff)
2. Attempt reconnect to same server
3. If fail → wait 2s → retry
4. If fail → wait 4s → retry
5. Continue doubling: 8s, 16s, max 30s
6. Add jitter: ±20% randomization to prevent thundering herd
7. After 10 consecutive failures → re-resolve DNS (server may have moved)
8. After 30 consecutive failures → show "connection lost" UI, stop retrying
```

### 14.3 Thundering Herd Prevention
- If a server crashes with 500K connections, all 500K clients reconnect simultaneously.
- **Fix**: Random jitter on reconnect delay (e.g., `baseDelay + random(0, baseDelay * 0.5)`)
- **Server-side**: Use HPA to auto-scale when connection spike detected.
- **Rate limit**: Connection accept rate limiter on each server (e.g., max 10K new connections/sec).

### 14.4 Session Resumption
- On reconnect, client sends `lastMessageId` it received.
- Server replays all messages after `lastMessageId` from offline queue / database.
- Prevents message gaps during brief disconnects.

---

## 15. Push Notifications — Offline Users

### 15.1 Current Gap
- If user is offline, message is either dropped or queued — but user has no idea they have pending messages.

### 15.2 Production Push Flow
```
Message arrives → recipient offline?
├── Yes → Store in offline queue
│        → Send push notification via FCM (Android) / APNs (iOS)
│        → Push payload: { title: "New message from {sender}", body: "..." }
└── No  → Deliver via WebSocket
```

### 15.3 Implementation
- Store device tokens in database: `user_devices(userId, platform, pushToken, lastActive)`
- Use a **push notification service** (AWS SNS, Firebase Cloud Messaging, or self-hosted)
- **Batching**: If user has 50 pending messages, don't send 50 push notifications — send 1 with "50 new messages"
- **Quiet hours**: Respect user's notification preferences (mute, DND)
- **Token refresh**: Device tokens expire — handle `InvalidRegistration` errors by removing stale tokens

### 15.4 Push Notification Rate Limits
- Max 1 push per conversation per 30 seconds (avoid spamming)
- Collapse key: group notifications by sender (`collapseKey: "chat_{senderId}"`)

---

## 16. Multi-Device Support

### 16.1 Current Gap
- Current design: 1 user = 1 connection = 1 server. If user logs in on another device, the first session is overwritten in Redis.

### 16.2 Production Multi-Device Architecture
```
Redis presence (per user):
  online:{userId} → [
    { serverId: "r1.s1", socketId: "abc", deviceId: "phone_123" },
    { serverId: "r1.s2", socketId: "def", deviceId: "laptop_456" },
    { serverId: "r2.s1", socketId: "ghi", deviceId: "tablet_789" }
  ]
```

### 16.3 Implementation Changes
- **Redis structure**: Change from single value to Redis Set or sorted set per user.
  ```
  SADD online:{userId} '{"serverId":"r1.s1","socketId":"abc","deviceId":"phone_123"}'
  ```
- **Message delivery**: Deliver to ALL active devices, not just one.
  - Fan out: For each entry in the set → route via local/gRPC/NATS.
- **Read sync**: When user reads a message on one device, send a `"read_sync"` event to all other devices.
- **Disconnect**: Remove only the specific device entry, not the entire key.
  ```
  SREM online:{userId} '{"serverId":"r1.s1","socketId":"abc","deviceId":"phone_123"}'
  ```
- **Max devices**: Limit to 5 concurrent sessions per user. Kick oldest session on 6th login.

---

## 17. Database Schema & Message Persistence

### 17.1 Current Gap
- No database. Messages exist only in transit.

### 17.2 Database Choice

| Database | Pros | Cons | Best For |
|---|---|---|---|
| **PostgreSQL** | ACID, rich queries, proven | Vertical scaling limits | <10M users |
| **ScyllaDB / Cassandra** | Horizontal scaling, write-optimized | Eventual consistency, limited queries | 10M-1B users |
| **DynamoDB** | Managed, auto-scaling, global tables | Expensive at scale, limited query patterns | AWS-native, <100M users |

**Recommendation**: DynamoDB for AWS-native stack, ScyllaDB if self-managed.

### 17.3 DynamoDB Schema
```
Table: messages
  Partition Key: conversation_id (string)  # sorted pair: "user1#user2"
  Sort Key:      message_id (string)       # ULID (time-sortable UUID)

  Attributes:
    from_user_id    (string)
    to_user_id      (string)
    content         (string, encrypted)
    status          (string: PENDING | DELIVERED | READ)
    created_at      (number, epoch ms)
    ttl             (number, epoch seconds — for auto-deletion)

  GSI: by_recipient
    Partition Key: to_user_id
    Sort Key:      created_at
    # For querying "all undelivered messages for user X"

Table: conversations
  Partition Key: user_id (string)
  Sort Key:      conversation_id (string)

  Attributes:
    last_message_preview  (string)
    last_message_at       (number)
    unread_count          (number)
```

### 17.4 Conversation ID Generation
```go
// Always sort user IDs to create a deterministic conversation ID
func conversationID(userA, userB string) string {
    if userA < userB {
        return userA + "#" + userB
    }
    return userB + "#" + userA
}
```

### 17.5 Message ID: Use ULID, Not UUID
- **ULID** (Universally Unique Lexicographically Sortable Identifier)
- Time-sortable: messages naturally sort by creation time
- 128-bit, compatible with UUID storage
- Library: `github.com/oklog/ulid/v2` (Go)

---

## 18. Compression & Payload Optimization

### 18.1 Current Gap
- All messages are plain JSON over WebSocket — no compression.
- At 500K msgs/sec with 1KB messages, that's ~4Gbps — near NIC limit.

### 18.2 WebSocket Per-Message Deflate
- Enable `permessage-deflate` WebSocket extension.
- Compresses each message individually: ~60-80% size reduction for text.
- Go: `websocket.AcceptOptions{ CompressionMode: websocket.CompressionContextTakeover }`
- **Trade-off**: CPU cost for compression. Use only if network-bound, not CPU-bound.

### 18.3 Protobuf Over WebSocket
- Instead of JSON, use Protobuf binary frames.
- ~30-50% smaller than JSON for structured data.
- Faster serialization/deserialization (no string parsing).
- Client needs Protobuf library (available for JS, Swift, Kotlin).

### 18.4 gRPC Compression
```go
// Enable gzip compression on gRPC connections
grpc.UseCompressor(gzip.Name)
```
- Reduces intra-region bandwidth between servers.
- ~60% size reduction, small CPU overhead.

### 18.5 NATS Payload Compression
- Compress NATS message payload before publish:
  ```go
  compressed := snappy.Encode(nil, payload)  // Snappy: fast, decent ratio
  nc.Publish(subject, compressed)
  ```
- Use Snappy (fast) for intra-region, Zstd (better ratio) for cross-region.

---

## 19. End-to-End Encryption (E2EE)

### 19.1 Why
- Even with TLS, server can read message content.
- E2EE ensures only sender and recipient can decrypt messages.
- Required for privacy-sensitive applications.

### 19.2 Signal Protocol (Industry Standard)
- Used by Signal, WhatsApp, Facebook Messenger.
- Provides: forward secrecy, deniability, break-in recovery.
- Libraries: `libsignal-protocol-javascript` (client), key exchange only on server.

### 19.3 Implementation Overview
```
1. Each user generates identity key pair on device registration
2. User uploads public key + signed pre-keys to server
3. When Alice wants to message Bob:
   a. Alice fetches Bob's public key bundle from server
   b. Alice creates a shared secret via X3DH key agreement
   c. Alice encrypts message with Double Ratchet algorithm
   d. Server receives encrypted blob — cannot decrypt
   e. Bob receives and decrypts with his private key
4. Server only stores/forwards encrypted ciphertext
```

### 19.4 Impact on Architecture
- Server-side message content becomes opaque bytes — no content-based features (search, moderation).
- Push notification preview: client provides a separate, user-controlled preview (or just "New message").
- **Recommendation**: E2EE is a v2 feature. Get the transport layer right first.

---

## 20. CI/CD Pipeline

### 20.1 Current Gap
- No build, test, or deployment automation.

### 20.2 Pipeline Stages
```
Push to main
├── 1. Lint (golangci-lint)
├── 2. Unit Tests (go test ./...)
├── 3. Build Docker Image (multi-stage, distroless base)
├── 4. Integration Tests (docker-compose with Redis + NATS + 2 servers)
├── 5. Push Image to ECR
├── 6. Deploy to Staging (K8s, 2 replicas per region)
├── 7. Smoke Tests (connect WS, send message, verify delivery)
├── 8. Manual Approval Gate
└── 9. Deploy to Production (rolling update, 10% canary → 50% → 100%)
```

### 20.3 Dockerfile (Multi-Stage)
```dockerfile
# Build stage
FROM golang:1.23-alpine AS builder
WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -ldflags="-s -w" -o /chat-server ./cmd/server

# Runtime stage (distroless — no shell, minimal attack surface)
FROM gcr.io/distroless/static-debian12
COPY --from=builder /chat-server /chat-server
EXPOSE 3000 4000 6060
ENTRYPOINT ["/chat-server"]
```

### 20.4 Tools
- **CI**: GitHub Actions / GitLab CI / Jenkins
- **Container Registry**: AWS ECR
- **K8s Deployment**: ArgoCD (GitOps) or Helm + `kubectl`
- **Secret Management**: AWS Secrets Manager → K8s External Secrets Operator

---

## 21. Deployment Strategy

### 21.1 Rolling Update (Default)
- K8s updates pods one-by-one: drain old pod → start new pod.
- With `PodDisruptionBudget: minAvailable: 90%`, max 10 pods update at a time.
- **Risk**: If new version has a bug, it propagates slowly but still reaches all pods.

### 21.2 Canary Deployment (Recommended)
```
1. Deploy new version to 1 pod (1% of traffic)
2. Monitor for 10 minutes: error rate, latency, crash loops
3. If healthy → scale to 10 pods (10%)
4. Monitor for 10 minutes
5. If healthy → full rollout (100 pods)
6. If unhealthy at any stage → auto-rollback to previous version
```
- **Tool**: Argo Rollouts (`AnalysisRun` with Prometheus queries for automated canary).

### 21.3 Blue/Green (For Major Version Changes)
- Run two full environments: blue (current) + green (new).
- Switch traffic at load balancer level.
- Instant rollback by switching back.
- **Cost**: 2× infrastructure during deployment window.

### 21.4 WebSocket Connection Draining
- WebSocket connections are long-lived — can't just kill a pod.
- **Drain strategy**:
  1. Mark pod as "draining" (stop accepting new connections via readiness probe failing).
  2. Wait for existing connections to naturally disconnect (client reconnect cycle).
  3. After `terminationGracePeriodSeconds` (e.g., 300s / 5min), force close remaining connections.
  4. Clients reconnect to new pods automatically.
- **Set `terminationGracePeriodSeconds: 300`** for WebSocket servers (not the default 30s).

---

## 22. Backup & Disaster Recovery

### 22.1 What to Back Up
| Data | Backup Strategy | RPO | RTO |
|---|---|---|---|
| Message database | DynamoDB point-in-time recovery (continuous) | 0 (continuous) | <1 hour |
| Redis presence | No backup needed — ephemeral, rebuilds on reconnect | N/A | N/A |
| NATS JetStream | Cross-region replication (built-in) | ~seconds | ~minutes |
| User accounts | Database backup (separate auth service) | <1 hour | <1 hour |
| Configuration | Git (Infrastructure as Code) | 0 | <30 min |

### 22.2 Disaster Recovery Scenarios

#### Single Server Failure
- K8s auto-restarts the pod.
- Users reconnect to other pods.
- Redis presence auto-expires in 5 minutes.
- **Recovery**: Automatic, <30 seconds.

#### Full Region Failure
- Route 53 / Global Accelerator detects health check failure.
- DNS reroutes all traffic to surviving region.
- Cross-region Redis read replica promotes to primary.
- NATS JetStream replays queued messages.
- **Recovery**: 1-3 minutes (DNS TTL).

#### Redis Failure
- ElastiCache Multi-AZ: automatic failover to replica (<30 seconds).
- During failover: messages delivered to locally cached sockets (no presence lookup needed for local delivery).
- New connections during failover: fail gracefully with "try again" error.

#### NATS Failure
- JetStream cluster: survives 1 node failure (3-node cluster, quorum = 2).
- Full NATS outage: cross-region messages buffer locally (bounded queue) → retry when NATS recovers.
- Intra-region (gRPC) and local delivery unaffected.

---

## 23. Data Compliance (GDPR / Privacy)

### 23.1 Requirements
- **Right to Deletion**: User requests account deletion → delete ALL their messages, presence data, device tokens within 30 days.
- **Data Export**: User requests data export → generate a ZIP of all their messages in JSON format.
- **Data Minimization**: Don't store data you don't need. Don't log message content.
- **Consent**: User must consent to data processing on sign-up.
- **Data Residency**: EU users' data may need to stay in EU region (affects Redis, database, NATS placement).

### 23.2 Implementation
```
DELETE /api/users/{userId}/data
  1. Delete all messages where from_user_id = userId OR to_user_id = userId
  2. Delete Redis presence: DEL online:{userId}
  3. Delete device tokens: DELETE FROM user_devices WHERE userId = ?
  4. Delete conversation metadata
  5. Log deletion event (audit trail, without PII)
  6. Return confirmation to user
```

### 23.3 Audit Logging
- Log WHO accessed WHAT data WHEN (without logging actual message content).
- Store audit logs separately, immutable (append-only S3 bucket with Object Lock).
- Retention: Audit logs for 7 years (regulatory requirement).

---

## 24. Protocol Versioning

### 24.1 Current Gap
- No protocol versioning. If message format changes, old clients break.

### 24.2 WebSocket Protocol Versioning
```
Client connects: wss://chat.yourapp.com?token=xxx&v=2
Server checks version:
  v=1 → use old message format (JSON, flat structure)
  v=2 → use new message format (JSON, nested structure)
  v=3 → use Protobuf binary frames
  unsupported → reject connection with error message
```

### 24.3 gRPC / Proto Versioning
- Protobuf is forward-compatible by design (new fields are ignored by old clients).
- **Never remove or renumber fields** — only add new ones.
- For breaking changes: create a new service version (`InternalCommunicationServiceV2`).

### 24.4 Client Minimum Version Enforcement
- Server maintains `MIN_CLIENT_VERSION = 2`.
- Client sends its version on connect.
- If `clientVersion < MIN_CLIENT_VERSION` → reject with `"UPGRADE_REQUIRED"` error and app store link.

---

## 25. Typing Indicators & Presence Broadcasting

### 25.1 Typing Indicators
```
Client A starts typing → emit "typing_start" { to: "userB" }
Server routes to User B (same routing logic as messages: local/gRPC/NATS)
Client A stops typing (2s timeout) → emit "typing_stop" { to: "userB" }
```
- **Don't persist** — typing events are ephemeral.
- **Throttle**: Max 1 typing event per 3 seconds per conversation.
- **No retry**: If it fails to deliver, don't retry (it's stale).

### 25.2 Online/Offline Presence Broadcasting
- When user connects → broadcast "user_online" to their friends.
- When user disconnects → broadcast "user_offline" to their friends.
- **Problem at scale**: User with 1000 friends → 1000 presence events per connect/disconnect.
- **Fix**: Presence updates are lazy:
  - Don't broadcast proactively.
  - When User A opens chat with User B → query Redis: `EXISTS online:{userB}`.
  - Show online/offline badge based on query result.
  - Subscribe to User B's presence changes (pub/sub) only while chat is open.

---

## Summary Checklist

### Must Have Before Production
- [ ] Message persistence (DB write before delivery)
- [ ] Offline message queue with drain-on-reconnect
- [ ] Delivery acknowledgments (sent/delivered/read)
- [ ] JWT authentication on WebSocket connections
- [ ] TLS everywhere (WSS, mTLS for gRPC, TLS for NATS/Redis)
- [ ] Redis presence TTL + heartbeat
- [ ] Conditional Redis delete (Lua script)
- [ ] NATS JetStream (not basic pub/sub)
- [ ] Graceful shutdown handler
- [ ] Input validation + rate limiting
- [ ] Health check endpoints (/healthz, /readyz)
- [ ] Prometheus metrics + Grafana dashboards
- [ ] Structured logging (JSON)
- [ ] K8s manifests (StatefulSet, Services, HPA, PDB)
- [ ] OS/kernel tuning (sysctl.conf)
- [ ] Load test to validate throughput targets
- [ ] DNS geo-routing (Route 53 latency-based)
- [ ] Client reconnection with exponential backoff + jitter
- [ ] Push notifications for offline users (FCM/APNs)
- [ ] Database schema + message persistence layer
- [ ] CI/CD pipeline with automated testing
- [ ] Canary deployment strategy
- [ ] WebSocket connection draining (5min grace period)
- [ ] Protocol versioning (client version check on connect)
- [ ] Disaster recovery plan (tested)
- [ ] GDPR compliance (deletion, export, audit logs)

### Should Have (v1.1)
- [ ] Multi-device support (fan-out delivery)
- [ ] Session resumption (replay from lastMessageId)
- [ ] Payload compression (WebSocket permessage-deflate)
- [ ] gRPC compression (gzip/snappy)
- [ ] Distributed tracing (OpenTelemetry)
- [ ] Circuit breaker on gRPC pool
- [ ] Message ordering (ULID + sequence numbers)
- [ ] Duplicate message prevention (idempotency)
- [ ] Chaos testing suite
- [ ] Typing indicators
- [ ] Online/offline presence (lazy query)
- [ ] Conversation list with unread counts

### Nice to Have (v2.0)
- [ ] End-to-end encryption (Signal Protocol)
- [ ] io_uring adoption (when Go supports it)
- [ ] Protobuf over WebSocket (binary frames)
- [ ] Read receipts with multi-device sync
- [ ] Message search (ElasticSearch / OpenSearch)
- [ ] Media/file sharing (S3 + CDN + pre-signed URLs)
- [ ] Message reactions
- [ ] Group messaging
- [ ] DPDK/XDP kernel bypass (extreme scale)

