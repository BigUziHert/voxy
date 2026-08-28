package me.cortex.voxy.client.core.compat.seasons;

import com.teamtea.eclipticseasons.api.EclipticSeasonsApi;
import com.teamtea.eclipticseasons.client.core.ExtraModelManager;
import com.teamtea.eclipticseasons.client.core.ExtraRendererContext;
import com.teamtea.eclipticseasons.client.util.ClientCon;
import com.teamtea.eclipticseasons.common.core.map.MapChecker;
import me.cortex.voxy.client.core.model.bakery.ReuseVertexConsumer;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.other.Mapper;
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
}
