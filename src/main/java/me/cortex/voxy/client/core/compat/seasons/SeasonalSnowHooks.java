package me.cortex.voxy.client.core.compat.seasons;

import com.teamtea.eclipticseasons.api.EclipticSeasonsApi;
import com.teamtea.eclipticseasons.client.core.ExtraModelManager;
import com.teamtea.eclipticseasons.client.core.ExtraRendererContext;
import com.teamtea.eclipticseasons.client.util.ClientCon;
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
                data[i] = (voxel & ~(((1L << 20) - 1) << 27))
                        | ((me.cortex.voxy.common.compat.SeasonalSnowIds.mark(blockId) & ((1L << 20) - 1)) << 27);
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
        int light = Mapper.getLightId(aboveVoxel);
        if ((light & 0xF) <= SeasonalSnowRefresher.MIN_SKY_LIGHT) {
            return SeasonalSnowRefresher.NO;//Not open enough to the sky
        }
        if (notSnowyNearGlow && ((light >> 4) & 0xF) >= glowLevel) {
            return SeasonalSnowRefresher.NO;
        }

        int aboveStored = Mapper.getBlockId(aboveVoxel);
        int aboveBase = aboveStored >= stateCount
                ? me.cortex.voxy.common.compat.SeasonalSnowIds.MAX_BLOCK_ID - aboveStored
                : aboveStored;
        if (aboveBase < 0 || aboveBase >= stateCount) {
            return SeasonalSnowRefresher.UNKNOWN;
        }
        BlockState aboveState = mapper.getBlockStateFromBlockId(aboveBase);
        if (aboveState == null) {
            return SeasonalSnowRefresher.UNKNOWN;
        }

        int flag = MapChecker.getDefaultBlockTypeFlag(state);
        if (flag <= MapChecker.FLAG_NONE) {
            return SeasonalSnowRefresher.NO;//Never a block EclipticSeasons snows
        }
        if (MapChecker.leaveLike(flag)) {
            boolean specialLeaves = aboveState.is(state.getBlock())
                    && (Heightmap.Types.MOTION_BLOCKING_NO_LEAVES.isOpaque().test(aboveState)
                        || MapChecker.extraSnowPassable(aboveState));
            if (specialLeaves && !snowyTree) {
                return SeasonalSnowRefresher.NO;
            }
        } else if (MapChecker.extraSnowPassable(state) && MapChecker.extraSnowPassable(aboveState)) {
            return SeasonalSnowRefresher.NO;
        }

        //A biome the store remembers but this world cannot resolve is not evidence of no snow
        if (biome == null) {
            return SeasonalSnowRefresher.UNKNOWN;
        }
        return MapChecker.shouldSnowAtBiome(level, biome.value(), state, level.getRandom(),
                state.getSeed(pos), pos) ? SeasonalSnowRefresher.YES : SeasonalSnowRefresher.NO;
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

    /** Identifies the current season, for spotting a change. Never compared for anything else. */
    static Object seasonToken() {
        return ClientCon.nowSolarTerm;
    }
}
