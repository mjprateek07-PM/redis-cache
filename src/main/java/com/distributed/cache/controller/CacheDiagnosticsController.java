package com.distributed.cache.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisClusterConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Diagnostic endpoints for observing Redis cluster state,
 * cache entries, and simulating failures.
 */
@Slf4j
@RestController
@RequestMapping("/api/cache")
@RequiredArgsConstructor
public class CacheDiagnosticsController {

    private final RedisTemplate<String, Object> redisTemplate;
    private final RedisConnectionFactory connectionFactory;

    @Value("${app.instance-id:default}")
    private String instanceId;

    // ─── GET /api/cache/status ────────────────────────────────
    // Shows all cache keys currently in Redis
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getCacheStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("instanceId", instanceId);
        status.put("timestamp", new Date());

        try {
            Set<String> userKeys = redisTemplate.keys("users::*");
            Set<String> listKeys = redisTemplate.keys("users-list::*");
            Set<String> searchKeys = redisTemplate.keys("user-search::*");

            status.put("redisConnected", true);
            status.put("cachedUserEntries",   userKeys != null ? userKeys.size() : 0);
            status.put("cachedListEntries",   listKeys != null ? listKeys.size() : 0);
            status.put("cachedSearchEntries", searchKeys != null ? searchKeys.size() : 0);
            status.put("allCacheKeys", mergeSets(userKeys, listKeys, searchKeys));

            // TTL for each key
            Map<String, Long> ttls = new LinkedHashMap<>();
            if (userKeys != null) {
                for (String key : userKeys) {
                    Long ttl = redisTemplate.getExpire(key);
                    ttls.put(key, ttl);
                }
            }
            status.put("keyTTLs_seconds", ttls);

        } catch (Exception e) {
            status.put("redisConnected", false);
            status.put("error", e.getMessage());
            log.warn("[DIAGNOSTIC] Redis status check failed: {}", e.getMessage());
        }

        return ResponseEntity.ok(status);
    }

    // ─── GET /api/cache/cluster-info ──────────────────────────
    // Shows Redis cluster topology (which nodes are alive)
    @GetMapping("/cluster-info")
    public ResponseEntity<Map<String, Object>> getClusterInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("instanceId", instanceId);

        try {
            RedisClusterConnection clusterConn =
                    connectionFactory.getClusterConnection();

            // Cluster node topology
            List<Map<String, Object>> nodes = new ArrayList<>();
            clusterConn.clusterGetNodes().forEach(node -> {
                Map<String, Object> nodeInfo = new LinkedHashMap<>();
                nodeInfo.put("id",        node.getId());
                nodeInfo.put("host",      node.getHost());
                nodeInfo.put("port",      node.getPort());
                nodeInfo.put("type",      node.getType() != null ? node.getType().name() : "UNKNOWN");
                nodeInfo.put("slotRange", node.getSlotRange() != null ? node.getSlotRange().toString() : "N/A");
                nodeInfo.put("alive",     !node.isMarkedAsFail());
                nodes.add(nodeInfo);
            });

            info.put("clusterNodes", nodes);
            info.put("totalNodes", nodes.size());
            info.put("aliveNodes", nodes.stream().filter(n -> Boolean.TRUE.equals(n.get("alive"))).count());
            clusterConn.close();

        } catch (Exception e) {
            info.put("error", "Cannot reach Redis cluster: " + e.getMessage());
            log.error("[DIAGNOSTIC] Cluster info failed: {}", e.getMessage());
        }

        return ResponseEntity.ok(info);
    }

    // ─── DELETE /api/cache/flush ──────────────────────────────
    // Flush all cache entries (use for testing)
    @DeleteMapping("/flush")
    public ResponseEntity<Map<String, String>> flushCache() {
        Map<String, String> result = new LinkedHashMap<>();
        try {
            Set<String> keys = redisTemplate.keys("users*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                result.put("status", "flushed");
                result.put("deletedKeys", String.valueOf(keys.size()));
                log.info("[DIAGNOSTIC] Cache flushed: {} keys deleted", keys.size());
            } else {
                result.put("status", "nothing_to_flush");
            }
        } catch (Exception e) {
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    // ─── GET /api/cache/key/{key} ─────────────────────────────
    // Inspect a specific cache entry
    @GetMapping("/key/{key}")
    public ResponseEntity<Map<String, Object>> getCacheEntry(@PathVariable String key) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Object value = redisTemplate.opsForValue().get(key);
            Long ttl = redisTemplate.getExpire(key);

            result.put("key",   key);
            result.put("found", value != null);
            result.put("ttl_seconds", ttl);
            result.put("value", value);
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    private Set<String> mergeSets(Set<String>... sets) {
        Set<String> merged = new LinkedHashSet<>();
        for (Set<String> s : sets) {
            if (s != null) merged.addAll(s);
        }
        return merged;
    }
}
