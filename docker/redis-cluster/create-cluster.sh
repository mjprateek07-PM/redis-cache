#!/bin/sh
# ============================================================
# Redis Cluster Bootstrap Script
# ============================================================
# This runs inside the redis-cluster-init container.
# Waits for all 6 Redis nodes to be ready, then forms the cluster.
#
# Cluster layout after this script:
#   Masters:  redis-node-1:7001, redis-node-2:7002, redis-node-3:7003
#   Replicas: redis-node-4:7004 → M1, redis-node-5:7005 → M2, redis-node-6:7006 → M3
# ============================================================

set -e

echo "==> Waiting for Redis nodes to be ready..."

wait_for_node() {
    host=$1
    port=$2
    echo "    Waiting for $host:$port..."
    until redis-cli -h "$host" -p "$port" ping 2>/dev/null | grep -q PONG; do
        sleep 1
    done
    echo "    ✓ $host:$port is ready"
}

wait_for_node redis-node-1 7001
wait_for_node redis-node-2 7002
wait_for_node redis-node-3 7003
wait_for_node redis-node-4 7004
wait_for_node redis-node-5 7005
wait_for_node redis-node-6 7006

echo ""
echo "==> All nodes ready. Creating Redis Cluster..."
echo "    3 masters + 3 replicas (1 replica per master)"
echo ""

# --cluster-replicas 1: each master gets 1 replica
# Redis auto-assigns replicas to avoid co-location with master
redis-cli --cluster create \
    redis-node-1:7001 \
    redis-node-2:7002 \
    redis-node-3:7003 \
    redis-node-4:7004 \
    redis-node-5:7005 \
    redis-node-6:7006 \
    --cluster-replicas 1 \
    --cluster-yes

echo ""
echo "==> Redis Cluster created successfully!"
echo ""
echo "==> Cluster info:"
redis-cli -h redis-node-1 -p 7001 cluster info | grep -E "cluster_state|cluster_slots_assigned|cluster_known_nodes|cluster_size"

echo ""
echo "==> Node roles:"
redis-cli -h redis-node-1 -p 7001 cluster nodes
