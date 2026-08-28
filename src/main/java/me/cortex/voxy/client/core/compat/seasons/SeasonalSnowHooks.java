package me.cortex.voxy.client.core.compat.seasons;

import com.teamtea.eclipticseasons.api.EclipticSeasonsApi;
import com.teamtea.eclipticseasons.client.core.ExtraModelManager;
import com.teamtea.eclipticseasons.client.core.ExtraRendererContext;
import com.teamtea.eclipticseasons.client.util.ClientCon;
import com.teamtea.eclipticseasons.common.core.biome.WeatherManager;
import com.teamtea.eclipticseasons.common.core.map.MapChecker;
import me.cortex.voxy.client.core.model.bakery.ReuseVertexConsumer;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;

import java.util.List;

/**
 * The only class here that touches EclipticSeasons. It must stay that way: SeasonalSnow is
 * reachable from common ingest code, and loading it must not drag these types in when the mod
 * is not installed.
 */
final class SeasonalSnowHooks {
    private SeasonalSnowHooks() {}

    //The same order the bakery walks, with null last for the unculled quads
    private static final Direction[] FACES = {
            Direction.DOWN, Direction.UP, Direction.NORTH,
            Direction.SOUTH, Direction.WEST, Direction.EAST, null
    };

    //EclipticSeasons seeds its snow placement per block, and the bakery is position independent,
    //so the same constant seed vanilla uses for model randomisation is used throughout
    private static final long SEED = 42L;

    //Resolved once. EclipticSeasons' snow config is not something that changes mid session, and
    //reflecting per section during ingest would show up in chunk load times.
    private static volatile boolean cfgRead = false;
    private static boolean cfgGlow;
    private static int cfgGlowLvl;
    private static boolean cfgTree;

    private static void ensureConfig() {
        if (!cfgRead) {
            cfgGlow = cfgNotSnowyNearGlow();
            cfgGlowLvl = cfgGlowLevel();
            cfgTree = cfgSnowyTree();
            cfgRead = true;
        }
    }

    //Biome holders resolved per ingest thread, a registry lookup per voxel would be far too slow
    private static final ThreadLocal<Holder<Biome>[]> BIOME_CACHE = new ThreadLocal<>();
    private static final ThreadLocal<boolean[]> BIOME_TRIED = new ThreadLocal<>();

    /**
     * Decides snow over a freshly ingested 16^3 section, in place.
     *
     * Deliberately the same decision the refresher makes, rather than asking
     * EclipticSeasonsApi#isSnowyBlock. That api answers from EclipticSeasons' own client region
     * map, which is not populated for everything voxy ingests, and a false from it is not evidence
     * of no snow. Trusting it meant a re-ingest wrote bare terrain over ground a refresh had
     * correctly snowed, so flying around slowly stripped the world back.
     */
    static void markSection(long[] data, Mapper mapper, VoxelizedSection section) {
        Level level = ClientCon.getUseLevel();
        if (level == null) {
            return;
        }
        ensureConfig();

        int stateCount = mapper.getBlockStateCount();
        int biomeCount = Math.max(mapper.getBiomeEntries().length, 1);
        Holder<Biome>[] cache = BIOME_CACHE.get();
        boolean[] tried = BIOME_TRIED.get();
        if (cache == null || cache.length < biomeCount) {
            cache = new Holder[biomeCount];
            tried = new boolean[biomeCount];
            BIOME_CACHE.set(cache);
            BIOME_TRIED.set(tried);
        }

        //Index layout is (y<<8)|(z<<4)|x, so the voxel above is +256 and the top row has none
        for (int i = 0; i < 0xF00; i++) {
            long voxel = data[i];
            int blockId = Mapper.getBlockId(voxel);
            if (blockId <= 0 || blockId >= stateCount) {
                continue;//Air, or already marked, or not resolvable
            }
            //Reject on light before resolving anything: this runs during chunk load, and almost
            //every voxel in a section is underground
            if (!SeasonalSnowRefresher.lightAllowsSnow(data[i + 256], cfgGlow, cfgGlowLvl)) {
                continue;
            }

            BlockState state = mapper.getBlockStateFromBlockId(blockId);
            if (state == null) {
                continue;
            }

            int biomeId = Mapper.getBiomeId(voxel);
            Holder<Biome> biome = null;
            if (biomeId >= 0 && biomeId < cache.length) {
                if (!tried[biomeId]) {
                    tried[biomeId] = true;
                    try {
                        cache[biomeId] = level.registryAccess()
                                .registryOrThrow(Registries.BIOME)
                                .getHolder(ResourceKey.create(Registries.BIOME,
                                        ResourceLocation.parse(mapper.getBiomeEntries()[biomeId].biome)))
                                .orElse(null);
                    } catch (Throwable ignored) {
                        cache[biomeId] = null;
                    }
                }
                biome = cache[biomeId];
            }

            BlockPos pos = new BlockPos(
                    (section.x << 4) + (i & 0xF),
                    (section.y << 4) + ((i >> 8) & 0xF),
                    (section.z << 4) + ((i >> 4) & 0xF));

            int verdict = decide(level, mapper, state, data[i + 256], biome, pos, stateCount,
                    cfgGlow, cfgGlowLvl, cfgTree);
            if (verdict == SeasonalSnowRefresher.YES) {
                data[i] = SeasonalSnowRefresher.withBlockId(voxel,
                        me.cortex.voxy.common.compat.SeasonalSnowIds.mark(blockId));
            }
        }
    }

    static void renderSnowOverlay(BlockState state, RenderType layer, ReuseVertexConsumer translucentVC, ReuseVertexConsumer opaqueVC) {
        if (state.getRenderShape() == RenderShape.INVISIBLE) {
            return;
        }
        Level level = ClientCon.getUseLevel();
        if (level == null) {
            return;
        }

        int flag = MapChecker.getDefaultBlockTypeFlag(state);
        BakedModel model = ExtraModelManager.getSnowyModel(state, null, flag, MapChecker.getSnowOffset(state, flag));
        if (model == null) {
            return;
        }

        ExtraRendererContext context = new ExtraRendererContext()
                .setReplace(ExtraModelManager.isModelReplaceable(state, level, BlockPos.ZERO, model))
                .setExtraModel(model)
                .setOriginalModel(Minecraft.getInstance().getModelManager().getBlockModelShaper().getBlockModel(state));

        //Leaves keep the layer the bakery picked for them, everything else uses the layer
        //EclipticSeasons wants its snow drawn on
        RenderType type = state.getBlock() instanceof LeavesBlock ? layer : ExtraModelManager.getRenderType(state);
        boolean forceSolid = state.is(BlockTags.LEAVES);

        for (Direction face : FACES) {
            SingleThreadedRandomSource random = new SingleThreadedRandomSource(SEED);
            List<BakedQuad> quads = ExtraModelManager.cancelTop(context, model, level, state, BlockPos.ZERO,
                    face, random, SEED, model.getQuads(state, face, random), List.of());
            for (BakedQuad quad : quads) {
                (type == RenderType.translucent() ? translucentVC : opaqueVC).quad(quad, forceSolid, layer);
            }
        }
    }

    /**
     * Re-decides snow for a voxel already in the store, from stored data alone. Used by the
     * refresher, where the chunk is long gone and EclipticSeasons' own map has nothing to say
     * about somewhere the player has never been.
     *
     * Returns SeasonalSnowRefresher NO / YES / UNKNOWN. UNKNOWN means leave the voxel alone.
     */
    static int decide(Level level, Mapper mapper, BlockState state, long aboveVoxel,
                      Holder<Biome> biome, BlockPos pos, int stateCount,
                      boolean notSnowyNearGlow, int glowLevel, boolean snowyTree) {
        return SeasonalSnowRefresher.verdictOf(explain(level, mapper, state, aboveVoxel, biome, pos,
                stateCount, notSnowyNearGlow, glowLevel, snowyTree));
    }

    /** The decision itself, as a SeasonalSnowRefresher R_ reason. See decide for the verdict. */
    static int explain(Level level, Mapper mapper, BlockState state, long aboveVoxel,
                       Holder<Biome> biome, BlockPos pos, int stateCount,
                       boolean notSnowyNearGlow, int glowLevel, boolean snowyTree) {
        int light = Mapper.getLightId(aboveVoxel);
        if ((light & 0xF) <= SeasonalSnowRefresher.MIN_SKY_LIGHT) {
            return SeasonalSnowRefresher.R_NO_SKY_LIGHT;//Not open enough to the sky
        }
        if (notSnowyNearGlow && ((light >> 4) & 0xF) >= glowLevel) {
            return SeasonalSnowRefresher.R_NO_BLOCK_LIGHT;
        }

        int aboveStored = Mapper.getBlockId(aboveVoxel);
        int aboveBase = aboveStored >= stateCount
                ? me.cortex.voxy.common.compat.SeasonalSnowIds.MAX_BLOCK_ID - aboveStored
                : aboveStored;
        if (aboveBase < 0 || aboveBase >= stateCount) {
            return SeasonalSnowRefresher.R_UNKNOWN_ABOVE_UNRESOLVABLE;
        }
        BlockState aboveState = mapper.getBlockStateFromBlockId(aboveBase);
        if (aboveState == null) {
            return SeasonalSnowRefresher.R_UNKNOWN_ABOVE_UNRESOLVABLE;
        }

        int flag = MapChecker.getDefaultBlockTypeFlag(state);
        if (flag <= MapChecker.FLAG_NONE) {
            return SeasonalSnowRefresher.R_NO_NOT_A_SNOW_BLOCK;
        }
        if (MapChecker.leaveLike(flag)) {
            boolean specialLeaves = aboveState.is(state.getBlock())
                    && (Heightmap.Types.MOTION_BLOCKING_NO_LEAVES.isOpaque().test(aboveState)
                        || MapChecker.extraSnowPassable(aboveState));
            if (specialLeaves && !snowyTree) {
                return SeasonalSnowRefresher.R_NO_TREE_INTERIOR;
            }
        } else if (MapChecker.extraSnowPassable(state) && MapChecker.extraSnowPassable(aboveState)) {
            return SeasonalSnowRefresher.R_NO_BOTH_PASSABLE;
        }

        //A biome the store remembers but this world cannot resolve is not evidence of no snow
        if (biome == null) {
            return SeasonalSnowRefresher.R_UNKNOWN_BIOME_UNRESOLVABLE;
        }
        if (MapChecker.shouldSnowAtBiome(level, biome.value(), state, level.getRandom(),
                state.getSeed(pos), pos)) {
            return SeasonalSnowRefresher.R_YES;
        }
        //shouldSnowAtBiome is snowDepthAtBiome(biome) > |seed % 100|, and getSnowDepthAtBiome
        //answers 0 both for a biome with no snow and for a biome EclipticSeasons has no weather
        //for at all. Taking the second as a no is what stripped snow off distant biomes the client
        //had never been told about. Only trust a no when the weather is actually known.
        return WeatherManager.getBiomeWeather(level, biome.value()) == null
                ? SeasonalSnowRefresher.R_UNKNOWN_NO_WEATHER_DATA
                : SeasonalSnowRefresher.R_NO_BIOME_HAS_NO_SNOW;
    }

    //CommonConfig.Snow's fields are declared as ModConfigSpec.BooleanValue and IntValue, NeoForge
    //types that are not on a fabric compile classpath, so naming them here does not compile. They do
    //implement Supplier and IntSupplier, which is reachable reflectively. Read once per pass by the
    //caller: these cannot meaningfully change inside one, and this is far too slow per voxel.
    private static final String SNOW_CONFIG = "com.teamtea.eclipticseasons.config.CommonConfig$Snow";

    private static Object snowConfigValue(String field) throws Exception {
        return Class.forName(SNOW_CONFIG).getField(field).get(null);
    }

    static boolean cfgNotSnowyNearGlow() {
        try {
            if (snowConfigValue("notSnowyNearGlowingBlock") instanceof java.util.function.Supplier<?> s
                    && s.get() instanceof Boolean b) {
                return b;
            }
        } catch (Throwable ignored) {}
        return true;//EclipticSeasons' own default
    }

    static int cfgGlowLevel() {
        try {
            Object v = snowConfigValue("notSnowyNearGlowingBlockLevel");
            if (v instanceof java.util.function.IntSupplier s) {
                return s.getAsInt();
            }
            if (v instanceof java.util.function.Supplier<?> s && s.get() instanceof Number n) {
                return n.intValue();
            }
        } catch (Throwable ignored) {}
        return 10;//EclipticSeasons' own default
    }

    static boolean cfgSnowyTree() {
        try {
            if (snowConfigValue("snowyTree") instanceof java.util.function.Supplier<?> s
                    && s.get() instanceof Boolean b) {
                return b;
            }
        } catch (Throwable ignored) {}
        return true;//EclipticSeasons' own default
    }

    /**
     * Identifies the current snow situation, for spotting a change. Never compared for anything else.
     *
     * The solar term alone is not enough. Snow depth per biome falls gradually as it melts, and the
     * decision reads that depth, so a single pass on the term change jumps straight to the end state
     * while the world near you is still melting. Quantising the depths puts a handful of steps in
     * that curve instead, so the distance follows the melt down rather than beating it to the bottom.
     */
    static Object snowStateToken(Level level) {
        int hash = ClientCon.nowSolarTerm == null ? 0 : ClientCon.nowSolarTerm.hashCode();
        try {
            var biomes = WeatherManager.getBiomeList(level);
            if (biomes != null) {
                for (var weather : biomes) {
                    //Coarse: a step per ten percent of depth, so a full melt is a handful of passes
                    hash = hash * 31 + (weather == null ? 0 : weather.getSnowDepth() / 10);
                }
            }
        } catch (Throwable ignored) {
            //Fall back to the solar term alone
        }
        return hash;
    }

    /** The biome's snow depth, or -1 when EclipticSeasons has no weather for it. See snowDepthOf. */
    static int snowDepthValue(Level level, Biome biome) {
        try {
            Object weather = WeatherManager.getBiomeWeather(level, biome);
            if (weather == null) {
                return -1;
            }
            Object depth = weather.getClass().getMethod("getSnowDepth").invoke(weather);
            return depth instanceof Number n ? n.intValue() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Reflective so a version of EclipticSeasons that renamed it degrades to "?" rather than failing. */
    private static String snowDepthOf(Level level, Biome biome) {
        try {
            Object weather = WeatherManager.getBiomeWeather(level, biome);
            if (weather == null) {
                return "no weather data";
            }
            return String.valueOf(weather.getClass().getMethod("getSnowDepth").invoke(weather));
        } catch (Throwable t) {
            return "?";
        }
    }

    /** What EclipticSeasons itself says about the season, the config, and the biomes in the store. */
    static void describeState(Level level, Mapper mapper, List<String> out) {
        out.add("season: " + ClientCon.nowSolarTerm);
        out.add("config: snowyTree=" + cfgSnowyTree()
                + ", notSnowyNearGlowingBlock=" + cfgNotSnowyNearGlow()
                + " at light " + cfgGlowLevel()
                + ", min sky light " + (SeasonalSnowRefresher.MIN_SKY_LIGHT + 1));

        var entries = mapper.getBiomeEntries();
        int known = 0;
        int unknown = 0;
        int unresolved = 0;
        var detail = new java.util.ArrayList<String>();
        for (int i = 0; i < entries.length; i++) {
            if (entries[i] == null) {
                continue;
            }
            Holder<Biome> holder;
            try {
                holder = level.registryAccess().registryOrThrow(Registries.BIOME)
                        .getHolder(ResourceKey.create(Registries.BIOME,
                                ResourceLocation.parse(entries[i].biome)))
                        .orElse(null);
            } catch (Throwable t) {
                holder = null;
            }
            if (holder == null) {
                unresolved++;
                detail.add("  " + entries[i].biome + ": not in this world");
                continue;
            }
            String depth = snowDepthOf(level, holder.value());
            if (depth.equals("no weather data")) {
                unknown++;
            } else {
                known++;
            }
            detail.add("  " + entries[i].biome + ": snow depth " + depth);
        }
        out.add("biomes in the store: " + known + " with weather, " + unknown
                + " with none, " + unresolved + " unresolvable");
        //Every biome, in full: the point of this command is spotting the one that is different
        out.addAll(detail);
    }

    /** What EclipticSeasons would answer for the real block at pos, for comparing against the lod. */
    static void describeVanilla(Level level, BlockPos pos, List<String> out) {
        try {
            //Same as the lod side: the height asked for is a hint. Drop to the surface when it is air
            if (level.getBlockState(pos).isAir()) {
                int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ()) - 1;
                if (surface > level.getMinBuildHeight()) {
                    pos = new BlockPos(pos.getX(), surface, pos.getZ());
                    out.add("(nothing at that height, reading the surface at y=" + surface + " instead)");
                } else {
                    out.add("the real block here: the chunk is not loaded, so EclipticSeasons cannot be asked");
                    return;
                }
            }
            BlockState state = level.getBlockState(pos);
            Holder<Biome> biome = level.getBiome(pos);
            String name = biome.unwrapKey().map(k -> k.location().toString()).orElse("?");
            long seed = state.getSeed(pos);
            out.add("the real block here: " + state.getBlock().builtInRegistryHolder().key().location()
                    + " in " + name);
            out.add("  snow depth " + snowDepthOf(level, biome.value())
                    + " vs this block's roll " + Math.abs(seed % 100)
                    + ", type flag " + MapChecker.getDefaultBlockTypeFlag(state));
            out.add("  EclipticSeasons says " + (MapChecker.shouldSnowAtBiome(level, biome.value(),
                    state, level.getRandom(), seed, pos) ? "snow" : "no snow"));
            out.add("  api isSnowyBlock: " + EclipticSeasonsApi.getInstance().isSnowyBlock(level, state, pos));
            out.add("  surface here is y=" + (level.getHeight(Heightmap.Types.MOTION_BLOCKING,
                    pos.getX(), pos.getZ()) - 1) + ", sky light above "
                    + level.getBrightness(net.minecraft.world.level.LightLayer.SKY, pos.above()));
        } catch (Throwable t) {
            out.add("the real block here: could not be read (" + t + ")");
        }
    }
}
