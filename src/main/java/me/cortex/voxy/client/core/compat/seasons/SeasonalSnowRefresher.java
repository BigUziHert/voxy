package me.cortex.voxy.client.core.compat.seasons;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.compat.SeasonalSnowIds;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Re-decides seasonal snow over lod that is already in the store.
 *
 * Snow is not a property of the model. It is decided at ingest, where a snowed block is written as
 * the complement of its own id, and that sits in the voxel itself. Distant lod is never re-ingested,
 * so it keeps whichever season it was captured under. Walking the store is what reaches it, and the
 * decision can be remade entirely from what is already stored: the biome, the sky light, and the
 * voxel above.
 *
 * Every level is walked and decided on its own terms rather than deciding level 0 and re-mipping
 * upward. A level 3 voxel can then disagree slightly with the level 0 blocks under it at an lod
 * boundary, which is the price of not hand rolling cross section mip propagation that this fork has
 * no api for and that would corrupt distant terrain if it were wrong.
 *
 * The write is deliberately narrow: only the 20 bit block id field, and only ever between a state
 * and its own complement. Light, biome and air-ness are never touched, so the worst a bad decision
 * can do is put snow in the wrong place.
 */
public final class SeasonalSnowRefresher {
    private SeasonalSnowRefresher() {}

    //Block ids live in bits 27..46 of a voxel, see Mapper#getBlockId
    private static final long BLOCK_ID_MASK = ((1L << 20) - 1) << 27;

    //Sky light the voxel above must exceed before snow can settle, matching the ingest side
    static final int MIN_SKY_LIGHT = 9;

    //Verdicts. UNKNOWN is not "no", it means leave the voxel exactly as it is: a biome the store
    //remembers but this world cannot resolve is not evidence of no snow, and stripping it would
    //persist and could only be undone by a re-ingest.
    static final int NO = 0;
    static final int YES = 1;
    static final int UNKNOWN = 2;

    /**
     * The cheapest part of the snow decision, and the one that rejects almost everything: anything
     * not open to the sky. Split out so the caller can run it before resolving a block state, a
     * biome and a position, which is otherwise a billion pointless allocations underground.
     */
    static boolean lightAllowsSnow(long aboveVoxel, boolean notSnowyNearGlow, int glowLevel) {
        int light = Mapper.getLightId(aboveVoxel);
        if ((light & 0xF) <= MIN_SKY_LIGHT) {
            return false;
        }
        return !(notSnowyNearGlow && ((light >> 4) & 0xF) >= glowLevel);
    }

    static long withBlockId(long voxel, int blockId) {
        return (voxel & ~BLOCK_ID_MASK) | ((blockId & ((1L << 20) - 1)) << 27);
    }

    private static Thread worker;
    private static volatile boolean cancelled;
    private static volatile String status = "idle";

    public static synchronized boolean isRunning() {
        return worker != null && worker.isAlive();
    }

    public static String describe() {
        return status;
    }

    /** Returns null when started, or a reason it could not be. */
    public static synchronized String start(Level level, WorldEngine engine) {
        if (!SeasonalSnow.enabled()) {
            return "seasonal snow on lods is off";
        }
        if (isRunning()) {
            return "a refresh is already running (" + status + ")";
        }
        if (level == null || engine == null || !engine.isLive()) {
            return "no live voxy world";
        }
        cancelled = false;
        status = "starting";
        final long startedAt = System.currentTimeMillis();
        Thread t = new Thread(() -> run(level, engine, startedAt), "voxy-seasonal-snow-refresh");
        //The coordinator just hands out work, the scanning threads it starts carry the priority
        t.setPriority(Thread.NORM_PRIORITY - 2);
        t.setDaemon(true);
        worker = t;
        t.start();
        return null;
    }

    public static void cancel() {
        cancelled = true;
    }

    //Enough to use the idle cores without competing with render and ingest for all of them
    private static int workerCount() {
        return Math.max(1, Math.min(6, Runtime.getRuntime().availableProcessors() / 2));
    }

    private static void run(Level level, WorldEngine engine, long startedAt) {
        var changed = new java.util.concurrent.atomic.AtomicLong();
        var visited = new java.util.concurrent.atomic.AtomicLong();
        var touched = new java.util.concurrent.atomic.AtomicLong();
        try {
            Mapper mapper = engine.getMapper();
            //Read once: these are per voxel reads otherwise, and they cannot change meaningfully
            //inside a single pass
            boolean notSnowyNearGlow = SeasonalSnowHooks.cfgNotSnowyNearGlow();
            int glowLevel = SeasonalSnowHooks.cfgGlowLevel();
            boolean snowyTree = SeasonalSnowHooks.cfgSnowyTree();

            //Highest level first. Distant terrain renders from the top levels and there are far
            //fewer of those sections, so what you are actually looking at updates in seconds
            //instead of after the whole of level 0 has been ground through.
            for (int lvl = WorldEngine.MAX_LOD_LAYER; lvl >= 0; lvl--) {
                //Collect first: the storage iterator holds a cursor open and acquiring sections
                //underneath it is not something the backend promises to survive
                LongArrayList positions = new LongArrayList();
                engine.storage.iteratePositions(lvl, positions::add);
                if (positions.isEmpty()) {
                    continue;
                }

                //Sections are independent, and the section tracker is built for concurrent access
                //(striped StampedLocks), which is how ingest already reaches it from its own pool.
                //Only the dirty notification is serialised, see markDirtySafely.
                final int level0 = lvl;
                final var cursor = new java.util.concurrent.atomic.AtomicInteger();
                final var total = positions.size();
                int workers = Math.min(workerCount(), total);
                Thread[] pool = new Thread[workers];
                for (int w = 0; w < workers; w++) {
                    pool[w] = new Thread(() -> {
                        //Per worker, a shared one would need locking on every voxel
                        Holder<Biome>[] biomeCache = new Holder[Math.max(mapper.getBiomeEntries().length, 1)];
                        boolean[] biomeTried = new boolean[biomeCache.length];
                        int i;
                        while ((i = cursor.getAndIncrement()) < total) {
                            if (cancelled || !engine.isLive()) {
                                return;
                            }
                            long pos = positions.getLong(i);
                            WorldSection section = engine.acquireIfExists(pos);
                            if (section == null) {
                                continue;
                            }
                            WorldSection above = engine.acquireIfExists(WorldEngine.getWorldSectionId(
                                    level0, WorldEngine.getX(pos), WorldEngine.getY(pos) + 1, WorldEngine.getZ(pos)));
                            try {
                                int n = process(level, mapper, section, above, biomeCache, biomeTried,
                                        notSnowyNearGlow, glowLevel, snowyTree);
                                if (n > 0) {
                                    markDirtySafely(engine, section);
                                    changed.addAndGet(n);
                                    touched.incrementAndGet();
                                }
                            } catch (Throwable t) {
                                Logger.error("Seasonal snow refresh failed on " + WorldEngine.pprintPos(pos), t);
                            } finally {
                                if (above != null) {
                                    above.release();
                                }
                                section.release();
                            }
                            long v = visited.incrementAndGet();
                            if ((v & 0xFF) == 0) {
                                status = describeProgress(level0, cursor.get(), total, changed.get(), startedAt);
                            }
                        }
                    }, "voxy-seasonal-snow-refresh-" + lvl + "-" + w);
                    //Below render and ingest, but not so low that the OS never schedules it
                    pool[w].setPriority(Thread.NORM_PRIORITY - 2);
                    pool[w].setDaemon(true);
                    pool[w].start();
                }
                for (Thread t : pool) {
                    t.join();
                }
                if (cancelled || !engine.isLive()) {
                    status = "cancelled after " + changed.get() + " voxels, " + elapsed(startedAt);
                    return;
                }
            }
            status = "done: " + changed.get() + " voxels over " + touched.get()
                    + " sections in " + elapsed(startedAt);
            Logger.info("Seasonal snow refresh " + status);
        } catch (Throwable t) {
            status = "failed: " + t;
            Logger.error("Seasonal snow refresh failed", t);
        }
    }

    //GeometryCache and the update router make no thread safety promises, and this fires once per
    //changed section rather than per voxel, so serialising it costs nothing measurable
    private static final Object DIRTY_LOCK = new Object();

    private static void markDirtySafely(WorldEngine engine, WorldSection section) {
        synchronized (DIRTY_LOCK) {
            engine.markDirty(section);
        }
    }

    private static String describeProgress(int lvl, int done, int total, long changed, long startedAt) {
        return "level " + lvl + ", " + Math.min(done, total) + "/" + total + " sections ("
                + (total == 0 ? 100 : (int) (100L * Math.min(done, total) / total)) + "%), "
                + changed + " voxels changed, " + elapsed(startedAt);
    }

    private static String elapsed(long startedAt) {
        long ms = System.currentTimeMillis() - startedAt;
        return ms < 1000 ? ms + "ms" : (ms / 1000) + "s";
    }

    private static int process(Level level, Mapper mapper, WorldSection section, WorldSection above,
                               Holder<Biome>[] biomeCache, boolean[] biomeTried,
                               boolean notSnowyNearGlow, int glowLevel, boolean snowyTree) {
        long[] data = section._unsafeGetRawDataArray();
        long[] aboveData = above == null ? null : above._unsafeGetRawDataArray();
        if (data == null) {
            return 0;
        }
        int stateCount = mapper.getBlockStateCount();
        int lvl = section.lvl;
        int changed = 0;

        for (int y = 0; y < 32; y++) {
            for (int z = 0; z < 32; z++) {
                for (int x = 0; x < 32; x++) {
                    int idx = WorldSection.getIndex(x, y, z);
                    long voxel = data[idx];

                    int stored = Mapper.getBlockId(voxel);
                    int base = stored >= stateCount ? SeasonalSnowIds.MAX_BLOCK_ID - stored : stored;
                    if (base <= 0 || base >= stateCount) {
                        continue;//Air, or an id this world cannot resolve
                    }

                    long aboveVoxel;
                    if (y < 31) {
                        aboveVoxel = data[WorldSection.getIndex(x, y + 1, z)];
                    } else if (aboveData != null) {
                        aboveVoxel = aboveData[WorldSection.getIndex(x, 0, z)];
                    } else {
                        continue;//Nothing above to judge by, leave it alone
                    }

                    if (!lightAllowsSnow(aboveVoxel, notSnowyNearGlow, glowLevel)) {
                        //A definite no, not an unknown, so snow that is there has to come off
                        if (stored != base) {
                            data[idx] = withBlockId(voxel, base);
                            changed++;
                        }
                        continue;
                    }

                    BlockState state = mapper.getBlockStateFromBlockId(base);
                    if (state == null) {
                        continue;
                    }

                    Holder<Biome> biome = biome(level, mapper, Mapper.getBiomeId(voxel), biomeCache, biomeTried);
                    BlockPos pos = new BlockPos(
                            ((section.x << 5) + x) << lvl,
                            ((section.y << 5) + y) << lvl,
                            ((section.z << 5) + z) << lvl);

                    int verdict = SeasonalSnowHooks.decide(level, mapper, state, aboveVoxel, biome, pos,
                            stateCount, notSnowyNearGlow, glowLevel, snowyTree);
                    if (verdict == UNKNOWN) {
                        continue;
                    }

                    boolean want = verdict == YES;
                    boolean has = stored != base;
                    if (want == has) {
                        continue;
                    }

                    data[idx] = withBlockId(voxel, want ? SeasonalSnowIds.mark(base) : base);
                    changed++;
                }
            }
        }
        return changed;
    }

    private static Holder<Biome> biome(Level level, Mapper mapper, int biomeId,
                                       Holder<Biome>[] cache, boolean[] tried) {
        if (biomeId < 0 || biomeId >= cache.length) {
            return null;
        }
        if (tried[biomeId]) {
            return cache[biomeId];
        }
        tried[biomeId] = true;
        try {
            String name = mapper.getBiomeEntries()[biomeId].biome;
            ResourceLocation id = ResourceLocation.parse(name);
            cache[biomeId] = level.registryAccess()
                    .registryOrThrow(Registries.BIOME)
                    .getHolder(ResourceKey.create(Registries.BIOME, id))
                    .orElse(null);
        } catch (Throwable t) {
            cache[biomeId] = null;
        }
        return cache[biomeId];
    }
}
