package me.cortex.voxy.commonImpl.mixin.distanthorizons;

import java.util.ArrayList;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;

@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.api.internal.SharedApi", remap = false)
public class MixinSharedApi {
    @Inject(method = "queueChunkUpdate", at = @At(
        value = "NEW",
        target = "Lcom/seibel/distanthorizons/core/api/internal/chunkUpdating/ChunkUpdateData;"),
//        cancellable = true,
        remap = false
    )
    private static void beforeChunkUpdateCreation(
            Object chunkWrapper,
            ArrayList<?> neighborChunkList,
            Object dhLevel,
            boolean canGetNeighboringChunks,
            CallbackInfo ci
    ) {
        ChunkAccess chunkAccess = extractChunkAccess(chunkWrapper);
        if (chunkAccess == null) {
            return;
        }

        if (!(chunkAccess instanceof LevelChunk wc)) {
            Logger.error("DH MixinSharedApi: ChunkAccess is not LevelChunk: " + chunkAccess.getClass().getName());
            return;
        }

        // for (int x = 0; x < 16; x++) {
        //     System.out.println(wc.getBlockState(new BlockPos(x, 0, 0)).toString());
        // }

        if (VoxelIngestService.tryAutoIngestChunk(wc)) {
            // Logger.info("DH MixinSharedApi: Auto-ingest triggered for chunk: " + chunkWrapper.getChunkPos());
        } else {
            // Logger.info("DH MixinSharedApi: Auto-ingest NOT triggered for chunk: " + chunkWrapper.getChunkPos());
        }
        // ci.cancel();
    }

    private static ChunkAccess extractChunkAccess(Object chunkWrapper) {
        try {
            Method getChunk = chunkWrapper.getClass().getMethod("getChunk");
            Object chunk = getChunk.invoke(chunkWrapper);
            if (chunk instanceof ChunkAccess chunkAccess) {
                return chunkAccess;
            }

            Logger.error("DH MixinSharedApi: getChunk returned unexpected type: " + chunk.getClass().getName());
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            Logger.error("DH MixinSharedApi: Failed to inspect chunk wrapper " + chunkWrapper.getClass().getName(), e);
        }

        return null;
    }
}
