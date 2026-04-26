# Redis Distributed Caching — Complete Setup & Execution Guide

## Project Structure

```
redis-distributed-cache/
├── src/main/java/com/distributed/cache/
│   ├── RedisCacheDemoApplication.java       # Entry point (@EnableCaching @EnableRetry)
│   ├── config/
│   │   └── RedisConfig.java                 # Cluster + CacheManager + ErrorHandler
│   ├── controller/
│   │   ├── UserController.java              # CRUD REST endpoints
│   │   └── CacheDiagnosticsController.java  # Cache inspection endpoints
│   ├── service/
│   │   └── UserService.java                 # @Cacheable / @CachePut / @CacheEvict
│   ├── entity/User.java
│   ├── dto/UserDto.java
│   ├── repository/UserRepository.java
│   ├── exception/
│   │   ├── GlobalExceptionHandler.java
│   │   ├── UserNotFoundException.java
│   │   └── UserAlreadyExistsException.java
│   └── health/RedisClusterHealthIndicator.java
├── src/main/resources/
│   └── application.yml
├── docker/
│   ├── mysql/init.sql
│   └── redis-cluster/
│       ├── redis.conf
│       └── create-cluster.sh
├── docker-compose.yml
├── Dockerfile
└── pom.xml
```

---

## Step 1 — Prerequisites

```bash
# Verify Docker Desktop is running (WSL)
docker version
docker-compose version

# Verify Java 17+
java -version

# Verify Maven
mvn -version
```

---

## Step 2 — Start All Infrastructure

```bash
# Clone/extract project, then:
cd redis-distributed-cache

# Start MySQL + all 6 Redis nodes + cluster init
docker-compose up -d mysql redis-node-1 redis-node-2 redis-node-3 \
                       redis-node-4 redis-node-5 redis-node-6 redis-cluster-init

# Watch cluster init logs
docker logs -f redis-cluster-init
```

**Expected output from redis-cluster-init:**
```
==> Waiting for Redis nodes to be ready...
    ✓ redis-node-1:7001 is ready
    ✓ redis-node-2:7002 is ready
    ...
==> All nodes ready. Creating Redis Cluster...
>>> Performing hash slots allocation on 6 nodes...
Master[0] -> Slots 0 - 5460
Master[1] -> Slots 5461 - 10922
Master[2] -> Slots 10923 - 16383
Adding replica redis-node-4:7004 to redis-node-1:7001
...
[OK] All 16384 slots covered.
==> Redis Cluster created successfully!
cluster_state:ok
cluster_slots_assigned:16384
cluster_known_nodes:6
cluster_size:3
```

---

## Step 3 — Verify Redis Cluster Health

```bash
# Check cluster topology from inside a node
docker exec -it redis-node-1 redis-cli -p 7001 cluster nodes

# Check cluster info
docker exec -it redis-node-1 redis-cli -p 7001 cluster info

# Verify slot distribution
docker exec -it redis-node-1 redis-cli -p 7001 cluster slots
```

---

## Step 4 — Build and Run Spring Boot (Multiple Instances)

### Option A — Run in Docker (Recommended)

```bash
# Build and start all 3 app instances
docker-compose up -d app-1 app-2 app-3

# Watch logs from instance 1
docker logs -f app-1

# Watch logs from instance 2
docker logs -f app-2
```

### Option B — Run locally (WSL / Terminal)

```bash
# Build the JAR
mvn clean package -DskipTests

# Terminal 1 — Instance 1
INSTANCE_ID=local-instance-1 SERVER_PORT=8080 \
  java -jar target/redis-cache-demo-1.0.0.jar

# Terminal 2 — Instance 2
INSTANCE_ID=local-instance-2 SERVER_PORT=8081 \
  java -jar target/redis-cache-demo-1.0.0.jar

# Terminal 3 — Instance 3
INSTANCE_ID=local-instance-3 SERVER_PORT=8082 \
  java -jar target/redis-cache-demo-1.0.0.jar
```

---

## Step 5 — API Testing

### Create Users

```bash
curl -X POST http://localhost:8080/api/users \
  -H "Content-Type: application/json" \
  -d '{"name":"Alice Johnson","email":"alice@example.com","department":"Engineering","jobTitle":"Staff Engineer","active":true}'

curl -X POST http://localhost:8080/api/users \
  -H "Content-Type: application/json" \
  -d '{"name":"Bob Smith","email":"bob@example.com","department":"Product","jobTitle":"PM","active":true}'
```

### GET — First call (CACHE MISS → DB hit)

```bash
curl http://localhost:8080/api/users/1
```

**Log output (instance 1):**
```
[CACHE-MISS] DB query for user id=1 | Redis had no entry
Hibernate: select u1_0.id, ... from users u1_0 where u1_0.id=?
```

### GET — Second call (CACHE HIT → no DB hit)

```bash
curl http://localhost:8080/api/users/1
```

**Log output:**
```
[REQUEST] GET /api/users/1 | Instance: local-instance-1
# No DB query logged — served from Redis!
```

### Cross-instance cache sharing

```bash
# Write via instance 1 (port 8080)
curl -X POST http://localhost:8080/api/users \
  -H "Content-Type: application/json" \
  -d '{"name":"Carol","email":"carol@test.com","department":"Design","jobTitle":"UX","active":true}'

# Read via instance 2 (port 8081) — should be CACHE HIT
curl http://localhost:8081/api/users/1

# Look at instanceId field in response — it says "local-instance-2"
# but no DB query was logged — cache was shared from Redis!
```

### Update User (cache refresh)

```bash
curl -X PUT http://localhost:8080/api/users/1 \
  -H "Content-Type: application/json" \
  -d '{"name":"Alice Updated","email":"alice@example.com","department":"Engineering","jobTitle":"Principal Engineer","active":true}'
```

**Log:**
```
[CACHE-PUT] User updated id=1 | Cache refreshed in Redis
```

### Delete User (cache eviction)

```bash
curl -X DELETE http://localhost:8080/api/users/1
```

**Log:**
```
[CACHE-EVICT] User deleted id=1 | Entry removed from Redis
```

---

## Step 6 — Cache Diagnostics

```bash
# See all cached keys and TTLs
curl http://localhost:8080/api/cache/status | python3 -m json.tool

# See Redis cluster topology
curl http://localhost:8080/api/cache/cluster-info | python3 -m json.tool

# Inspect a specific cache entry
curl "http://localhost:8080/api/cache/key/users::1"

# Flush all cache
curl -X DELETE http://localhost:8080/api/cache/flush

# Health check (shows node-level health)
curl http://localhost:8080/actuator/health | python3 -m json.tool
```

---

## Step 7 — FAILURE SIMULATION

### Simulate Master Node Failure

```bash
# Step 1: Populate cache
curl http://localhost:8080/api/users/1
curl http://localhost:8080/api/users/2

# Step 2: Verify cluster is healthy
docker exec redis-node-1 redis-cli -p 7001 cluster nodes | grep master

# Step 3: KILL a master node
docker stop redis-node-1

# Step 4: Immediately call the API
curl http://localhost:8080/api/users/1
```

**What happens during the ~5–30s failover window:**
```
[CACHE-FAULT] GET failed | cache=users key=1 | reason=Unable to connect to... | Falling back to DB
Hibernate: select u1_0.id ... from users u1_0 where u1_0.id=?
```

- Application **does NOT crash**
- CacheErrorHandler silently swallows the Redis error
- Falls through to MySQL
- Response still returned to client

**After failover completes** (~5–30 seconds):
```bash
# Replica-1 (port 7004) is promoted to master
docker exec redis-node-4 redis-cli -p 7004 cluster nodes | grep master

# Normal caching resumes
curl http://localhost:8080/api/users/1
# CACHE MISS first → stores in new master → subsequent calls: HIT
```

### Simulate Recovery

```bash
# Restart the failed node (it rejoins as replica)
docker start redis-node-1

# Verify it rejoined
docker exec redis-node-1 redis-cli -p 7001 cluster nodes

# Lettuce client auto-detects topology change
# Normal cluster operation resumes
```

### Simulate Multiple Node Failures

```bash
# Kill 2 out of 3 masters → cluster loses quorum
docker stop redis-node-1 redis-node-2

# App logs will show:
# [CACHE-FAULT] Cluster unreachable... Falling back to DB
# All requests go to MySQL until quorum restored

# Restore
docker start redis-node-1 redis-node-2
```

---

## Step 8 — Observability Checklist

| Log Pattern | Meaning |
|---|---|
| `[CACHE-MISS] DB query for user id=X` | Redis had no entry; DB queried |
| `[CACHE-PUT] User created/updated id=X` | DB write + cache warmed |
| `[CACHE-EVICT] User deleted id=X` | Cache entry removed |
| `[CACHE-FAULT] GET failed ... Falling back to DB` | Redis error, DB fallback used |
| `[CACHE-RECOVER] Redis retries exhausted` | All retry attempts failed |
| `[REQUEST] GET ... Instance: app-instance-2` | Which app instance served it |
| `Hibernate: select ...` | SQL query executed (cache miss or bypass) |

Enable verbose cache logs in `application.yml`:
```yaml
logging:
  level:
    org.springframework.cache: TRACE
```

TRACE output:
```
o.s.cache.interceptor.CacheInterceptor - Computed cache key '1' for operation
o.s.cache.interceptor.CacheInterceptor - Cache entry for key '1' found in cache 'users'
```

---

## Interview Concepts Explained

### Redis Cluster vs Sentinel

| Feature | Redis Cluster | Redis Sentinel |
|---|---|---|
| Sharding | Yes (16384 hash slots) | No (single dataset) |
| HA | Yes (auto failover) | Yes (monitors + promotes) |
| Multi-node writes | Yes | No |
| Client complexity | Higher (cluster-aware) | Lower |
| Use case | Large datasets, scale-out | Single large master, HA only |

**This project uses Cluster** = sharding + replication + automatic failover in one.

### CAP Theorem in Redis Cluster

Redis Cluster prioritizes **AP** (Availability + Partition Tolerance):
- On network partition, Redis continues serving requests (A)
- It may serve **stale data** during failover window (trades C for A)
- After failover, consistency is restored (eventual consistency)
- There is a `cluster-node-timeout` window where writes to the failed master are lost

### Cache Invalidation Strategies

1. **TTL Expiry** — entries auto-expire (used here: 10 min users, 5 min lists)
2. **@CacheEvict** — explicit removal on write/delete (used here)
3. **@CachePut** — overwrite on update, never stale (used here)
4. **Event-driven** — Kafka/SQS signals cache invalidation across services (prod pattern)

### Cache Stampede Handling

Problem: 1000 concurrent requests, cache entry expires → all hit DB simultaneously.

Solutions:
1. **Probabilistic Early Expiration** — randomly refresh before TTL expires
2. **Redis Lock** — only one thread populates cache, others wait
3. **Short TTL with background refresh** — serve stale while refreshing async
4. **`@Cacheable(sync=true)`** — Spring locks method per key (used in single-instance)

In this project, `disableCachingNullValues()` prevents null stampedes.

### When NOT to use Redis

- Data that changes every request (no cache benefit)
- Highly relational data with complex joins (hard to invalidate)
- When you can't tolerate stale reads (financial transactions)
- Data > 512MB per value (Redis value size limit)
- When latency from Redis is worse than DB (in same datacenter, rare)
- Session data that must be strongly consistent

### How Replication Works

1. Master receives write → executes → stores in memory + AOF
2. Master sends commands to replicas via replication stream (async)
3. Replicas apply same commands → stay in sync
4. On master failure, replicas compare replication offsets → highest offset wins
5. Winning replica broadcasts FAILOVER → cluster promotes it to master
6. Old master rejoins as replica after recovery
