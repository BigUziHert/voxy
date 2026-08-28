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
import net.minecraft.core.SectionPos;
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

    static boolean isSnowy(int blockId, Mapper mapper, int index, VoxelizedSection section) {
        BlockState state = mapper.getBlockStateFromBlockId(blockId);
        if (MapChecker.getDefaultBlockTypeFlag(state) <= MapChecker.FLAG_NONE) {
            return false;//Not a block EclipticSeasons ever puts snow on
        }
        Level level = ClientCon.getUseLevel();
        if (level == null) {
            return false;
        }
        //Only ask about chunks EclipticSeasons has snow data for, otherwise it cannot answer and
        //the block would be ingested as bare while a later pass says otherwise
        if (!MapChecker.isLoaded(level, section.x, section.z)) {
            return false;
        }
        BlockPos pos = SectionPos.of(section.x, section.y, section.z).origin()
                .offset(index & 0xF, (index >> 8) & 0xF, (index >> 4) & 0xF);
        return EclipticSeasonsApi.getInstance().isSnowyBlock(level, state, pos);
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
