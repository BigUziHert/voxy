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
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ConcurrentLinkedDeque;

public class VoxelIngestService {
    private static final ThreadLocal<VoxelizedSection> SECTION_CACHE = ThreadLocal.withInitial(VoxelizedSection::createEmpty);
    private final Service service;
    //aboveSection and its two light layers are the section directly on top of this one, when the
    //enqueuing side could see it. All three are null otherwise, and then the top row of this section
    //is simply left undecided rather than decided against data nobody has.
    private record IngestSection(int cx, int cy, int cz, WorldEngine world, LevelChunkSection section,
                                 DataLayer blockLight, DataLayer skyLight,
                                 LevelChunkSection aboveSection, DataLayer aboveBlockLight,
                                 DataLayer aboveSkyLight){
        IngestSection(int cx, int cy, int cz, WorldEngine world, LevelChunkSection section,
                      DataLayer blockLight, DataLayer skyLight) {
            this(cx, cy, cz, world, section, blockLight, skyLight, null, null, null);
        }
    }

    /**
     * A lighting supplier that can also answer for the row above the section, so the top row of a
     * section can be decided rather than skipped. Only built when the sky light of the section above
     * is actually known: absent light reads as zero, and zero sky light on the block above a surface
     * would say "buried" when it means "no data".
     */
    private record AboveAwareLighting(ILightingSupplier base, LevelChunkSection aboveSection,
                                      DataLayer aboveBlockLight, DataLayer aboveSkyLight)
            implements ILightingSupplier, IAboveSectionData {
        @Override
        public byte supply(int x, int y, int z) {
            return this.base.supply(x, y, z);
        }

        @Override
        public byte lightAbove(int x, int z) {
            int block = this.aboveBlockLight == null ? 0 : Math.min(15, this.aboveBlockLight.get(x, 0, z));
            int sky = this.aboveSkyLight == null ? 0 : Math.min(15, this.aboveSkyLight.get(x, 0, z));
            return (byte) (sky | (block << 4));
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
        boolean sl = sla != null && !sla.isEmpty();
        boolean bl = bla != null && !bla.isEmpty();
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
        //Only when the sky light above is genuinely known. A missing layer reads as zero, and zero
        //over a surface block would be taken as buried, which is the one answer that must not be
        //guessed at here.
        var asl = task.aboveSkyLight;
        if (task.aboveSection != null && asl != null && !asl.isEmpty()) {
            return new AboveAwareLighting(supplier, task.aboveSection, task.aboveBlockLight, asl);
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
            engine.markActive();
            //TODO: fixme, this is technically not safe todo on the chunk load ingest, we need to copy the section data so it cant be modified while being read
            this.ingestQueue.add(new IngestSection(chunk.getPos().x, y, chunk.getPos().z, engine, section,
                    blCopies[k], slCopies[k],
                    hasAbove ? sections[a] : null,
                    hasAbove ? blCopies[a] : null,
                    hasAbove ? slCopies[a] : null));
            try {
                this.service.execute();
            } catch (Exception e) {
                Logger.error("Executing had an error: assume shutting down, aborting",e);
                break;
            }
        }
        return true;
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
