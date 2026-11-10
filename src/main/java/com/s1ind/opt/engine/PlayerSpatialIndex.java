package com.s1ind.opt.engine;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.ArrayList;
import java.util.List;

/**
 * Spatial chunk-bucket index (32x32 block regions) to eliminate O(mobs * players)
 * distance scans on high-population multiplayer servers while preserving exact 0-tick precision.
 *
 * Stores primitive coordinates only (no Entity/World references retained) and recycles
 * node lists across ticks to prevent heap allocation churn.
 */
public final class PlayerSpatialIndex {

    public static final int REGION_SHIFT = 5; // 32x32 blocks per region bucket
    public static final int DIRECT_SCAN_THRESHOLD = 8;

    private static final class Bucket {
        double[] coords = new double[24]; // packed x, y, z triples (capacity 8 players initially)
        int count = 0;

        void reset() {
            count = 0;
        }

        void add(double x, double y, double z) {
            int base = count * 3;
            if (base + 3 > coords.length) {
                double[] grown = new double[coords.length * 2];
                System.arraycopy(coords, 0, grown, 0, coords.length);
                coords = grown;
            }
            coords[base] = x;
            coords[base + 1] = y;
            coords[base + 2] = z;
            count++;
        }
    }

    private final Long2ObjectOpenHashMap<Bucket> buckets = new Long2ObjectOpenHashMap<>(64);
    private final List<Bucket> pool = new ArrayList<>(64);
    private int poolCursor = 0;

    private Object lastWorldRef = null;
    private long lastTickTime = Long.MIN_VALUE;

    private static final ThreadLocal<PlayerSpatialIndex> THREAD_LOCAL = ThreadLocal.withInitial(PlayerSpatialIndex::new);

    public static PlayerSpatialIndex forCurrentThread() {
        return THREAD_LOCAL.get();
    }

    public static void clearCurrentThread() {
        THREAD_LOCAL.get().clearAll();
        THREAD_LOCAL.remove();
    }

    public boolean needsRebuild(Object worldRef, long worldTime) {
        return worldRef != lastWorldRef || worldTime != lastTickTime;
    }

    public void beginFrame(Object worldRef, long worldTime) {
        lastWorldRef = worldRef;
        lastTickTime = worldTime;
        buckets.clear();
        poolCursor = 0;
    }

    public void addPlayer(double x, double y, double z) {
        int rx = ((int) Math.floor(x)) >> REGION_SHIFT;
        int rz = ((int) Math.floor(z)) >> REGION_SHIFT;
        long key = packKey(rx, rz);

        Bucket bucket = buckets.get(key);
        if (bucket == null) {
            if (poolCursor < pool.size()) {
                bucket = pool.get(poolCursor++);
                bucket.reset();
            } else {
                bucket = new Bucket();
                pool.add(bucket);
                poolCursor++;
            }
            buckets.put(key, bucket);
        }
        bucket.add(x, y, z);
    }

    /**
     * Finds the exact squared distance to the closest indexed player within maxRadiusBlocks,
     * scanning only overlapping 32x32 buckets. Returns Double.MAX_VALUE if none are near.
     */
    public double findNearestDistanceSq(double mx, double my, double mz, double maxRadiusBlocks) {
        int minRx = ((int) Math.floor(mx - maxRadiusBlocks)) >> REGION_SHIFT;
        int maxRx = ((int) Math.floor(mx + maxRadiusBlocks)) >> REGION_SHIFT;
        int minRz = ((int) Math.floor(mz - maxRadiusBlocks)) >> REGION_SHIFT;
        int maxRz = ((int) Math.floor(mz + maxRadiusBlocks)) >> REGION_SHIFT;

        double minDistSq = Double.MAX_VALUE;
        for (int rx = minRx; rx <= maxRx; rx++) {
            for (int rz = minRz; rz <= maxRz; rz++) {
                Bucket bucket = buckets.get(packKey(rx, rz));
                if (bucket == null) continue;

                double[] c = bucket.coords;
                for (int i = 0, n = bucket.count * 3; i < n; i += 3) {
                    double dx = c[i] - mx;
                    double dy = c[i + 1] - my;
                    double dz = c[i + 2] - mz;
                    double dSq = dx * dx + dy * dy + dz * dz;
                    if (dSq < minDistSq) {
                        minDistSq = dSq;
                        if (minDistSq <= MobAiPolicy.INTERACTION_RADIUS_SQ) {
                            return minDistSq; // Immediate exit inside guaranteed 16-block radius
                        }
                    }
                }
            }
        }
        return minDistSq;
    }

    public void clearAll() {
        buckets.clear();
        pool.clear();
        poolCursor = 0;
        lastWorldRef = null;
        lastTickTime = Long.MIN_VALUE;
    }

    private static long packKey(int rx, int rz) {
        return (((long) rx) << 32) | (rz & 0xffffffffL);
    }
}
