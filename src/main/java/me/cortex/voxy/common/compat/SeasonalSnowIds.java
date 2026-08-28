package me.cortex.voxy.common.compat;

import me.cortex.voxy.common.voxelization.IAboveSectionData;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.other.Mapper;

/**
 * Seasonally snowed blocks are stored as their normal block id reflected into the top of voxys
 * 20 bit id space, so a snowy variant costs no extra mapper entry and no change to the storage
 * format. ModelFactory#idMappings is already sized to the whole 20 bit range, so a reflected id
 * lands in its own model slot and bakes with a snow overlay on top of the base block.
 *
 * This is the same encoding EclipticSeasons uses for its own voxy integration, so lod data stays
 * readable either way round.
 *
 * Nothing here touches EclipticSeasons: the ingest side is installed as an oracle by the client
 * (see me.cortex.voxy.client.core.compat.seasons.SeasonalSnow) so that common code never has to
 * load a class that references the mod.
 */
public final class SeasonalSnowIds {
    private SeasonalSnowIds() {}

    /** Voxy packs block ids into 20 bits, see Mapper#getBlockId. */
    public static final int MAX_BLOCK_ID = (1 << 20) - 1;

    @FunctionalInterface
    public interface SnowOracle {
        /**
         * Marks the snowed voxels of a freshly built 16^3 section, in place.
         *
         * Runs over the finished array rather than per block during the fill, because deciding snow
         * needs the voxel above and that is not written yet while the fill is in progress. It also
         * lets ingest run the exact same decision as the refresher, which is what stops a re-ingest
         * from disagreeing with a refresh and undoing it.
         *
         * above is the row over the top of the section, or null when the caller could not see it.
         * Null means the top row is left undecided, never that it is decided as if nothing were
         * there: a store walk covers it later with data it can actually read.
         */
        void markSection(long[] data, Mapper mapper, VoxelizedSection section, IAboveSectionData above);
    }

    /** Null until the client installs one, and stays null when EclipticSeasons is absent. */
    public static volatile SnowOracle ORACLE = null;

    public static int mark(int blockId) {
        return MAX_BLOCK_ID - blockId;
    }

    /**
     * Real ids are always below the mapper's count, so anything at or above it is either a
     * reflected id or genuinely out of range. Reflecting it back settles which.
     */
    public static boolean isMarked(Mapper mapper, int blockId) {
        int count = mapper.getBlockStateCount();
        return blockId >= count && (MAX_BLOCK_ID - blockId) < count;
    }

    /** Reflected ids map back to their base block, everything else passes through untouched. */
    public static int unmark(Mapper mapper, int blockId) {
        int count = mapper.getBlockStateCount();
        if (blockId < count) {
            return blockId;
        }
        int unmarked = MAX_BLOCK_ID - blockId;
        return unmarked < count ? unmarked : blockId;
    }
}
