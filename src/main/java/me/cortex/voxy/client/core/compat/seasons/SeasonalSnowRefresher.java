package me.cortex.voxy.client.core.compat.seasons;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.compat.SeasonalSnowIds;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.common.world.other.Mipper;
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
 * so it keeps whichever season it was captured under. Walking the store is what reaches it.
 *
 * The decision is made at level 0 and nowhere else, and the levels above it are then rebuilt by
 * rerunning the mip. That is not an optimisation, it is the only correct reading of this data.
 * Mipper#mip returns one of its eight children verbatim, so a level 3 voxel is a level 0 block that
 * won a tournament, not a thing with a biome and a sky light of its own. Asking it those questions
 * gives answers about a block sixteen blocks away, or about empty sky the store never kept, which is
 * what stripped snow off the furthest terrain. Deciding at level 0 and following the mip upward makes
 * the levels agree with each other by construction, exactly as ingest already makes them agree.
 *
 * The write is deliberately narrow: only the 20 bit block id field, and only ever between a state and
 * its own complement. Light, biome and air-ness are never touched, so nothing structural changes and
 * the worst a bad decision can do is put snow in the wrong place.
 */
public final class SeasonalSnowRefresher {
    private SeasonalSnowRefresher() {}

    //Block ids live in bits 27..46 of a voxel, see Mapper#getBlockId
    private static final long BLOCK_ID_MASK = ((1L << 20) - 1) << 27;
    private static final int SECTION_WIDTH = 32;

    //Sky light the voxel above must exceed before snow can settle, matching the ingest side
    static final int MIN_SKY_LIGHT = 9;

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
    public static final int R_NO_SKY_LIGHT = 1;
    public static final int R_NO_BLOCK_LIGHT = 2;
    public static final int R_NO_NOT_A_SNOW_BLOCK = 3;
    public static final int R_NO_TREE_INTERIOR = 4;
    public static final int R_NO_BOTH_PASSABLE = 5;
    public static final int R_NO_BIOME_HAS_NO_SNOW = 6;
    //Split out of the one above by the reporting code only, never returned by explain
    public static final int R_NO_BIOME_DRY = 7;
    public static final int R_NO_LOST_THE_ROLL = 8;
    //Everything from here up is an unknown, see verdictOf
    public static final int R_UNKNOWN_ABOVE_UNRESOLVABLE = 9;
    public static final int R_UNKNOWN_BIOME_UNRESOLVABLE = 10;
    public static final int R_UNKNOWN_NO_WEATHER_DATA = 11;
    public static final int REASON_COUNT = 12;

    public static final String[] REASON_NAMES = {
            "snow",
            "no: too dark to be open sky",
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

    static long withBlockId(long voxel, int blockId) {
        return (voxel & ~BLOCK_ID_MASK) | ((blockId & ((1L << 20) - 1)) << 27);
    }

    //A section above that the store does not have is empty sky, not an unknown: voxy has no reason
    //to keep a section that is nothing but air. Reading its absence as darkness is what convinced
    //the walk that open ground was buried.
    private static final long OPEN_SKY = Mapper.airWithLight(0x0F);

    //How many sections between yields. The walk is deliberately one thread that gives a slice back
    //regularly rather than several that compete with render and ingest for the whole machine.
    private static final int SECTIONS_PER_BATCH = 128;
    private static final long BATCH_PAUSE_MILLIS = 8;

    private static final class Run {
        volatile boolean cancelled;
        //Captured on the client thread at start. A pass takes a while and re-reading the camera
        //mid walk would only reshuffle the far rings.
        int centreSectionX;
        int centreSectionZ;
    }

    //Control flow, not an error: no stack trace, no suppression
    private static final class WalkCancelled extends RuntimeException {
        WalkCancelled() {
            super(null, null, false, false);
        }
    }

    private static Thread worker;
    //Per run, so a cancel cannot stop the run that replaces it
    private static volatile Run active;
    private static volatile String status = "idle";
    public static volatile long lastPassEndMillis = 0;
    /** How long the last pass took, so the caller can space passes against their real cost. */
    public static volatile long lastPassDurationMillis = 0;

    /**
     * Sections holding nothing that any season could ever snow: no voxel that is both open to the
     * sky and a block EclipticSeasons snows, and none currently marked.
     *
     * That does not depend on the season. It depends on the terrain and its light, which only change
     * when the section is ingested again. So once a pass has established it, every later pass can
     * skip the section without reading it back off disk, and reading them back is what makes a pass
     * expensive: a store is overwhelmingly underground, and underground can never snow.
     *
     * Anything that ingests a section takes it back out of here, so a section that changes is looked
     * at again. Cleared outright when the world changes, or when the snow config a pass classified
     * against is no longer the one in force.
     */
    private static final Object BARREN_LOCK = new Object();
    private static final it.unimi.dsi.fastutil.longs.LongOpenHashSet barren =
            new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    private static WorldEngine barrenEngine = null;
    private static long barrenConfig = Long.MIN_VALUE;

    private static boolean isBarren(long key) {
        synchronized (BARREN_LOCK) {
            return barren.contains(key);
        }
    }

    private static void setBarren(long key) {
        synchronized (BARREN_LOCK) {
            barren.add(key);
        }
    }

    /** Called by ingest: this section has been rewritten, so whatever a pass concluded is stale. */
    public static void sectionIngested(int sectionX, int sectionY, int sectionZ) {
        synchronized (BARREN_LOCK) {
            if (barren.isEmpty()) {
                return;
            }
            barren.remove(WorldEngine.getWorldSectionId(0, sectionX >> 1, sectionY >> 1, sectionZ >> 1));
        }
    }

    public static int barrenCount() {
        synchronized (BARREN_LOCK) {
            return barren.size();
        }
    }

    private static void validateBarren(WorldEngine engine, long configToken) {
        synchronized (BARREN_LOCK) {
            if (barrenEngine != engine || barrenConfig != configToken) {
                barren.clear();
                barrenEngine = engine;
                barrenConfig = configToken;
            }
        }
    }

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
        Run run = new Run();
        var player = net.minecraft.client.Minecraft.getInstance().player;
        if (player != null) {
            run.centreSectionX = ((int) Math.floor(player.getX())) >> 5;
            run.centreSectionZ = ((int) Math.floor(player.getZ())) >> 5;
        }
        active = run;
        pendingDirty.clear();
        pendingSet.clear();
        dirtyEngine = engine;
        status = "starting";
        final long startedAt = System.currentTimeMillis();
        Thread t = new Thread(() -> run(level, engine, run, startedAt), "voxy-seasonal-snow-refresh");
        //Below render and ingest, but not so low that the OS never schedules it
        t.setPriority(Thread.NORM_PRIORITY - 2);
        t.setDaemon(true);
        worker = t;
        t.start();
        return null;
    }

    public static void cancel() {
        Run run = active;
        if (run != null) {
            run.cancelled = true;
        }
    }

    //Sections whose geometry needs rebuilding, handed to the renderer a few per tick. Marking all of
    //them as they are found buries the render pipeline in thousands of rebuild requests inside a few
    //seconds, which is what the stutter was: the scan itself is cheap. Saving is not queued, see run.
    private static final java.util.concurrent.ConcurrentLinkedQueue<Long> pendingDirty =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    //Parents are shared between many level 0 sections, so without this the queue would hold the same
    //position thousands of times and take minutes to drain past them
    private static final java.util.Set<Long> pendingSet = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static volatile WorldEngine dirtyEngine;

    private static final int DIRTY_PER_TICK = 32;

    private static void queueDirty(long pos) {
        if (pendingSet.add(pos)) {
            pendingDirty.add(pos);
        }
    }

    /**
     * Called from the client tick. Hands a bounded number of rebuilds to the renderer.
     *
     * Only the rebuild, never the save: the walk marks its own sections dirty as it edits them, so a
     * position that has since been evicted is nothing to worry about here. Its data went to disk and
     * comes back correct when it is next loaded. The queue is filled nearest first, so what you are
     * looking at is redrawn in the first seconds and the far tail can take as long as it likes.
     */
    public static void drainDirty() {
        var engine = dirtyEngine;
        if (engine == null || !engine.isLive()) {
            pendingDirty.clear();
            pendingSet.clear();
            return;
        }
        for (int i = 0; i < DIRTY_PER_TICK; i++) {
            Long pos = pendingDirty.poll();
            if (pos == null) {
                return;
            }
            pendingSet.remove(pos);
            var section = engine.acquireIfExists(pos);
            if (section == null) {
                continue;
            }
            try {
                //Only block ids changed, never air-ness, so no child existence update is needed
                engine.markDirty(section, WorldEngine.UPDATE_TYPE_BLOCK_BIT, 0);
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

    private static void run(Level level, WorldEngine engine, Run run, long startedAt) {
        long scanned = 0;
        long rewritten = 0;
        long flipped = 0;
        boolean referenced = false;
        try {
            //Hold the world open for the whole walk. Without this the idle check that precedes
            //teardown can sample a moment where nothing is acquired, free the engine, and the next
            //acquire here throws.
            engine.acquireRef();
            referenced = true;

            Mapper mapper = engine.getMapper();
            //Read once: these are per voxel reads otherwise and cannot change meaningfully in a pass
            boolean notSnowyNearGlow = SeasonalSnowHooks.cfgNotSnowyNearGlow();
            int glowLevel = SeasonalSnowHooks.cfgGlowLevel();
            boolean snowyTree = SeasonalSnowHooks.cfgSnowyTree();
            //What a section was classified barren against. Change any of it and the classification
            //has to be thrown away, since it is these that decide what can ever be snowed.
            validateBarren(engine, (notSnowyNearGlow ? 1L : 0) | ((long) glowLevel << 1)
                    | (snowyTree ? 1L << 8 : 0));

            //Level 0 only, see the class comment. Collect first: the storage iterator holds a cursor
            //open and acquiring sections underneath it is not something the backend promises to
            //survive, so the walk needs its own way out of the callback.
            LongArrayList keys = new LongArrayList();
            try {
                engine.storage.iteratePositions(0, key -> {
                    if (run.cancelled || !engine.isLive()) {
                        throw new WalkCancelled();
                    }
                    keys.add(key);
                });
            } catch (WalkCancelled e) {
                status = "cancelled while listing sections";
                finished.set("Seasonal snow refresh " + status);
                return;
            }
            if (keys.isEmpty()) {
                status = "done: nothing stored to refresh";
                finished.set("Seasonal snow refresh " + status);
                return;
            }

            //The storage iterator yields keys in packed order, which paints the refresh as
            //scanlines marching across the screen, because the order sections are marked dirty in is
            //the order they are rebuilt in. Nearest first makes the same work read as rings from
            //where you stand, and puts what you are actually looking at first.
            final int cx = run.centreSectionX;
            final int cz = run.centreSectionZ;
            keys.sort((long a, long b) -> {
                long adx = WorldEngine.getX(a) - cx, adz = WorldEngine.getZ(a) - cz;
                long bdx = WorldEngine.getX(b) - cx, bdz = WorldEngine.getZ(b) - cz;
                int byDist = Long.compare(adx * adx + adz * adz, bdx * bdx + bdz * bdz);
                if (byDist != 0) {
                    return byDist;
                }
                int byX = Integer.compare(WorldEngine.getX(a), WorldEngine.getX(b));
                if (byX != 0) {
                    return byX;
                }
                int byZ = Integer.compare(WorldEngine.getZ(a), WorldEngine.getZ(b));
                return byZ != 0 ? byZ : Integer.compare(WorldEngine.getY(a), WorldEngine.getY(b));
            });

            final int total = keys.size();
            status = "scanning " + total + " sections";

            //Workers pull indices off a shared cursor, so the nearest first order is still roughly
            //what gets done first while the pass uses more than one core. Safe where it matters: the
            //section tracker is built for concurrent access and ingest already reaches it from its
            //own pool, each worker keeps its own biome cache, and two level 0 sections never write
            //the same parent voxel because their footprints tile the parent without overlapping.
            final java.util.concurrent.atomic.AtomicInteger cursor =
                    new java.util.concurrent.atomic.AtomicInteger();
            final java.util.concurrent.atomic.AtomicLong flippedA =
                    new java.util.concurrent.atomic.AtomicLong();
            final java.util.concurrent.atomic.AtomicLong rewrittenA =
                    new java.util.concurrent.atomic.AtomicLong();
            final java.util.concurrent.atomic.AtomicLong scannedA =
                    new java.util.concurrent.atomic.AtomicLong();
            final java.util.concurrent.atomic.AtomicLong skippedA =
                    new java.util.concurrent.atomic.AtomicLong();
            final boolean fNotSnowyNearGlow = notSnowyNearGlow;
            final int fGlowLevel = glowLevel;
            final boolean fSnowyTree = snowyTree;

            int workers = Math.max(1, Math.min(3, Runtime.getRuntime().availableProcessors() / 4));
            Thread[] pool = new Thread[Math.min(workers, Math.max(1, total))];
            for (int w = 0; w < pool.length; w++) {
                pool[w] = new Thread(() -> {
                    BiomeCache biomes = new BiomeCache(level, mapper);
                    int i;
                    while ((i = cursor.getAndIncrement()) < total) {
                        if (run.cancelled || !engine.isLive()
                                || (engine.instanceIn != null && !engine.instanceIn.isRunning())) {
                            return;
                        }
                        long key = keys.getLong(i);
                        if (isBarren(key)) {
                            skippedA.incrementAndGet();
                            continue;//Never reads it, which is the whole point
                        }
                        WorldSection section = engine.acquireIfExists(key);
                        if (section != null) {
                            try {
                                long result = refreshSection(engine, level, mapper, biomes, section,
                                        fNotSnowyNearGlow, fGlowLevel, fSnowyTree);
                                int changed = (int) result;
                                if ((result & CANDIDATE_BIT) == 0) {
                                    //Nothing in it any season could snow, so no later pass needs it
                                    setBarren(key);
                                }
                                if (changed > 0) {
                                    flippedA.addAndGet(changed);
                                    rewrittenA.incrementAndGet();
                                    //Flagged for saving here and now, while this thread still holds
                                    //the section. Only the rebuild is queued, and that can be minutes
                                    //behind on a big store. A section the walk has edited but nothing
                                    //has marked dirty yet is dropped on eviction rather than written,
                                    //and the walk is what applies the pressure that evicts it.
                                    section.markDirty();
                                    queueDirty(key);
                                    remipIntoParents(engine, mapper, section);
                                }
                            } catch (Throwable t) {
                                Logger.error("Seasonal snow refresh failed on "
                                        + WorldEngine.pprintPos(key), t);
                            } finally {
                                //Every stored section passes through here exactly once and a pass
                                //visits far more of them than the cache holds, so keeping them would
                                //evict everything anyone else is using. A section this walk rewrote
                                //is unaffected: it is dirty, so it exits through the save queue and
                                //re-enters the cache from there, which is right, since the rewritten
                                //ones are the ones about to be re-meshed.
                                section.release(WorldSection.RELEASE_HINT_DONT_CACHE);
                            }
                        }
                        long done = scannedA.incrementAndGet();
                        if ((done % SECTIONS_PER_BATCH) == 0) {
                            status = describeProgress((int) (done + skippedA.get()), total,
                                    flippedA.get(), startedAt);
                            //Give a slice back regularly so a pass cannot sit on its cores throughout
                            try {
                                Thread.sleep(BATCH_PAUSE_MILLIS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                    }
                }, "voxy-seasonal-snow-refresh-" + w);
                pool[w].setDaemon(true);
                //Below render and ingest, but not so low that the OS never schedules it
                pool[w].setPriority(Thread.NORM_PRIORITY - 2);
                pool[w].start();
            }
            for (Thread t : pool) {
                t.join();
            }
            scanned = scannedA.get();
            rewritten = rewrittenA.get();
            flipped = flippedA.get();
            if (run.cancelled || !engine.isLive()) {
                status = "cancelled after " + flipped + " voxels, " + elapsed(startedAt);
                finished.set("Seasonal snow refresh " + status);
                return;
            }

            status = "done: " + flipped + " voxels over " + rewritten + " sections in "
                    + elapsed(startedAt)
                    + (skippedA.get() == 0 ? "" : ", " + skippedA.get() + " skipped as unsnowable")
                    + (pendingDirty.isEmpty() ? "" : ", " + pendingDirty.size() + " rebuilds still landing");
            Logger.info("Seasonal snow refresh " + status);
            finished.set("Seasonal snow refresh " + status);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            status = "interrupted after " + flipped + " voxels";
            finished.set("Seasonal snow refresh " + status);
        } catch (Throwable t) {
            status = "failed: " + t;
            finished.set("Seasonal snow refresh " + status);
            Logger.error("Seasonal snow refresh failed", t);
        } finally {
            lastPassEndMillis = System.currentTimeMillis();
            lastPassDurationMillis = lastPassEndMillis - startedAt;
            if (referenced) {
                try {
                    engine.releaseRef();
                } catch (RuntimeException ignored) {
                    //World already torn down, nothing left to hand back
                }
            }
            synchronized (SeasonalSnowRefresher.class) {
                if (worker == Thread.currentThread()) {
                    worker = null;
                    active = null;
                }
            }
        }
    }

    private static String describeProgress(int done, int total, long changed, long startedAt) {
        return Math.min(done, total) + "/" + total + " sections ("
                + (total == 0 ? 100 : (int) (100L * Math.min(done, total) / total)) + "%), "
                + changed + " voxels changed, " + elapsed(startedAt);
    }

    private static String elapsed(long startedAt) {
        long ms = System.currentTimeMillis() - startedAt;
        return ms < 1000 ? ms + "ms" : (ms / 1000) + "s";
    }

    //Set on the result when the section held at least one voxel a season could snow, so the caller
    //knows whether it is worth ever reading again. The low 32 bits are the change count.
    private static final long CANDIDATE_BIT = 1L << 32;

    /** @return the change count, with CANDIDATE_BIT set when anything here is ever snowable */
    private static long refreshSection(WorldEngine engine, Level level, Mapper mapper, BiomeCache biomes,
                                       WorldSection section, boolean notSnowyNearGlow, int glowLevel,
                                       boolean snowyTree) {
        long[] data = section._unsafeGetRawDataArray();
        if (data == null) {
            return 0;
        }
        int stateCount = mapper.getBlockStateCount();
        WorldSection above = null;
        boolean aboveResolved = false;
        long[] aboveData = null;
        int changed = 0;
        boolean candidate = false;

        try {
            for (int y = 0; y < SECTION_WIDTH; y++) {
                for (int z = 0; z < SECTION_WIDTH; z++) {
                    for (int x = 0; x < SECTION_WIDTH; x++) {
                        int idx = WorldSection.getIndex(x, y, z);
                        long voxel = data[idx];
                        int stored = Mapper.getBlockId(voxel);
                        if (stored == 0) {
                            continue;//Air
                        }
                        boolean storedSnowy = stored >= stateCount;
                        int base = storedSnowy ? SeasonalSnowIds.MAX_BLOCK_ID - stored : stored;
                        if (base <= 0 || base >= stateCount) {
                            continue;//An id this world cannot resolve, leave it alone
                        }
                        //Snow already on it is reason enough to come back: it may need taking off
                        candidate |= storedSnowy;

                        long aboveVoxel;
                        if (y + 1 < SECTION_WIDTH) {
                            aboveVoxel = data[WorldSection.getIndex(x, y + 1, z)];
                        } else {
                            if (!aboveResolved) {
                                aboveResolved = true;
                                above = engine.acquireIfExists(section.lvl, section.x,
                                        section.y + 1, section.z);
                                aboveData = above == null ? null : above._unsafeGetRawDataArray();
                            }
                            aboveVoxel = aboveData == null
                                    ? OPEN_SKY : aboveData[WorldSection.getIndex(x, 0, z)];
                        }

                        int aboveLight = Mapper.getLightId(aboveVoxel);
                        if (!SeasonalSnowHooks.lightAllowsSnow(aboveLight, notSnowyNearGlow, glowLevel)) {
                            //A definite no, so snow that is there has to come off. Air with not one
                            //bit of light recorded is the exception, and it is the same "not knowing
                            //is not knowing there is none" rule as everywhere else in here: a whole
                            //voxel of zero is what voxy writes for a chunk section it was handed no
                            //light layers for, and a fully lit air section above the surface is
                            //precisely the section the client keeps no layers for, so the air
                            //sitting on open ground reads back as pitch black. Ingest asks the light
                            //engine itself about that row and is right about it, and taking this as
                            //a no would strip the snow it had just put down.
                            if (storedSnowy && aboveVoxel != 0) {
                                //Re-read first for the same reason the write below does: ingest may
                                //have replaced this voxel, and rewriting it from the stale read
                                //would carry the old light and biome back in with it.
                                long dark = data[idx];
                                if (Mapper.getBlockId(dark) == stored) {
                                    data[idx] = withBlockId(dark, base);
                                    changed++;
                                }
                            }
                            continue;
                        }

                        BlockState state = mapper.getBlockStateFromBlockId(base);
                        if (state == null) {
                            continue;
                        }
                        Holder<Biome> biome = biomes.get(Mapper.getBiomeId(voxel));
                        BlockPos pos = new BlockPos(
                                (section.x << 5) + x, (section.y << 5) + y, (section.z << 5) + z);

                        int reason = SeasonalSnowHooks.explain(level, state, aboveLight,
                                SeasonalSnowHooks.aboveStateOf(mapper, aboveVoxel, stateCount), biome,
                                pos, notSnowyNearGlow, glowLevel, snowyTree);
                        //Open to the sky and a kind of block EclipticSeasons snows. Whether it snows
                        //today is the season's business, but that it could is the terrain's, and
                        //that is what makes this section worth reading again next time.
                        candidate |= reason != R_NO_NOT_A_SNOW_BLOCK;
                        int verdict = verdictOf(reason);
                        if (verdict == UNKNOWN) {
                            continue;
                        }
                        boolean wantSnowy = verdict == YES;
                        if (wantSnowy == storedSnowy) {
                            continue;
                        }

                        //Deciding took long enough for ingest to have replaced this voxel. Writing a
                        //value derived from the stale read would roll its work back, and it used the
                        //current season anyway, so only write if nothing moved.
                        long current = data[idx];
                        if (Mapper.getBlockId(current) != stored) {
                            continue;
                        }
                        data[idx] = withBlockId(current,
                                wantSnowy ? SeasonalSnowIds.mark(base) : base);
                        changed++;
                    }
                }
            }
        } finally {
            if (above != null) {
                //Kept, unlike the section itself: keys are sorted by distance and then by x, z, y,
                //so a column is walked bottom up with nothing in between, and this is the next key
                above.release();
            }
        }
        return (changed & 0xFFFFFFFFL) | (candidate ? CANDIDATE_BIT : 0);
    }

    //One set of pyramid buffers per walker thread: ~37KiB that a pass rewriting thousands of
    //sections would otherwise allocate for every one of them.
    private static final ThreadLocal<long[][]> MIP_SCRATCH = ThreadLocal.withInitial(() -> {
        long[][] levels = new long[WorldEngine.MAX_LOD_LAYER][];
        int side = SECTION_WIDTH;
        for (int lvl = 0; lvl < levels.length; lvl++) {
            side >>= 1;
            levels[lvl] = new long[side * side * side];
        }
        return levels;
    });

    /**
     * Brings the levels above this section back in line with the level 0 data just edited.
     *
     * Ingest decides snow at level 0 and mips upward, so a parent voxel shows snow exactly when the
     * child the mip picked did. That has to be recomputed rather than guessed at, so the same
     * Mipper the ingest pyramid uses is rerun here. Rebuilding from this section's own voxels is
     * self contained: a level 4 voxel spans 16 blocks, so every child of every parent covering these
     * 32 blocks is inside them.
     *
     * Only the snow bit is carried across. Where the recomputed voxel disagrees about anything else
     * the stored one is ingest's, and is left alone.
     */
    private static void remipIntoParents(WorldEngine engine, Mapper mapper, WorldSection section) {
        long[] data = section._unsafeGetRawDataArray();
        if (data == null) {
            return;
        }
        int stateCount = mapper.getBlockStateCount();
        long[][] scratch = MIP_SCRATCH.get();
        long[] cur = data;
        int curSide = SECTION_WIDTH;

        for (int lvl = 1; lvl <= WorldEngine.MAX_LOD_LAYER; lvl++) {
            int side = curSide >> 1;
            long[] next = scratch[lvl - 1];
            for (int y = 0; y < side; y++) {
                for (int z = 0; z < side; z++) {
                    for (int x = 0; x < side; x++) {
                        int cx = x << 1, cy = y << 1, cz = z << 1;
                        //Same child order as WorldVoxilizedSectionMipper, which is what built these
                        //levels in the first place
                        next[cubeIndex(x, y, z, side)] = Mipper.mip(
                                cur[cubeIndex(cx, cy, cz, curSide)],
                                cur[cubeIndex(cx + 1, cy, cz, curSide)],
                                cur[cubeIndex(cx, cy, cz + 1, curSide)],
                                cur[cubeIndex(cx + 1, cy, cz + 1, curSide)],
                                cur[cubeIndex(cx, cy + 1, cz, curSide)],
                                cur[cubeIndex(cx + 1, cy + 1, cz, curSide)],
                                cur[cubeIndex(cx, cy + 1, cz + 1, curSide)],
                                cur[cubeIndex(cx + 1, cy + 1, cz + 1, curSide)],
                                mapper);
                    }
                }
            }
            applyLevel(engine, section, lvl, next, side, stateCount);
            cur = next;
            curSide = side;
        }
    }

    private static void applyLevel(WorldEngine engine, WorldSection section, int lvl,
                                   long[] computed, int side, int stateCount) {
        WorldSection parent = engine.acquireIfExists(lvl,
                section.x >> lvl, section.y >> lvl, section.z >> lvl);
        if (parent == null) {
            return;
        }
        try {
            long[] pdata = parent._unsafeGetRawDataArray();
            if (pdata == null) {
                return;
            }
            //Where this section lands inside the parent: its 32 blocks are 32>>lvl of its voxels
            int ox = (section.x << (5 - lvl)) & 31;
            int oy = (section.y << (5 - lvl)) & 31;
            int oz = (section.z << (5 - lvl)) & 31;

            boolean touched = false;
            for (int y = 0; y < side; y++) {
                for (int z = 0; z < side; z++) {
                    for (int x = 0; x < side; x++) {
                        long want = computed[cubeIndex(x, y, z, side)];
                        int pidx = WorldSection.getIndex(ox + x, oy + y, oz + z);
                        long have = pdata[pidx];
                        if (want == have) {
                            continue;
                        }
                        int wantId = Mapper.getBlockId(want);
                        int haveId = Mapper.getBlockId(have);
                        boolean wantSnowy = wantId >= stateCount;
                        boolean haveSnowy = haveId >= stateCount;
                        int wantBase = wantSnowy ? SeasonalSnowIds.MAX_BLOCK_ID - wantId : wantId;
                        int haveBase = haveSnowy ? SeasonalSnowIds.MAX_BLOCK_ID - haveId : haveId;
                        //Disagreeing about which block this is means the mip picked differently than
                        //ingest did, which is not something a season change should be rewriting
                        if (wantBase != haveBase || wantSnowy == haveSnowy) {
                            continue;
                        }
                        pdata[pidx] = withBlockId(have, wantSnowy
                                ? SeasonalSnowIds.mark(haveBase) : haveBase);
                        touched = true;
                    }
                }
            }
            if (touched) {
                parent.markDirty();
                queueDirty(WorldEngine.getWorldSectionId(lvl,
                        section.x >> lvl, section.y >> lvl, section.z >> lvl));
            }
        } finally {
            parent.release();
        }
    }

    //Matches WorldSection.getIndex at side 32 and generalises it to the smaller mip cubes
    private static int cubeIndex(int x, int y, int z, int side) {
        return (y * side + z) * side + x;
    }

    /**
     * Biome holders, resolved once per id per pass. getBiomeEntries copies the whole table under a
     * lock and resolving a key hits the registry, so both are worth doing once, failures included.
     */
    static final class BiomeCache {
        private final Level level;
        private final Mapper.BiomeEntry[] entries;
        private final Holder<Biome>[] resolved;
        private final boolean[] known;

        @SuppressWarnings("unchecked")
        BiomeCache(Level level, Mapper mapper) {
            this.level = level;
            this.entries = mapper.getBiomeEntries();
            this.resolved = new Holder[Math.max(1, this.entries.length)];
            this.known = new boolean[this.resolved.length];
        }

        Holder<Biome> get(int biomeId) {
            if (biomeId < 0 || biomeId >= this.known.length) {
                return null;
            }
            if (this.known[biomeId]) {
                return this.resolved[biomeId];
            }
            this.known[biomeId] = true;
            try {
                if (biomeId >= this.entries.length || this.entries[biomeId] == null) {
                    return null;
                }
                this.resolved[biomeId] = this.level.registryAccess()
                        .registryOrThrow(Registries.BIOME)
                        .getHolder(ResourceKey.create(Registries.BIOME,
                                ResourceLocation.parse(this.entries[biomeId].biome)))
                        .orElse(null);
            } catch (Throwable t) {
                this.resolved[biomeId] = null;
            }
            return this.resolved[biomeId];
        }
    }

    // ---------------------------------------------------------------- debug reporting

    /**
     * Reports what one block column holds at every lod level, and what the decision makes of the
     * level 0 voxel. Only level 0 carries a decision, so the higher levels report what the mip left
     * there rather than a verdict of their own.
     */
    public static void probe(Level level, WorldEngine engine, BlockPos target, java.util.List<String> out) {
        Mapper mapper = engine.getMapper();
        int stateCount = mapper.getBlockStateCount();
        boolean notSnowyNearGlow = SeasonalSnowHooks.cfgNotSnowyNearGlow();
        int glowLevel = SeasonalSnowHooks.cfgGlowLevel();
        boolean snowyTree = SeasonalSnowHooks.cfgSnowyTree();
        BiomeCache biomes = new BiomeCache(level, mapper);

        for (int lvl = 0; lvl <= WorldEngine.MAX_LOD_LAYER; lvl++) {
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
                int biomeId = Mapper.getBiomeId(voxel);
                String biomeName = biomeId >= 0 && biomeId < mapper.getBiomeEntries().length
                        && mapper.getBiomeEntries()[biomeId] != null
                        ? mapper.getBiomeEntries()[biomeId].biome : ("#" + biomeId);

                if (base <= 0 || base >= stateCount) {
                    out.add(at + "air or an unreadable id (" + stored + ")");
                    continue;
                }
                if (lvl > 0) {
                    //No decision is made here, the mip is. Saying so is the point.
                    out.add(at + describeBlock(mapper, base, stateCount, marked) + " in " + biomeName
                            + ", mipped up from level 0");
                    continue;
                }

                above = engine.acquireIfExists(lvl, sx, sy + 1, sz);
                long[] aboveData = above == null ? null : above._unsafeGetRawDataArray();
                long aboveVoxel = vy < 31
                        ? data[WorldSection.getIndex(vx, vy + 1, vz)]
                        : (aboveData == null ? OPEN_SKY : aboveData[WorldSection.getIndex(vx, 0, vz)]);
                int lightAbove = Mapper.getLightId(aboveVoxel);

                BlockState state = mapper.getBlockStateFromBlockId(base);
                if (state == null) {
                    out.add(at + "id " + base + " is not a block in this world");
                    continue;
                }
                Holder<Biome> biome = biomes.get(biomeId);
                BlockPos pos = new BlockPos((sx << 5) + vx, (sy << 5) + vy, (sz << 5) + vz);
                int reason = SeasonalSnowHooks.explain(level, state, lightAbove,
                        SeasonalSnowHooks.aboveStateOf(mapper, aboveVoxel, stateCount), biome, pos,
                        notSnowyNearGlow, glowLevel, snowyTree);
                String roll = "";
                if (biome != null) {
                    int depth = SeasonalSnowHooks.snowDepthValue(level, biome.value());
                    if (reason == R_NO_BIOME_HAS_NO_SNOW) {
                        reason = depth <= 0 ? R_NO_BIOME_DRY : R_NO_LOST_THE_ROLL;
                    }
                    roll = ", depth " + depth + " vs roll " + Math.abs(state.getSeed(pos) % 100);
                }
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
     * Counts the reason every level 0 voxel around a position gets its verdict, and how many voxels
     * at each level are currently marked snowy. Answers what a single probe cannot: whether the
     * decision is saying no to a whole region, and on what grounds.
     */
    public static void sample(Level level, WorldEngine engine, BlockPos centre, int radius,
                              java.util.List<String> out) {
        Mapper mapper = engine.getMapper();
        int stateCount = mapper.getBlockStateCount();
        boolean notSnowyNearGlow = SeasonalSnowHooks.cfgNotSnowyNearGlow();
        int glowLevel = SeasonalSnowHooks.cfgGlowLevel();
        boolean snowyTree = SeasonalSnowHooks.cfgSnowyTree();
        BiomeCache biomes = new BiomeCache(level, mapper);
        int[] depthCache = new int[Math.max(1, mapper.getBiomeEntries().length)];
        java.util.Arrays.fill(depthCache, Integer.MIN_VALUE);

        long[] reasons = new long[REASON_COUNT];
        long snowNow = 0;
        long sections = 0;
        int cx = centre.getX() >> 5;
        int cy = centre.getY() >> 5;
        int cz = centre.getZ() >> 5;
        for (int sx = cx - radius; sx <= cx + radius; sx++) {
            for (int sz = cz - radius; sz <= cz + radius; sz++) {
                for (int sy = cy - 1; sy <= cy + 1; sy++) {
                    WorldSection section = engine.acquireIfExists(0, sx, sy, sz);
                    if (section == null) {
                        continue;
                    }
                    WorldSection above = engine.acquireIfExists(0, sx, sy + 1, sz);
                    try {
                        long[] data = section._unsafeGetRawDataArray();
                        long[] aboveData = above == null ? null : above._unsafeGetRawDataArray();
                        if (data == null) {
                            continue;
                        }
                        sections++;
                        for (int y = 0; y < SECTION_WIDTH; y++) {
                            for (int z = 0; z < SECTION_WIDTH; z++) {
                                for (int x = 0; x < SECTION_WIDTH; x++) {
                                    long voxel = data[WorldSection.getIndex(x, y, z)];
                                    int stored = Mapper.getBlockId(voxel);
                                    if (stored == 0) {
                                        continue;
                                    }
                                    boolean storedSnowy = stored >= stateCount;
                                    int base = storedSnowy
                                            ? SeasonalSnowIds.MAX_BLOCK_ID - stored : stored;
                                    if (base <= 0 || base >= stateCount) {
                                        continue;
                                    }
                                    if (storedSnowy) {
                                        snowNow++;
                                    }
                                    long aboveVoxel = y + 1 < SECTION_WIDTH
                                            ? data[WorldSection.getIndex(x, y + 1, z)]
                                            : (aboveData == null ? OPEN_SKY
                                               : aboveData[WorldSection.getIndex(x, 0, z)]);
                                    int aboveLight = Mapper.getLightId(aboveVoxel);
                                    if (!SeasonalSnowHooks.lightAllowsSnow(aboveLight,
                                            notSnowyNearGlow, glowLevel)) {
                                        reasons[(aboveLight & 0xF) <= MIN_SKY_LIGHT
                                                ? R_NO_SKY_LIGHT : R_NO_BLOCK_LIGHT]++;
                                        continue;
                                    }
                                    BlockState state = mapper.getBlockStateFromBlockId(base);
                                    if (state == null) {
                                        continue;
                                    }
                                    int biomeId = Mapper.getBiomeId(voxel);
                                    Holder<Biome> biome = biomes.get(biomeId);
                                    BlockPos pos = new BlockPos((sx << 5) + x, (sy << 5) + y,
                                            (sz << 5) + z);
                                    int reason = SeasonalSnowHooks.explain(level, state, aboveLight,
                                            SeasonalSnowHooks.aboveStateOf(mapper, aboveVoxel,
                                                    stateCount), biome, pos, notSnowyNearGlow,
                                            glowLevel, snowyTree);
                                    if (reason == R_NO_BIOME_HAS_NO_SNOW && biome != null) {
                                        if (biomeId >= 0 && biomeId < depthCache.length
                                                && depthCache[biomeId] == Integer.MIN_VALUE) {
                                            depthCache[biomeId] = SeasonalSnowHooks
                                                    .snowDepthValue(level, biome.value());
                                        }
                                        int depth = biomeId >= 0 && biomeId < depthCache.length
                                                ? depthCache[biomeId] : 0;
                                        reason = depth <= 0 ? R_NO_BIOME_DRY : R_NO_LOST_THE_ROLL;
                                    }
                                    reasons[reason]++;
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
            out.add("nothing stored at level 0 nearby");
            return;
        }
        //Being buried rejects the whole underground, so it would drown out everything else
        StringBuilder sb = new StringBuilder("level 0: " + sections + " sections, "
                + snowNow + " snowy now");
        for (int r = 0; r < REASON_COUNT; r++) {
            if (r == R_NO_SKY_LIGHT || reasons[r] == 0) {
                continue;
            }
            sb.append(", ").append(reasons[r]).append(' ').append(REASON_NAMES[r]);
        }
        out.add(sb.toString());
        out.add("levels 1 to 4 carry no decision of their own, they are mipped up from level 0");
    }
}
