package com.distributed.cache.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.lettuce.core.ReadFrom;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ============================================================
 * DISTRIBUTED REDIS CLUSTER CONFIGURATION
 * ============================================================
 *
 * ARCHITECTURE:
 *   ┌─────────────┐    ┌─────────────┐    ┌─────────────┐
 *   │  Master-1   │    │  Master-2   │    │  Master-3   │
 *   │  Port 7001  │    │  Port 7002  │    │  Port 7003  │
 *   │  Slots 0-   │    │  Slots 5461-│    │  Slots10923-│
 *   │  5460       │    │  10922      │    │  16383      │
 *   └──────┬──────┘    └──────┬──────┘    └──────┬──────┘
 *          │                  │                  │
 *   ┌──────▼──────┐    ┌──────▼──────┐    ┌──────▼──────┐
 *   │  Replica-1  │    │  Replica-2  │    │  Replica-3  │
 *   │  Port 7004  │    │  Port 7005  │    │  Port 7006  │
 *   └─────────────┘    └─────────────┘    └─────────────┘
 *
 * KEY CONCEPTS:
 * - Redis Cluster = sharding + replication (no single point of failure)
 * - 16384 hash slots distributed across masters
 * - Each master has a replica for HA
 * - Lettuce client is cluster-aware (handles redirects, failover)
 * - ReadFrom.REPLICA_PREFERRED = reads go to replicas (offloads masters)
 * ============================================================
 */
@Slf4j
@Configuration
public class RedisConfig implements CachingConfigurer {


    private List<String> clusterNodes=List.of("127.0.0.1:7001","127.0.0.1:7002","127.0.0.1:7003","127.0.0.1:7004","127.0.0.1:7005","127.0.0.1:7006");


    private int maxRedirects=3;

    // ─────────────────────────────────────────────────────────
    // 1. CLUSTER CONNECTION FACTORY
    // ─────────────────────────────────────────────────────────

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        // Cluster topology config
        RedisClusterConfiguration clusterConfig = new RedisClusterConfiguration(clusterNodes);
        clusterConfig.setMaxRedirects(maxRedirects);

        // Auto-topology refresh: detect node changes (failovers, adds)
        ClusterTopologyRefreshOptions topologyRefreshOptions = ClusterTopologyRefreshOptions.builder()
                .enableAllAdaptiveRefreshTriggers()
                .enablePeriodicRefresh(Duration.ofSeconds(30))
                .dynamicRefreshSources(true)
                .build();

        ClusterClientOptions clientOptions = ClusterClientOptions.builder()
                .topologyRefreshOptions(topologyRefreshOptions)
                .validateClusterNodeMembership(false) // important for Docker networking
                .build();

        // Connection pooling via Apache Commons Pool2
        LettucePoolingClientConfiguration clientConfig = LettucePoolingClientConfiguration.builder()
                .readFrom(ReadFrom.REPLICA_PREFERRED) // Reads from replicas → offloads masters
                .clientOptions(clientOptions)
                .commandTimeout(Duration.ofSeconds(5))
                .shutdownTimeout(Duration.ofMillis(100))
                .poolConfig(buildPoolConfig())
                .build();

        LettuceConnectionFactory factory = new LettuceConnectionFactory(clusterConfig, clientConfig);
        factory.setValidateConnection(false); // Avoid blocking on startup
        return factory;
    }

    private org.apache.commons.pool2.impl.GenericObjectPoolConfig<?> buildPoolConfig() {
        org.apache.commons.pool2.impl.GenericObjectPoolConfig<?> config =
                new org.apache.commons.pool2.impl.GenericObjectPoolConfig<>();
        config.setMaxTotal(20);       // Max connections in pool
        config.setMaxIdle(10);        // Max idle connections
        config.setMinIdle(5);         // Always keep 5 warm connections
        config.setTestOnBorrow(true); // Validate before use
        config.setTestWhileIdle(true);
        config.setTimeBetweenEvictionRuns(Duration.ofSeconds(30));
        return config;
    }

    // ─────────────────────────────────────────────────────────
    // 2. JSON SERIALIZATION (Human-readable, type-safe)
    // ─────────────────────────────────────────────────────────

    @Bean
    public ObjectMapper redisObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // Store class type info so deserialization works for generic Object
        mapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
        );
        return mapper;
    }

    @Bean
    public GenericJackson2JsonRedisSerializer redisSerializer() {
        return new GenericJackson2JsonRedisSerializer(redisObjectMapper());
    }

    // ─────────────────────────────────────────────────────────
    // 3. REDIS TEMPLATE (Low-level operations)
    // ─────────────────────────────────────────────────────────

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer jsonSerializer = redisSerializer();

        template.setKeySerializer(stringSerializer);          // Keys: plain strings
        template.setHashKeySerializer(stringSerializer);
        template.setValueSerializer(jsonSerializer);          // Values: JSON
        template.setHashValueSerializer(jsonSerializer);
        template.setEnableTransactionSupport(false);          // Cluster mode: no multi-key tx
        template.afterPropertiesSet();

        return template;
    }

    // ─────────────────────────────────────────────────────────
    // 4. CACHE MANAGER (Spring Cache abstraction)
    // ─────────────────────────────────────────────────────────

    @Bean
    @Override
    public CacheManager cacheManager() {
        RedisCacheConfiguration defaultConfig = buildDefaultCacheConfig(Duration.ofMinutes(10));

        // Per-cache TTL overrides
        Map<String, RedisCacheConfiguration> cacheConfigs = new HashMap<>();
        cacheConfigs.put("users",        buildDefaultCacheConfig(Duration.ofMinutes(10)));
        cacheConfigs.put("users-list",   buildDefaultCacheConfig(Duration.ofMinutes(5)));
        cacheConfigs.put("user-search",  buildDefaultCacheConfig(Duration.ofMinutes(3)));

        return RedisCacheManager.builder(redisConnectionFactory())
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigs)
                .transactionAware()      // Evict only on tx commit
                .build();
    }

    private RedisCacheConfiguration buildDefaultCacheConfig(Duration ttl) {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(ttl)
                .disableCachingNullValues()          // Never cache null → force DB lookup
                .computePrefixWith(name -> name + ":") // Key prefix: "users:1"
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair
                                .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair
                                .fromSerializer(redisSerializer()));
    }

    // ─────────────────────────────────────────────────────────
    // 5. FAULT-TOLERANT ERROR HANDLER
    // ─────────────────────────────────────────────────────────
    //
    // CRITICAL: Without this, any Redis failure = 500 error.
    // With this:  Redis failure = log warning + fallback to DB.
    //
    // What happens during node failure:
    //   1. Master-1 goes down
    //   2. Redis cluster promotes Replica-1 to master (~5-30s)
    //   3. During this window, cache ops fail → caught here → DB fallback
    //   4. After promotion, new master serves requests
    //   5. Lettuce auto-detects topology change (adaptive refresh)
    //   6. Normal caching resumes

    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {

            @Override
            public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
                log.warn("[CACHE-FAULT] GET failed | cache={} key={} | reason={} | Falling back to DB",
                        cache.getName(), key, e.getMessage());
                // Return null → Spring calls the actual @Cacheable method (DB)
            }

            @Override
            public void handleCachePutError(RuntimeException e, Cache cache, Object key, Object value) {
                log.warn("[CACHE-FAULT] PUT failed | cache={} key={} | reason={} | DB write succeeded, cache skipped",
                        cache.getName(), key, e.getMessage());
                // DB write already succeeded. Cache is temporarily stale. No crash.
            }

            @Override
            public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
                log.warn("[CACHE-FAULT] EVICT failed | cache={} key={} | reason={} | Entry may be stale until TTL expires",
                        cache.getName(), key, e.getMessage());
            }

            @Override
            public void handleCacheClearError(RuntimeException e, Cache cache) {
                log.error("[CACHE-FAULT] CLEAR failed | cache={} | reason={}",
                        cache.getName(), e.getMessage());
            }
        };
    }
}
