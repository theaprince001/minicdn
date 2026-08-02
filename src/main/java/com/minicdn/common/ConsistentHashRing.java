package com.minicdn.common;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

public class ConsistentHashRing {
    private final TreeMap<Long, EdgeInfo> ring = new TreeMap<>();
    private final int virtualNodesPerEdge;
    private final MessageDigest md;

    public ConsistentHashRing(List<EdgeInfo> edges, int virtualNodesPerEdge) {
        this.virtualNodesPerEdge = virtualNodesPerEdge;
        try {
            md = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 not available", e);
        }
        for (EdgeInfo edge : edges) {
            addEdge(edge);
        }
    }

    public void addEdge(EdgeInfo edge) {
        for (int i = 0; i < virtualNodesPerEdge; i++) {
            long hash = hash(edge.id + "-" + i);
            ring.put(hash, edge);
        }
    }

    public void removeEdge(EdgeInfo edge) {
        for (int i = 0; i < virtualNodesPerEdge; i++) {
            long hash = hash(edge.id + "-" + i);
            ring.remove(hash);
        }
    }

    /**
     * Find the primary edge for a given key.
     */
    public EdgeInfo getNode(String key) {
        if (ring.isEmpty()) return null;
        long hash = hash(key);
        if (!ring.containsKey(hash)) {
            SortedMap<Long, EdgeInfo> tailMap = ring.tailMap(hash);
            hash = tailMap.isEmpty() ? ring.firstKey() : tailMap.firstKey();
        }
        return ring.get(hash);
    }

    /**
     * Walk the ring clockwise to find the first healthy edge.
     */
    public EdgeInfo getHealthyNode(String key, Map<String, Boolean> healthyMap) {
        if (ring.isEmpty()) return null;
        EdgeInfo primary = getNode(key);
        if (primary != null && healthyMap.getOrDefault(primary.id, false)) {
            return primary;
        }
        long hash = hash(key);
        Long start = hash;
        for (int i = 0; i < ring.size(); i++) {
            SortedMap<Long, EdgeInfo> tailMap = ring.tailMap(start);
            Long nextKey = tailMap.isEmpty() ? ring.firstKey() : tailMap.firstKey();
            EdgeInfo next = ring.get(nextKey);
            if (healthyMap.getOrDefault(next.id, false)) {
                return next;
            }
            start = nextKey + 1;
        }
        return null; // no healthy edge
    }

    private long hash(String key) {
        md.reset();
        byte[] digest = md.digest(key.getBytes());
        long h = 0;
        for (int i = 0; i < 8; i++) {
            h = (h << 8) | (digest[i] & 0xFF);
        }
        return h & Long.MAX_VALUE; // positive
    }
}