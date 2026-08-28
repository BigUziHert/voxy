package me.cortex.voxy.common.voxelization;

import net.minecraft.world.level.block.state.BlockState;

/**
 * The row of blocks immediately above a 16^3 section, that is y = 0 of the section above it.
 *
 * A section being voxelized can see the voxel above every block it contains except the top row,
 * where the answer lives in the neighbouring section. Anything that decides per block and needs to
 * look up, seasonal snow being the one that does, otherwise has to leave that row undecided.
 *
 * Supplied by whoever built the lighting: an ILightingSupplier may also implement this, and
 * WorldConversionFactory passes it on when it does. It is always optional. A caller that cannot
 * see the section above simply does not implement it, and the row stays undecided rather than
 * being guessed at.
 */
public interface IAboveSectionData {
    /** Packed as sky | (block << 4), the same as ILightingSupplier#supply. */
    byte lightAbove(int x, int z);

    /** Never null. Air where the section above has nothing at that column. */
    BlockState stateAbove(int x, int z);
}
