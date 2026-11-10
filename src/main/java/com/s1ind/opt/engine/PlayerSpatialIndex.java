package com.s1ind.opt.engine;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.ArrayList;
import java.util.List;

/**
 * Spatial region index (32x32 block buckets) with O(1) incremental intra-tick updates.
 *
 * Eliminates both O(mobs * players) scanning AND intra-tick coordinate staleness:
 * - Supports instant O(1) bucket migration when a player moves, teleports, dies,
 *   respawns, or switches dimensions during an active tick.
 * - Stores only primitive coordinates and entity IDs (never retains Entity/World references beyond weak/identity comparison cleared at shutdown).
 * - Recycles both Bucket and PlayerSlot instances via internal pools.
 */
public final class PlayerSpatialIndex {

    public static final int REGION_SHIFT = 5; // 32x32 blocks per region bucket
    public static final int DIRECT_SCAN_THRESHOLD = 8;

    private static final class PlayerSlot {
        int entityId;
        long bucketKey;
        Bucket bucket;
        int indexInBucket;
        boolean active;
    }

    private static final class Bucket {
        long key;
        int[] ids = new int[8];
        double[] coords = new double[24];
        int count = 0;

        void reset(long key) {
            this.key = key;
            this.count = 0;
        }

        int add(int entityId, double x, double y, double z) {
            if (count >= ids.length) {
                int newCap = ids.length * 2;
                int[] newIds = new int[newCap];
                System.arraycopy(ids, 0, newIds, 0, ids.length);
                ids = newIds;

                double[] newCoords = new double[newCap * 3];
                System.arraycopy(coords, 0, newCoords, 0, coords.length);
                coords = newCoords;
            }
            int idx = count++;
            ids[idx] = entityId;
            int base = idx * 3;
            coords[base] = x;
            coords[base + 1] = y;
            coords[base + 2] = z;
            return idx;
        }

        void updateCoords(int idx, double x, double y, double z) {
            int base = idx * 3;
            coords[base] = x;
            coords[base + 1] = y;
            coords[base + 2] = z;
        }

        /** Swaps removed element with tail in O(1); updates relocated tail's slot index. */
        void swapRemove(int idx, Int2ObjectOpenHashMap<PlayerSlot> byId) {
            int lastIdx = --count;
            if (idx != lastIdx) {
                int movedId = ids[lastIdx];
                ids[idx] = movedId;
                int dstBase = idx * 3;
                int srcBase = lastIdx * 3;
                coords[dstBase] = coords[srcBase];
                coords[dstBase + 1] = coords[srcBase + 1];
                coords[dstBase + 2] = coords[srcBase + 2];

                PlayerSlot movedSlot = byId.get(movedId);
                if (movedSlot != null) {
                    movedSlot.indexInBucket = idx;
                }
            }
        }
    }

    private final Int2ObjectOpenHashMap<PlayerSlot> playersById = new Int2ObjectOpenHashMap<>(32);
    private final Long2ObjectOpenHashMap<Bucket> buckets = new Long2ObjectOpenHashMap<>(64);
    private final List<Bucket> bucketPool = new ArrayList<>(64);
    private final List<PlayerSlot> slotPool = new ArrayList<>(64);
    private int bucketPoolCursor = 0;
    private int slotPoolCursor = 0;

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
        playersById.clear();
        bucketPoolCursor = 0;
        slotPoolCursor = 0;
    }

    /** Convenience method for sequential bulk insertions during beginFrame(). */
    public void addPlayer(int entityId, double x, double y, double z) {
        updatePlayer(entityId, lastWorldRef, x, y, z, false);
    }

    /**
     * Incrementally inserts, moves across buckets, or removes a player in O(1) time.
     * Safe for intra-tick teleports, knockback, negative coordinates, respawn, and dimension shifts.
     */
    public void updatePlayer(int entityId, Object worldRef, double x, double y, double z, boolean spectator) {
        if (lastWorldRef == null) {
            return;
        }
        // If player moved into another dimension or became spectator/inactive -> remove from active dimension grid
        if (worldRef != lastWorldRef || spectator) {
            removePlayer(entityId);
            return;
        }

        int rx = ((int) Math.floor(x)) >> REGION_SHIFT;
        int rz = ((int) Math.floor(z)) >> REGION_SHIFT;
        long targetKey = packKey(rx, rz);

        PlayerSlot slot = playersById.get(entityId);
        if (slot != null && slot.active) {
            if (slot.bucketKey == targetKey) {
                slot.bucket.updateCoords(slot.indexInBucket, x, y, z);
                return;
            }
            // Player crossed 32x32 bucket boundary -> O(1) detach & attach to new bucket
            slot.bucket.swapRemove(slot.indexInBucket, playersById);
            if (slot.bucket.count == 0) {
                buckets.remove(slot.bucketKey);
            }
        } else {
            slot = acquireSlot(entityId);
            playersById.put(entityId, slot);
        }

        Bucket targetBucket = getOrCreateBucket(targetKey);
        slot.bucketKey = targetKey;
        slot.bucket = targetBucket;
        slot.indexInBucket = targetBucket.add(entityId, x, y, z);
        slot.active = true;
    }

    /** Removes player on death, disconnect, or dimension departure in O(1). */
    public void removePlayer(int entityId) {
        PlayerSlot slot = playersById.remove(entityId);
        if (slot != null && slot.active && slot.bucket != null) {
            slot.bucket.swapRemove(slot.indexInBucket, playersById);
            if (slot.bucket.count == 0) {
                buckets.remove(slot.bucketKey);
            }
            slot.active = false;
            slot.bucket = null;
        }
    }

    public int getIndexedPlayerCount() {
        return playersById.size();
    }

    public boolean containsPlayer(int entityId) {
        PlayerSlot slot = playersById.get(entityId);
        return slot != null && slot.active;
    }

    /**
     * Exact squared-distance search over overlapping 32x32 buckets.
     * Supports negative coordinates via Math.floor() and returns immediately inside 16.0 blocks.
     */
    public double findNearestDistanceSq(Object worldRef, double mx, double my, double mz, double maxRadiusBlocks) {
        if (worldRef != lastWorldRef || playersById.isEmpty()) {
            return Double.MAX_VALUE;
        }

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
                            return minDistSq;
                        }
                    }
                }
            }
        }
        return minDistSq;
    }

    private Bucket getOrCreateBucket(long key) {
        Bucket bucket = buckets.get(key);
        if (bucket == null) {
            if (bucketPoolCursor < bucketPool.size()) {
                bucket = bucketPool.get(bucketPoolCursor++);
                bucket.reset(key);
            } else {
                bucket = new Bucket();
                bucket.reset(key);
                bucketPool.add(bucket);
                bucketPoolCursor++;
            }
            buckets.put(key, bucket);
        }
        return bucket;
    }

    private PlayerSlot acquireSlot(int entityId) {
        PlayerSlot slot;
        if (slotPoolCursor < slotPool.size()) {
            slot = slotPool.get(slotPoolCursor++);
        } else {
            slot = new PlayerSlot();
            slotPool.add(slot);
            slotPoolCursor++;
        }
        slot.entityId = entityId;
        slot.active = false;
        slot.bucket = null;
        return slot;
    }

    public void clearAll() {
        buckets.clear();
        playersById.clear();
        bucketPool.clear();
        slotPool.clear();
        bucketPoolCursor = 0;
        slotPoolCursor = 0;
        lastWorldRef = null;
        lastTickTime = Long.MIN_VALUE;
    }

    private static long packKey(int rx, int rz) {
        return (((long) rx) << 32) | (rz & 0xffffffffL);
    }
}
