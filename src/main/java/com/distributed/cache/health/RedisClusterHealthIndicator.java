package com.distributed.cache.health;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.connection.RedisClusterConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Custom health indicator that shows Redis cluster node health.
 * Accessible at: GET /actuator/health
 */
@Slf4j
@Component("redisCluster")
@RequiredArgsConstructor
public class RedisClusterHealthIndicator implements HealthIndicator {

    private final RedisConnectionFactory connectionFactory;

    @Override
    public Health health() {
        try {
            RedisClusterConnection connection = connectionFactory.getClusterConnection();
            AtomicInteger totalNodes = new AtomicInteger(0);
            AtomicInteger aliveNodes = new AtomicInteger(0);

            Map<String, String> nodeDetails = new LinkedHashMap<>();
            connection.clusterGetNodes().forEach(node -> {
                totalNodes.incrementAndGet();
                boolean alive = !node.isMarkedAsFail();
                if (alive) aliveNodes.incrementAndGet();

                String nodeKey = node.getHost() + ":" + node.getPort();
                String status = alive ? "UP (" + (node.getType() != null ? node.getType() : "UNKNOWN") + ")" : "DOWN";
                nodeDetails.put(nodeKey, status);
            });

            connection.close();

            boolean healthy = aliveNodes.get() > 0 && aliveNodes.get() >= (totalNodes.get() / 2 + 1);

            if (healthy) {
                return Health.up()
                        .withDetail("totalNodes", totalNodes.get())
                        .withDetail("aliveNodes", aliveNodes.get())
                        .withDetail("nodes", nodeDetails)
                        .build();
            } else {
                return Health.down()
                        .withDetail("totalNodes", totalNodes.get())
                        .withDetail("aliveNodes", aliveNodes.get())
                        .withDetail("nodes", nodeDetails)
                        .withDetail("reason", "Quorum lost or too many nodes down")
                        .build();
            }

        } catch (Exception e) {
            log.error("[HEALTH] Redis cluster health check failed: {}", e.getMessage());
            return Health.down()
                    .withDetail("error", e.getMessage())
                    .withDetail("reason", "Cannot connect to Redis cluster")
                    .build();
        }
    }
}
