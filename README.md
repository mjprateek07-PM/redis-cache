# 🔴 Redis Distributed Cache — Spring Boot

A production-grade demonstration of distributed caching using **Redis Cluster** with **MySQL** as the primary database, built with Spring Boot 3. Features automatic failover, fault tolerance, multi-instance cache sharing, and full observability.

---

## 📐 Architecture

```
 ┌─────────────────────┐        ┌─────────────────────┐
 │   app-instance-1    │        │   app-instance-2    │
 │   Spring Boot :8080 │        │   Spring Boot :8081 │
 └────────┬────────────┘        └────────┬────────────┘
          │                              │
          └──────────────┬───────────────┘
                         │  Shared Redis Cluster
          ┌──────────────▼───────────────────────────────┐
          │              Redis Cluster                    │
          │  ┌──────────┐ ┌──────────┐ ┌──────────┐     │
          │  │ Master-1 │ │ Master-2 │ │ Master-3 │     │
          │  │  :7001   │ │  :7002   │ │  :7003   │     │
          │  └────┬─────┘ └────┬─────┘ └────┬─────┘     │
          │       │  replicate  │  replicate  │           │
          │  ┌────▼─────┐ ┌────▼─────┐ ┌────▼─────┐     │
          │  │Replica-1 │ │Replica-2 │ │Replica-3 │     │
          │  │  :7004   │ │  :7005   │ │  :7006   │     │
          │  └──────────┘ └──────────┘ └──────────┘     │
          └──────────────────────────────────────────────┘
                         │  cache miss / Redis down
                         ▼
                 ┌───────────────┐
                 │  MySQL :3306  │
                 │  (userdb)     │
                 │ source of truth│
                 └───────────────┘
```

### Cache Flow

| Operation | Annotation | Behaviour |
|---|---|---|
| `GET /users/{id}` | `@Cacheable` | Redis first → DB fallback on miss |
| `POST /users` | `@CachePut` + `@CacheEvict` | Save to DB → warm Redis immediately |
| `PUT /users/{id}` | `@CachePut` + `@CacheEvict` | Update DB → refresh Redis entry |
| `DELETE /users/{id}` | `@CacheEvict` | Delete from DB → evict from Redis |

---

## 🛠️ Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 3.2 |
| Cache Abstraction | Spring Cache (`@Cacheable`, `@CachePut`, `@CacheEvict`) |
| Cache Store | Redis 7.2 Cluster (3 masters + 3 replicas) |
| Redis Client | Lettuce (cluster-aware, connection pooling) |
| Primary DB | MySQL 8.0 |
| ORM | Spring Data JPA / Hibernate |
| Retry | Spring Retry (`@Retryable` + `@Recover`) |
| Observability | Spring Actuator |
| Containerisation | Docker + Docker Compose |

---

## 📁 Project Structure

```
redis-distributed-cache/
├── src/main/java/com/distributed/cache/
│   ├── RedisCacheDemoApplication.java        # @EnableCaching @EnableRetry
│   ├── config/
│   │   └── RedisConfig.java                  # Cluster + CacheManager + ErrorHandler
│   ├── controller/
│   │   ├── UserController.java               # CRUD REST endpoints
│   │   └── CacheDiagnosticsController.java   # Cache inspection endpoints
│   ├── service/
│   │   └── UserService.java                  # Cache annotation logic
│   ├── entity/User.java
│   ├── dto/UserDto.java
│   ├── repository/UserRepository.java
│   ├── exception/
│   │   ├── GlobalExceptionHandler.java
│   │   ├── UserNotFoundException.java
│   │   └── UserAlreadyExistsException.java
│   └── health/
│       └── RedisClusterHealthIndicator.java
├── src/main/resources/
│   ├── application.properties                # Local dev config
│   └── application-docker.properties         # Docker profile overrides
├── docker/
│   ├── mysql/init.sql                        # Schema + seed data
│   └── redis-cluster/
│       ├── redis.conf                        # Shared Redis node config
│       └── create-cluster.sh                 # Cluster bootstrap script
├── docker-compose.yml                        # Full infrastructure
├── Dockerfile                                # Multi-stage Spring Boot image
├── .gitignore
└── pom.xml
```

---

## ⚡ Quick Start

### Prerequisites

- Docker Desktop (with WSL2 backend on Windows)
- Java 17+
- Maven 3.9+

### 1 — Start infrastructure

```bash
git clone https://github.com/YOUR_USERNAME/redis-distributed-cache.git
cd redis-distributed-cache

# Start MySQL + 6 Redis nodes + cluster bootstrap
docker-compose up -d mysql \
  redis-node-1 redis-node-2 redis-node-3 \
  redis-node-4 redis-node-5 redis-node-6 \
  redis-cluster-init

# Watch cluster formation
docker logs -f redis-cluster-init
```

**Expected output:**
```
✓ redis-node-1:7001 is ready
...
[OK] All 16384 slots covered.
cluster_state:ok  cluster_size:3
```

### 2 — Run multiple Spring Boot instances

**Option A — Docker (recommended)**
```bash
docker-compose up -d app-1 app-2 app-3
```

**Option B — Local terminals**
```bash
# Terminal 1
INSTANCE_ID=instance-1 SERVER_PORT=9090 mvn spring-boot:run

# Terminal 2
INSTANCE_ID=instance-2 SERVER_PORT=9091 mvn spring-boot:run
```

### 3 — Verify everything is up

```bash
curl http://localhost:9090/actuator/health
curl http://localhost:9090/api/cache/cluster-info
```

---

## 🌐 API Reference

Base URL: `http://localhost:9090`

### User Endpoints

| Method | Endpoint | Description | Cache Behaviour |
|---|---|---|---|
| `GET` | `/api/users` | List all users | `@Cacheable` (5 min TTL) |
| `GET` | `/api/users/{id}` | Get user by ID | `@Cacheable` (10 min TTL) |
| `GET` | `/api/users/search?keyword=alice` | Search users | `@Cacheable` (3 min TTL) |
| `POST` | `/api/users` | Create user | `@CachePut` + evict list |
| `PUT` | `/api/users/{id}` | Update user | `@CachePut` + evict list |
| `DELETE` | `/api/users/{id}` | Delete user | `@CacheEvict` all related |

### Cache Diagnostic Endpoints

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/cache/status` | All cached keys + TTLs |
| `GET` | `/api/cache/cluster-info` | Redis cluster node topology |
| `GET` | `/api/cache/key/{key}` | Inspect a specific cache entry |
| `DELETE` | `/api/cache/flush` | Clear all cache entries |

### Example Requests

```bash
# Create a user
curl -X POST http://localhost:9090/api/users \
  -H "Content-Type: application/json" \
  -d '{"name":"Alice","email":"alice@example.com","department":"Engineering","jobTitle":"Staff Engineer","active":true}'

# Get user (first call = CACHE MISS, second = CACHE HIT)
curl http://localhost:9090/api/users/1

# Update user (cache refreshed)
curl -X PUT http://localhost:9090/api/users/1 \
  -H "Content-Type: application/json" \
  -d '{"name":"Alice Updated","email":"alice@example.com","department":"Engineering","jobTitle":"Principal Engineer","active":true}'

# Delete user (cache evicted)
curl -X DELETE http://localhost:9090/api/users/1

# Check what's in Redis right now
curl http://localhost:9090/api/cache/status
```

---

## 🔍 Observability — Reading the Logs

```
[CACHE-MISS]  DB query for user id=1 | Redis had no entry
[CACHE-PUT]   User created id=3     | Stored in Redis cache
[CACHE-EVICT] User deleted id=1     | Entry removed from Redis
[CACHE-FAULT] GET failed | cache=users key=1 | Falling back to DB   ← Redis node down
[CACHE-RECOVER] Redis retries exhausted for id=1 | Falling back to DB
```

**Log levels configured:**

| Logger | Level | What it shows |
|---|---|---|
| `com.distributed.cache` | `DEBUG` | Full app flow |
| `org.springframework.cache` | `TRACE` | Cache HIT / MISS decisions |
| `org.springframework.data.redis` | `DEBUG` | Redis commands |
| `org.hibernate.SQL` | `DEBUG` | Every SQL query executed |

---

## 💥 Failure Simulation

### Simulate a Redis master failure

```bash
# 1. Populate the cache
curl http://localhost:9090/api/users/1
curl http://localhost:9090/api/users/2

# 2. Kill master node 1
docker stop redis-node-1

# 3. Immediately call the API — app falls back to DB (no crash)
curl http://localhost:9090/api/users/1
# Log: [CACHE-FAULT] GET failed | Falling back to DB

# 4. Wait ~5–30 seconds for replica promotion
docker exec redis-node-4 redis-cli -p 7004 cluster nodes | grep master

# 5. Caching resumes automatically
curl http://localhost:9090/api/users/1
# Log: [CACHE-MISS] DB query... (first miss after failover, then HITs again)
```

### Restore the failed node

```bash
docker start redis-node-1
# Node rejoins cluster as a replica — no manual intervention needed
```

### Simulate full cache outage (kill 2 masters)

```bash
docker stop redis-node-1 redis-node-2
# Cluster loses quorum — all cache ops fall back to DB
# App continues to serve all requests via MySQL

docker start redis-node-1 redis-node-2
# Cluster recovers — caching resumes
```

---

## 🔒 Fault Tolerance Design

The `CacheErrorHandler` in `RedisConfig.java` is what prevents crashes:

```
Redis node down
      │
      ▼
Spring Cache tries Redis → throws exception
      │
      ▼
CacheErrorHandler.handleCacheGetError()
      │
      ├── Logs: [CACHE-FAULT] GET failed | Falling back to DB
      │
      └── Returns null to Spring Cache
                  │
                  ▼
          @Cacheable method body executes → DB query
                  │
                  ▼
          Response returned to client ✅  (no 500 error)
```

**Spring Retry** adds a second layer — on transient Redis errors, operations are retried up to 3 times with exponential backoff (500ms → 1s → 2s) before the `@Recover` fallback fires.

---

## 📊 Redis Cluster Concepts

### Cluster vs Sentinel

| | Redis Cluster | Redis Sentinel |
|---|---|---|
| Sharding | ✅ 16384 hash slots | ❌ Single dataset |
| Auto failover | ✅ Built-in | ✅ Via Sentinel |
| Scale-out writes | ✅ | ❌ |
| Client complexity | Higher | Lower |
| Best for | Large datasets, horizontal scale | Single large master + HA |

### CAP Theorem in Redis Cluster

Redis Cluster is **AP** (Available + Partition Tolerant):
- Continues serving requests during network partition
- May serve **stale data** during the failover window
- Eventual consistency restored after master promotion

### Hash Slot Distribution

```
Master-1 :7001 → slots    0 –  5460  (⅓ of keyspace)
Master-2 :7002 → slots 5461 – 10922  (⅓ of keyspace)
Master-3 :7003 → slots 10923 – 16383 (⅓ of keyspace)
```

Every key is hashed with CRC16 mod 16384 to determine its slot and therefore its owning master node.

---

## ⚙️ Configuration Reference

| Property | Default | Description |
|---|---|---|
| `server.port` | `9090` | Override with `SERVER_PORT` env var |
| `app.instance-id` | `instance-1` | Override with `INSTANCE_ID` env var |
| `spring.cache.redis.time-to-live` | `600000ms` | Default TTL (10 min) |
| `spring.data.redis.timeout` | `5000ms` | Redis command timeout |
| `lettuce.pool.max-active` | `20` | Max connections in pool |
| `lettuce.cluster.refresh.period` | `30s` | Topology refresh interval |

### Running with different profiles

```bash
# Local (default)
mvn spring-boot:run

# Docker profile
SPRING_PROFILES_ACTIVE=docker mvn spring-boot:run

# Custom instance
INSTANCE_ID=my-instance SERVER_PORT=9092 mvn spring-boot:run
```

---

## 🐳 Docker Services

| Container | Image | Port | Role |
|---|---|---|---|
| `mysql` | mysql:8.0 | 3306 | Primary database |
| `redis-node-1` | redis:7.2-alpine | 7001 | Master (slots 0–5460) |
| `redis-node-2` | redis:7.2-alpine | 7002 | Master (slots 5461–10922) |
| `redis-node-3` | redis:7.2-alpine | 7003 | Master (slots 10923–16383) |
| `redis-node-4` | redis:7.2-alpine | 7004 | Replica of Master-1 |
| `redis-node-5` | redis:7.2-alpine | 7005 | Replica of Master-2 |
| `redis-node-6` | redis:7.2-alpine | 7006 | Replica of Master-3 |
| `redis-cluster-init` | redis:7.2-alpine | — | Bootstrap job (exits after) |
| `app-1` | (built) | 9090 | Spring Boot instance 1 |
| `app-2` | (built) | 9091 | Spring Boot instance 2 |
| `app-3` | (built) | 9092 | Spring Boot instance 3 |

---

## 📄 License

MIT License — free to use, modify, and distribute.
