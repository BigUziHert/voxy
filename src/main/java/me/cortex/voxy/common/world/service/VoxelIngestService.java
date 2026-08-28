package me.cortex.voxy.common.world.service;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.Service;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.voxelization.IAboveSectionData;
import me.cortex.voxy.common.voxelization.ILightingSupplier;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldConversionFactory;
import me.cortex.voxy.common.voxelization.WorldVoxilizedSectionMipper;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldUpdater;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ConcurrentLinkedDeque;

public class VoxelIngestService {
    private static final ThreadLocal<VoxelizedSection> SECTION_CACHE = ThreadLocal.withInitial(VoxelizedSection::createEmpty);
    private final Service service;
    //aboveSection is the chunk section directly on top of this one, or null when there is none
    //because this is the top of the column. aboveLight is the light one block above this section's
    //top row, one packed byte per column, already resolved on the main thread; null only when
    //nobody could ask the light engine, and then the top row is left undecided.
    private record IngestSection(int cx, int cy, int cz, WorldEngine world, LevelChunkSection section,
                                 DataLayer blockLight, DataLayer skyLight,
                                 LevelChunkSection aboveSection, byte[] aboveLight){
        IngestSection(int cx, int cy, int cz, WorldEngine world, LevelChunkSection section,
                      DataLayer blockLight, DataLayer skyLight) {
            this(cx, cy, cz, world, section, blockLight, skyLight, null, null);
        }
    }

    /**
     * A lighting supplier that can also answer for the row above the section, so the top row of a
     * section can be decided rather than skipped. The above light is resolved before the task is
     * queued, because only the main thread may ask the light engine and the copied light layers
     * cannot answer for a section the client keeps no layer for.
     */
    private record AboveAwareLighting(ILightingSupplier base, LevelChunkSection aboveSection,
                                      byte[] aboveLight)
            implements ILightingSupplier, IAboveSectionData {
        @Override
        public byte supply(int x, int y, int z) {
            return this.base.supply(x, y, z);
        }

        @Override
        public byte lightAbove(int x, int z) {
            return this.aboveLight[(z << 4) | x];
        }

        @Override
        public BlockState stateAbove(int x, int z) {
            return this.aboveSection == null ? Blocks.AIR.defaultBlockState()
                    : this.aboveSection.getBlockState(x, 0, z);
        }
    }
    private final ConcurrentLinkedDeque<IngestSection> ingestQueue = new ConcurrentLinkedDeque<>();

    public VoxelIngestService(ServiceManager pool) {
        this.service = pool.createServiceNoCleanup(()->this::processJob, 5000, "Ingest service");
    }

    private void processJob() {
        var task = this.ingestQueue.pop();
        task.world.markActive();

        var section = task.section;
        var vs = SECTION_CACHE.get().setPosition(task.cx, task.cy, task.cz);

        if (section.hasOnlyAir() && task.blockLight==null && task.skyLight==null) {//If the chunk section has lighting data, propagate it
            WorldUpdater.insertUpdate(task.world, vs.zero());
        } else {
            VoxelizedSection csec = WorldConversionFactory.convert(
                    vs,
                    task.world.getMapper(),
                    section.getStates(),
                    section.getBiomes(),
                    getLightingSupplier(task)
            );
            WorldVoxilizedSectionMipper.mipSection(csec, task.world.getMapper());
            WorldUpdater.insertUpdate(task.world, csec);
        }
    }

    @NotNull
    private static ILightingSupplier getLightingSupplier(IngestSection task) {
        ILightingSupplier supplier = (x,y,z) -> (byte) 0;
        var sla = task.skyLight;
        var bla = task.blockLight;
        //A layer with no backing array is not a layer with nothing to say: it is uniform, and it
        //carries the value it is uniform at. get() answers correctly for it, and the synthesised
        //layer that puts real sky light on the air above the ground is exactly that shape, so
        //skipping empty layers here threw that answer away.
        boolean sl = sla != null;
        boolean bl = bla != null;
        if (sl || bl) {
            if (sl && bl) {
                supplier = (x,y,z)-> {
                    int block = Math.min(15,bla.get(x, y, z));
                    int sky = Math.min(15,sla.get(x, y, z));
                    return (byte) (sky|(block<<4));
                };
            } else if (bl) {
                supplier = (x,y,z)-> {
                    int block = Math.min(15,bla.get(x, y, z));
                    int sky = 0;
                    return (byte) (sky|(block<<4));
                };
            } else {
                supplier = (x,y,z)-> {
                    int block = 0;
                    int sky = Math.min(15,sla.get(x, y, z));
                    return (byte) (sky|(block<<4));
                };
            }
        }
        if (task.aboveLight != null) {
            return new AboveAwareLighting(supplier, task.aboveSection, task.aboveLight);
        }
        return supplier;
    }

    private static boolean shouldIngestSection(LevelChunkSection section, int cx, int cy, int cz) {
        return true;
    }

    public boolean enqueueIngest(WorldEngine engine, LevelChunk chunk) {
        if (!this.service.isLive()) {
            return false;
        }
        if (!engine.isLive()) {
            throw new IllegalStateException("Tried inserting chunk into WorldEngine that was not alive");
        }

        engine.markActive();

        var lightingProvider = chunk.getLevel().getLightEngine();
        boolean gotLighting = false;

        int i = chunk.getMinSection() - 1;
        boolean allEmpty = true;
        for (var section : chunk.getSections()) {
            i++;
            if (section == null || !shouldIngestSection(section, chunk.getPos().x, i, chunk.getPos().z)) continue;
            allEmpty&=section.hasOnlyAir();
            //if (section.isEmpty()) continue;
            var pos = SectionPos.of(chunk.getPos(), i);
            if (lightingProvider.getDebugSectionType(LightLayer.SKY, pos) != LayerLightSectionStorage.SectionType.LIGHT_AND_DATA && lightingProvider.getDebugSectionType(LightLayer.BLOCK, pos) != LayerLightSectionStorage.SectionType.LIGHT_AND_DATA)
                continue;
            gotLighting = true;
        }

        if (allEmpty&&!gotLighting) {
            //Special case all empty chunk columns, we need to clear it out
            i = chunk.getMinSection() - 1;
            for (var section : chunk.getSections()) {
                i++;
                if (section == null || !shouldIngestSection(section, chunk.getPos().x, i, chunk.getPos().z)) continue;
                engine.markActive();
                this.ingestQueue.add(new IngestSection(chunk.getPos().x, i, chunk.getPos().z, engine, section, null, null));
                try {
                    this.service.execute();
                } catch (Exception e) {
                    Logger.error("Executing had an error: assume shutting down, aborting",e);
                    break;
                }
            }
        }

        if (!gotLighting) {
            return false;
        }

        var blp = lightingProvider.getLayerListener(LightLayer.BLOCK);
        var slp = lightingProvider.getLayerListener(LightLayer.SKY);


        //Copied once per section and then referenced twice: as a section's own light, and again as
        //the row above the section below it. Fetching each neighbour separately would double the
        //copying to say the same thing.
        var sections = chunk.getSections();
        var blCopies = new DataLayer[sections.length];
        var slCopies = new DataLayer[sections.length];
        for (int k = 0; k < sections.length; k++) {
            if (sections[k] == null) continue;
            var pos = SectionPos.of(chunk.getPos(), chunk.getMinSection() + k);
            var bl = blp.getDataLayerData(pos);
            blCopies[k] = bl == null ? null : bl.copy();
            var sl = slp.getDataLayerData(pos);
            slCopies[k] = sl == null ? null : sl.copy();
        }

        //The air that sits directly on top of the ground is the one place the store has to be right
        //about, and the one place it was wrong: the light engine keeps no layer for a fully lit air
        //section, so voxy wrote the whole section out as plain zeros and the sky over open ground
        //came back as pitch black. Anything reading the store later - the seasonal snow pass, the
        //shading of that top face - then had no way to tell open sky from the inside of a mountain.
        //Only the sections immediately above something solid are given the real value: the rest of
        //the sky is nothing but air nobody asks about, and storing all of it would be a lot of empty
        //sections for no answer.
        for (int k = 0; k + 1 < sections.length; k++) {
            if (sections[k] == null || sections[k].hasOnlyAir() || slCopies[k + 1] != null) continue;
            if (sections[k + 1] == null) continue;
            int uniform = slp.getLightValue(
                    SectionPos.of(chunk.getPos(), chunk.getMinSection() + k + 1).origin());
            if (uniform > 0) {
                slCopies[k + 1] = new DataLayer(uniform);
            }
        }

        for (int k = 0; k < sections.length; k++) {
            var section = sections[k];
            int y = chunk.getMinSection() + k;
            if (section == null || !shouldIngestSection(section, chunk.getPos().x, y, chunk.getPos().z)) continue;
            //If its null for either, assume failure to obtain lighting and ignore section
            //if (blNone && slNone) {
            //    continue;
            //}
            int a = k + 1;
            boolean hasAbove = a < sections.length;
            //A section with nothing in it has no top row to decide, so do not pay for the row above it
            byte[] aboveLight = section.hasOnlyAir() ? null
                    : aboveLightRow(blp, slp,
                            hasAbove ? blCopies[a] : null,
                            hasAbove ? slCopies[a] : null,
                            chunk.getPos(), y + 1);
            engine.markActive();
            //TODO: fixme, this is technically not safe todo on the chunk load ingest, we need to copy the section data so it cant be modified while being read
            this.ingestQueue.add(new IngestSection(chunk.getPos().x, y, chunk.getPos().z, engine, section,
                    blCopies[k], slCopies[k],
                    hasAbove ? sections[a] : null,
                    aboveLight));
            try {
                this.service.execute();
            } catch (Exception e) {
                Logger.error("Executing had an error: assume shutting down, aborting",e);
                break;
            }
        }
        return true;
    }

    /**
     * The light one block above a section's top row, one packed byte per column, in the same
     * sky | block &lt;&lt; 4 layout ILightingSupplier uses.
     *
     * A copied light layer answers this per column when there is one. A missing layer is the case
     * that matters and the one that was getting this wrong: a section the light engine keeps no
     * layer for is uniform, and above the top of the sky light storage that uniform value is 15,
     * not 0. Reading it as zero said "buried" about the one place the sun definitely reaches, which
     * is the air sitting directly on top of the ground, and so the top row of every chunk section
     * was left bare while the fifteen rows under it snowed. One probe describes a uniform section,
     * and only the thread that owns the chunk may ask the light engine, which is why this is
     * resolved here rather than on the ingest worker.
     */
    private static byte[] aboveLightRow(LayerLightEventListener blp, LayerLightEventListener slp,
                                        DataLayer bl, DataLayer sl, ChunkPos cp, int aboveSectionY) {
        int uniformSky = 0;
        int uniformBlock = 0;
        if (sl == null || bl == null) {
            var origin = new BlockPos(cp.getMinBlockX(), aboveSectionY << 4, cp.getMinBlockZ());
            if (sl == null) uniformSky = slp.getLightValue(origin);
            if (bl == null) uniformBlock = blp.getLightValue(origin);
        }
        var row = new byte[256];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int sky = sl == null ? uniformSky : sl.get(x, 0, z);
                int block = bl == null ? uniformBlock : bl.get(x, 0, z);
                row[(z << 4) | x] = (byte) (Math.min(15, sky) | (Math.min(15, block) << 4));
            }
        }
        return row;
    }

    public int getTaskCount() {
        return this.service.numJobs();
    }

    public void shutdown() {
        this.service.shutdown();
    }

    //Utility method to ingest a chunk into the given WorldIdentifier or world
    public static boolean tryIngestChunk(WorldIdentifier worldId, LevelChunk chunk) {
        if (worldId == null) return false;
        var instance = VoxyCommon.getInstance();
        if (instance == null) return false;
        if (!instance.isIngestEnabled(worldId)) return false;
        var engine = instance.getOrCreate(worldId);
        if (engine == null) return false;
        return instance.getIngestService().enqueueIngest(engine, chunk);
    }

    //Try to automatically ingest the chunk into the correct world
    public static boolean tryAutoIngestChunk(LevelChunk chunk) {
        return tryIngestChunk(WorldIdentifier.of(chunk.getLevel()), chunk);
    }

    private boolean rawIngest0(WorldEngine engine, LevelChunkSection section, int x, int y, int z, DataLayer bl, DataLayer sl) {
        this.ingestQueue.add(new IngestSection(x, y, z, engine, section, bl, sl));
        try {
            this.service.execute();
            return true;
        } catch (Exception e) {
            Logger.error("Executing had an error: assume shutting down, aborting",e);
            return false;
        }
    }

    public static boolean rawIngest(WorldIdentifier id, LevelChunkSection section, int x, int y, int z, DataLayer bl, DataLayer sl) {
        if (id == null) return false;
        var engine = id.getOrCreateEngine();
        if (engine == null) return false;
        return rawIngest(engine, section, x, y, z, bl, sl);
    }

    public static boolean rawIngest(WorldEngine engine, LevelChunkSection section, int x, int y, int z, DataLayer bl, DataLayer sl) {
        if (!shouldIngestSection(section, x, y, z)) return false;
        if (engine.instanceIn == null) return false;
        if (!engine.instanceIn.isIngestEnabled(null)) return false;//TODO: dont pass in null
        return engine.instanceIn.getIngestService().rawIngest0(engine, section, x, y, z, bl, sl);
    }
}
