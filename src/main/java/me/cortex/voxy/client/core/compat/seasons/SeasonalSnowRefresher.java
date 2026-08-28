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

    //Verdicts. UNKNOWN is not "no", it means leave the voxel exactly as it is: a biome the store
    //remembers but this world cannot resolve is not evidence of no snow, and stripping it would
    //persist and could only be undone by a re-ingest.
    static final int NO = 0;
    static final int YES = 1;
    static final int UNKNOWN = 2;

    //Why a voxel got the verdict it did. Only the debug command reads these, but the decision is
    //made in terms of them so that what the command reports is the decision itself rather than a
    //second copy of it that can drift.
    public static final int R_YES = 0;
    public static final int R_NO_COVERED = 1;
    public static final int R_NO_BLOCK_LIGHT = 2;
    public static final int R_NO_NOT_A_SNOW_BLOCK = 3;
    public static final int R_NO_TREE_INTERIOR = 4;
    public static final int R_NO_BOTH_PASSABLE = 5;
    public static final int R_NO_BIOME_HAS_NO_SNOW = 6;
    //Split out of the one above by the reporting code only, never returned by explain: telling them
    //apart costs a snow depth lookup, and the answer is the same either way to everything but a
    //person reading it. A biome at depth 68 snows 68 percent of positions and leaves the rest bare
    //by design, which is a very different thing from a biome with no snow in it at all.
    public static final int R_NO_BIOME_DRY = 7;
    public static final int R_NO_LOST_THE_ROLL = 8;
    //Everything from here up is an unknown, see verdictOf
    public static final int R_UNKNOWN_ABOVE_UNRESOLVABLE = 9;
    public static final int R_UNKNOWN_BIOME_UNRESOLVABLE = 10;
    public static final int R_UNKNOWN_NO_WEATHER_DATA = 11;
    public static final int REASON_COUNT = 12;

    public static final String[] REASON_NAMES = {
            "snow",
            "no: something above it blocks the sky",
            "no: too close to a glowing block",
            "no: not a block EclipticSeasons snows",
            "no: inside a tree, and snowy trees are off",
            "no: block and the one above are both passable",
            "no: this biome has no snow right now",
            "no: this biome has no snow at all right now",
            "no: this spot lost the dice roll in a partly snowy biome",
            "unknown: the block above is not in this world",
            "unknown: the biome is not in this world",
            "unknown: EclipticSeasons has no weather for this biome",
    };

    static int verdictOf(int reason) {
        if (reason == R_YES) {
            return YES;
        }
        return reason >= R_UNKNOWN_ABOVE_UNRESOLVABLE ? UNKNOWN : NO;
    }

    /**
     * Whether a voxel is open to the sky, which is the test that rejects almost everything and so
     * runs before a block state, a biome or a position is resolved.
     *
     * Decided from geometry, not from the stored sky light, and that distinction is the whole point.
     * Sky light on an air voxel reads zero both for somewhere genuinely buried and for somewhere the
     * store simply has no data, and empty sky above the surface is exactly the second: voxy has no
     * reason to keep a section that is nothing but air, so the mip above the ground reads as pitch
     * dark. Believing it stripped snow off the terrain furthest away, where the voxel above spans
     * sixteen blocks and lands in that empty sky. Nothing solid above it in the column we can see is
     * the same question asked of data that is actually there.
     */
    static boolean glowAllowsSnow(long aboveVoxel, boolean notSnowyNearGlow, int glowLevel) {
        return !(notSnowyNearGlow && ((Mapper.getLightId(aboveVoxel) >> 4) & 0xF) >= glowLevel);
    }

    /** True when every voxel above this one, in the column and section given, is air. */
    private static boolean nothingSolidAbove(long[] data, int x, int fromY, int z) {
        for (int y = 31; y > fromY; y--) {
            if (!Mapper.isAir(data[WorldSection.getIndex(x, y, z)])) {
                return false;
            }
        }
        return true;
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
        pendingDirty.clear();
        dirtyEngine = engine;
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

    //A whole store is only tens of seconds of work, so this is deliberately restrained. Taking
    //half the cores made the game stutter for no gain that anyone was waiting on.
    private static int workerCount() {
        return Math.max(1, Math.min(3, Runtime.getRuntime().availableProcessors() / 4));
    }

    //Sections whose geometry needs rebuilding, handed to the renderer a few per tick.
    //Marking all of them as they are found buries the render pipeline in thousands of rebuild
    //requests inside a few seconds, which is what the stutter was: the scan itself is cheap.
    private static final java.util.concurrent.ConcurrentLinkedQueue<Long> pendingDirty =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static volatile WorldEngine dirtyEngine;

    //About 640 sections a second, so a full store spreads over roughly fifteen seconds instead of
    //arriving all at once. Slower than the scan, which is fine, the terrain just fills in.
    private static final int DIRTY_PER_TICK = 32;

    /**
     * Called from the client tick. Hands a bounded number of rebuilds to the renderer.
     *
     * Only the rebuild, never the save: the walk marks its own sections dirty as it edits them, so a
     * position that has since been evicted is nothing to worry about here. Its data went to disk and
     * comes back correct when it is next loaded.
     */
    public static void drainDirty() {
        var engine = dirtyEngine;
        if (engine == null || !engine.isLive()) {
            pendingDirty.clear();
            return;
        }
        for (int i = 0; i < DIRTY_PER_TICK; i++) {
            Long pos = pendingDirty.poll();
            if (pos == null) {
                return;
            }
            var section = engine.acquireIfExists(pos);
            if (section == null) {
                continue;
            }
            try {
                engine.markDirty(section);
            } catch (Throwable t) {
                Logger.error("Failed to mark a refreshed section dirty", t);
            } finally {
                section.release();
            }
        }
    }

    public static int pendingRebuilds() {
        return pendingDirty.size();
    }

    //A finished walk says so in chat rather than making you poll status for it. Set on the walk's
    //own thread and taken on the client tick.
    private static final java.util.concurrent.atomic.AtomicReference<String> finished =
            new java.util.concurrent.atomic.AtomicReference<>();

    public static String takeFinishedMessage() {
        return finished.getAndSet(null);
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
                                    //Flagged for saving here and now, while this thread still holds
                                    //the section. Only the rebuild notification is queued, and that
                                    //can be minutes behind on a big store. A section the walk has
                                    //edited but nothing has marked dirty yet is dropped on eviction
                                    //rather than written, so the edit is simply lost, and the walk
                                    //itself is what applies the eviction pressure that loses it.
                                    section.markDirty();
                                    pendingDirty.add(pos);
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
                    finished.set("Seasonal snow refresh " + status);
                    return;
                }
            }
            status = "done: " + changed.get() + " voxels over " + touched.get()
                    + " sections in " + elapsed(startedAt)
                    + (pendingDirty.isEmpty() ? "" : ", " + pendingDirty.size() + " rebuilds still landing");
            Logger.info("Seasonal snow refresh " + status);
            finished.set("Seasonal snow refresh " + status);
        } catch (Throwable t) {
            status = "failed: " + t;
            finished.set("Seasonal snow refresh " + status);
            Logger.error("Seasonal snow refresh failed", t);
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

        //Column at a time, from the top down, so openness to the sky falls out of the walk itself
        for (int z = 0; z < 32; z++) {
            for (int x = 0; x < 32; x++) {
                //A missing section above is empty sky, not an unknown: voxy has no reason to store
                //one that is nothing but air, so its absence is what open sky looks like here
                boolean covered = aboveData != null && !nothingSolidAbove(aboveData, x, -1, z);
                long aboveVoxel = aboveData == null ? 0 : aboveData[WorldSection.getIndex(x, 0, z)];

                for (int y = 31; y >= 0; y--) {
                    int idx = WorldSection.getIndex(x, y, z);
                    long voxel = data[idx];

                    int stored = Mapper.getBlockId(voxel);
                    int base = stored >= stateCount ? SeasonalSnowIds.MAX_BLOCK_ID - stored : stored;
                    if (base > 0 && base < stateCount) {
                        changed += decideVoxel(level, mapper, section, data, idx, voxel, stored, base,
                                aboveVoxel, !covered, x, y, z, lvl, stateCount, biomeCache, biomeTried,
                                notSnowyNearGlow, glowLevel, snowyTree);
                    }

                    if (!Mapper.isAir(voxel)) {
                        covered = true;//For everything below it, not for itself
                    }
                    aboveVoxel = voxel;
                }
            }
        }
        return changed;
    }

    /** Returns 1 when the voxel was rewritten, 0 otherwise. */
    private static int decideVoxel(Level level, Mapper mapper, WorldSection section, long[] data,
                                   int idx, long voxel, int stored, int base, long aboveVoxel,
                                   boolean openToSky, int x, int y, int z, int lvl, int stateCount,
                                   Holder<Biome>[] biomeCache, boolean[] biomeTried,
                                   boolean notSnowyNearGlow, int glowLevel, boolean snowyTree) {
        boolean has = stored != base;
        if (!openToSky || !glowAllowsSnow(aboveVoxel, notSnowyNearGlow, glowLevel)) {
            //A definite no, not an unknown, so snow that is there has to come off
            if (has) {
                data[idx] = withBlockId(voxel, base);
                return 1;
            }
            return 0;
        }

        BlockState state = mapper.getBlockStateFromBlockId(base);
        if (state == null) {
            return 0;
        }
        Holder<Biome> biome = biome(level, mapper, Mapper.getBiomeId(voxel), biomeCache, biomeTried);
        BlockPos pos = new BlockPos(
                ((section.x << 5) + x) << lvl,
                ((section.y << 5) + y) << lvl,
                ((section.z << 5) + z) << lvl);

        int verdict = SeasonalSnowHooks.decide(level, mapper, state, aboveVoxel, true, biome, pos,
                stateCount, notSnowyNearGlow, glowLevel, snowyTree);
        if (verdict == UNKNOWN) {
            return 0;
        }
        boolean want = verdict == YES;
        if (want == has) {
            return 0;
        }
        data[idx] = withBlockId(voxel, want ? SeasonalSnowIds.mark(base) : base);
        return 1;
    }

    /**
     * Reports, for one block column position, what every lod level in the store currently holds and
     * what the snow decision makes of it. Written for the debug command: guessing at why a patch of
     * distant ground is bare has been wrong often enough that reading the actual inputs is cheaper.
     */
    public static void probe(Level level, WorldEngine engine, BlockPos target, java.util.List<String> out) {
        Mapper mapper = engine.getMapper();
        int stateCount = mapper.getBlockStateCount();
        boolean notSnowyNearGlow = SeasonalSnowHooks.cfgNotSnowyNearGlow();
        int glowLevel = SeasonalSnowHooks.cfgGlowLevel();
        boolean snowyTree = SeasonalSnowHooks.cfgSnowyTree();
        Holder<Biome>[] cache = new Holder[Math.max(mapper.getBiomeEntries().length, 1)];
        boolean[] tried = new boolean[cache.length];

        for (int lvl = 0; lvl <= WorldEngine.MAX_LOD_LAYER; lvl++) {
            //The height asked for is a hint, not the answer. Nobody reads an exact ground y off F3
            //while flying, and every level here has a different idea of where the ground is anyway,
            //so each one finds its own topmost solid voxel at or below that height.
            int groundY = solidAtOrBelow(engine, lvl, target.getX(), target.getY(), target.getZ());
            if (groundY == Integer.MIN_VALUE) {
                out.add("lod " + lvl + ": nothing solid stored in this column below y=" + target.getY());
                continue;
            }
            int sx = target.getX() >> (5 + lvl);
            int sy = groundY >> (5 + lvl);
            int sz = target.getZ() >> (5 + lvl);
            int vx = (target.getX() >> lvl) & 31;
            int vy = (groundY >> lvl) & 31;
            int vz = (target.getZ() >> lvl) & 31;
            String at = "lod " + lvl + " (y=" + groundY + "): ";

            WorldSection section = engine.acquireIfExists(WorldEngine.getWorldSectionId(lvl, sx, sy, sz));
            if (section == null) {
                out.add(at + "nothing stored here");
                continue;
            }
            WorldSection above = null;
            try {
                long[] data = section._unsafeGetRawDataArray();
                if (data == null) {
                    out.add(at + "section is empty");
                    continue;
                }
                long voxel = data[WorldSection.getIndex(vx, vy, vz)];
                int stored = Mapper.getBlockId(voxel);
                int base = stored >= stateCount ? SeasonalSnowIds.MAX_BLOCK_ID - stored : stored;
                boolean marked = stored != base;

                above = engine.acquireIfExists(WorldEngine.getWorldSectionId(lvl, sx, sy + 1, sz));
                long[] aboveData = above == null ? null : above._unsafeGetRawDataArray();
                long aboveVoxel = vy < 31
                        ? data[WorldSection.getIndex(vx, vy + 1, vz)]
                        : (aboveData == null ? 0 : aboveData[WorldSection.getIndex(vx, 0, vz)]);

                int lightAbove = Mapper.getLightId(aboveVoxel);
                int biomeId = Mapper.getBiomeId(voxel);
                String biomeName = biomeId >= 0 && biomeId < mapper.getBiomeEntries().length
                        && mapper.getBiomeEntries()[biomeId] != null
                        ? mapper.getBiomeEntries()[biomeId].biome : ("#" + biomeId);

                if (base <= 0 || base >= stateCount) {
                    out.add(at + "air or an unreadable id (" + stored + ")");
                    continue;
                }
                BlockState state = mapper.getBlockStateFromBlockId(base);
                if (state == null) {
                    out.add(at + "id " + base + " is not a block in this world");
                    continue;
                }

                Holder<Biome> biome = biome(level, mapper, biomeId, cache, tried);
                BlockPos pos = new BlockPos(
                        ((sx << 5) + vx) << lvl, ((sy << 5) + vy) << lvl, ((sz << 5) + vz) << lvl);
                boolean openToSky = nothingSolidAbove(data, vx, vy, vz)
                        && (aboveData == null || nothingSolidAbove(aboveData, vx, -1, vz));
                int reason = SeasonalSnowHooks.explain(level, mapper, state, aboveVoxel, openToSky,
                        biome, pos, stateCount, notSnowyNearGlow, glowLevel, snowyTree);
                if (reason == R_NO_BIOME_HAS_NO_SNOW) {
                    reason = SeasonalSnowHooks.snowDepthValue(level, biome.value()) <= 0
                            ? R_NO_BIOME_DRY : R_NO_LOST_THE_ROLL;
                }

                //The roll is what makes a partly snowy biome patchy, and at these levels one voxel
                //stands for many blocks, so it is worth seeing per level rather than once
                String roll = biome == null ? ""
                        : ", depth " + SeasonalSnowHooks.snowDepthValue(level, biome.value())
                          + " vs roll " + Math.abs(state.getSeed(pos) % 100);
                out.add(at + describeBlock(mapper, base, stateCount, marked)
                        + " in " + biomeName
                        + ", sky " + (lightAbove & 0xF) + " block " + ((lightAbove >> 4) & 0xF)
                        + " above" + roll + " -> " + REASON_NAMES[reason]);
            } finally {
                if (above != null) {
                    above.release();
                }
                section.release();
            }
        }
    }

    /**
     * The topmost non air voxel in this column at or below a height, at one lod level. A height read
     * off F3 is whatever the player happened to be standing or flying at, and the ground under it is
     * what the question is actually about.
     */
    private static int solidAtOrBelow(WorldEngine engine, int lvl, int bx, int by, int bz) {
        int step = 1 << lvl;
        int sx = bx >> (5 + lvl);
        int sz = bz >> (5 + lvl);
        int vx = (bx >> lvl) & 31;
        int vz = (bz >> lvl) & 31;
        int heldSy = Integer.MIN_VALUE;
        WorldSection held = null;
        try {
            for (int y = by; y > by - 640; y -= step) {
                int sy = y >> (5 + lvl);
                if (sy != heldSy) {
                    if (held != null) {
                        held.release();
                    }
                    held = engine.acquireIfExists(WorldEngine.getWorldSectionId(lvl, sx, sy, sz));
                    heldSy = sy;
                }
                if (held == null) {
                    continue;
                }
                long[] data = held._unsafeGetRawDataArray();
                if (data == null) {
                    continue;
                }
                if (!Mapper.isAir(data[WorldSection.getIndex(vx, (y >> lvl) & 31, vz)])) {
                    return y;
                }
            }
        } finally {
            if (held != null) {
                held.release();
            }
        }
        return Integer.MIN_VALUE;
    }

    private static String describeBlock(Mapper mapper, int base, int stateCount, boolean marked) {
        BlockState state = base > 0 && base < stateCount ? mapper.getBlockStateFromBlockId(base) : null;
        String name = state == null ? ("id " + base)
                : state.getBlock().builtInRegistryHolder().key().location().toString();
        return (marked ? "snowy " : "bare ") + name;
    }

    /**
     * Counts the reason every voxel gets its verdict, over the sections around a position. Answers
     * the question a single probe cannot: whether the decision is saying no to a whole region, and
     * on what grounds.
     */
    public static void sample(Level level, WorldEngine engine, BlockPos centre, int radius,
                              java.util.List<String> out) {
        Mapper mapper = engine.getMapper();
        int stateCount = mapper.getBlockStateCount();
        boolean notSnowyNearGlow = SeasonalSnowHooks.cfgNotSnowyNearGlow();
        int glowLevel = SeasonalSnowHooks.cfgGlowLevel();
        boolean snowyTree = SeasonalSnowHooks.cfgSnowyTree();
        Holder<Biome>[] cache = new Holder[Math.max(mapper.getBiomeEntries().length, 1)];
        boolean[] tried = new boolean[cache.length];
        int[] depthCache = new int[cache.length];
        java.util.Arrays.fill(depthCache, Integer.MIN_VALUE);

        for (int lvl = 0; lvl <= WorldEngine.MAX_LOD_LAYER; lvl++) {
            long[] reasons = new long[REASON_COUNT];
            long snowNow = 0;
            long sections = 0;
            int cx = centre.getX() >> (5 + lvl);
            int cy = centre.getY() >> (5 + lvl);
            int cz = centre.getZ() >> (5 + lvl);
            for (int sx = cx - radius; sx <= cx + radius; sx++) {
                for (int sz = cz - radius; sz <= cz + radius; sz++) {
                    for (int sy = cy - 1; sy <= cy + 1; sy++) {
                        WorldSection section = engine.acquireIfExists(WorldEngine.getWorldSectionId(lvl, sx, sy, sz));
                        if (section == null) {
                            continue;
                        }
                        WorldSection above = engine.acquireIfExists(
                                WorldEngine.getWorldSectionId(lvl, sx, sy + 1, sz));
                        try {
                            long[] data = section._unsafeGetRawDataArray();
                            long[] aboveData = above == null ? null : above._unsafeGetRawDataArray();
                            if (data == null) {
                                continue;
                            }
                            sections++;
                            for (int z = 0; z < 32; z++) {
                                for (int x = 0; x < 32; x++) {
                                    boolean covered = aboveData != null
                                            && !nothingSolidAbove(aboveData, x, -1, z);
                                    long aboveVoxel = aboveData == null
                                            ? 0 : aboveData[WorldSection.getIndex(x, 0, z)];
                                    for (int y = 31; y >= 0; y--) {
                                        long voxel = data[WorldSection.getIndex(x, y, z)];
                                        int stored = Mapper.getBlockId(voxel);
                                        int base = stored >= stateCount
                                                ? SeasonalSnowIds.MAX_BLOCK_ID - stored : stored;
                                        boolean readable = base > 0 && base < stateCount;
                                        if (readable) {
                                            if (stored != base) {
                                                snowNow++;
                                            }
                                            reasons[reasonFor(level, mapper, voxel, base, aboveVoxel,
                                                    !covered, sx, sy, sz, x, y, z, lvl, stateCount,
                                                    cache, tried, depthCache, notSnowyNearGlow,
                                                    glowLevel, snowyTree)]++;
                                        }
                                        if (!Mapper.isAir(voxel)) {
                                            covered = true;
                                        }
                                        aboveVoxel = voxel;
                                    }
                                }
                            }
                        } finally {
                            if (above != null) {
                                above.release();
                            }
                            section.release();
                        }
                    }
                }
            }
            if (sections == 0) {
                out.add("lod " + lvl + ": nothing stored nearby");
                continue;
            }
            //Being buried rejects the whole underground, so it would drown out everything else
            StringBuilder sb = new StringBuilder("lod " + lvl + ": " + sections + " sections, "
                    + snowNow + " snowy now");
            for (int r = 0; r < REASON_COUNT; r++) {
                if (r == R_NO_COVERED || reasons[r] == 0) {
                    continue;
                }
                sb.append(", ").append(reasons[r]).append(' ').append(REASON_NAMES[r]);
            }
            out.add(sb.toString());
        }
    }

    private static int reasonFor(Level level, Mapper mapper, long voxel, int base, long aboveVoxel,
                                 boolean openToSky, int sx, int sy, int sz, int x, int y, int z,
                                 int lvl, int stateCount, Holder<Biome>[] cache, boolean[] tried,
                                 int[] depthCache, boolean notSnowyNearGlow, int glowLevel,
                                 boolean snowyTree) {
        if (!openToSky) {
            return R_NO_COVERED;
        }
        BlockState state = mapper.getBlockStateFromBlockId(base);
        if (state == null) {
            return R_UNKNOWN_ABOVE_UNRESOLVABLE;
        }
        int biomeId = Mapper.getBiomeId(voxel);
        Holder<Biome> biome = biome(level, mapper, biomeId, cache, tried);
        BlockPos pos = new BlockPos(((sx << 5) + x) << lvl, ((sy << 5) + y) << lvl, ((sz << 5) + z) << lvl);
        int reason = SeasonalSnowHooks.explain(level, mapper, state, aboveVoxel, true, biome, pos,
                stateCount, notSnowyNearGlow, glowLevel, snowyTree);
        if (reason == R_NO_BIOME_HAS_NO_SNOW) {
            return depthOf(level, biome, biomeId, depthCache) <= 0 ? R_NO_BIOME_DRY : R_NO_LOST_THE_ROLL;
        }
        return reason;
    }

    private static int depthOf(Level level, Holder<Biome> biome, int biomeId, int[] cache) {
        if (biome == null || biomeId < 0 || biomeId >= cache.length) {
            return -1;
        }
        if (cache[biomeId] == Integer.MIN_VALUE) {
            cache[biomeId] = SeasonalSnowHooks.snowDepthValue(level, biome.value());
        }
        return cache[biomeId];
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
