package me.cortex.voxy.client.core.compat.seasons;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.model.bakery.ReuseVertexConsumer;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.compat.SeasonalSnowIds;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.other.Mapper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Seasonal snow on lods, driven by EclipticSeasons.
 *
 * EclipticSeasons ships its own voxy integration, but it only registers the config value that
 * arms it when Platform#isModLoaded("voxy") is true as its config spec is built. A voxy arriving
 * through Sinytra Connector is not in the NeoForge mod list that early, so the option never
 * exists while the mixins still apply, leaving its VoxyTool#isVoxyTest dereferencing a null
 * config value. Doing the work from this side avoids depending on that timing entirely.
 *
 * Every EclipticSeasons type stays behind SeasonalSnowHooks so that this class, which common
 * ingest code reaches, never forces those classes to load when the mod is absent.
 */
public final class SeasonalSnow {
    private SeasonalSnow() {}

    public static final boolean MOD_PRESENT = FabricLoader.getInstance().isModLoaded("eclipticseasons");

    //Latched on the first failure. The EclipticSeasons api is not stable across its versions and a
    //throw here would otherwise repeat for every block of every section.
    private static volatile boolean broken = false;

    public static boolean enabled() {
        return MOD_PRESENT && !broken && VoxyConfig.CONFIG.seasonalSnowLod;
    }

    /** Called once from client init, before any section can be ingested. */
    public static void install() {
        if (!MOD_PRESENT) {
            return;
        }
        SeasonalSnowIds.ORACLE = SeasonalSnow::markIfSnowy;
        Logger.info("EclipticSeasons found, seasonal snow on lods is available");
    }

    private static int markIfSnowy(int blockId, Mapper mapper, int index, VoxelizedSection section, int biomeId) {
        if (!enabled()) {
            return blockId;
        }
        try {
            return SeasonalSnowHooks.isSnowy(blockId, mapper, index, section)
                    ? SeasonalSnowIds.mark(blockId)
                    : blockId;
        } catch (Throwable t) {
            disable("deciding whether a block is snowy", t);
            return blockId;
        }
    }

    /** Adds EclipticSeasons' snow model on top of the block already baked into the lod texture. */
    public static void renderSnowOverlay(BlockState state, RenderType layer, ReuseVertexConsumer translucentVC, ReuseVertexConsumer opaqueVC) {
        if (!enabled()) {
            return;
        }
        try {
            SeasonalSnowHooks.renderSnowOverlay(state, layer, translucentVC, opaqueVC);
        } catch (Throwable t) {
            disable("baking a snow overlay model", t);
        }
    }

    //Last season seen by the tick poll. EclipticSeasons 0.14.5 has no client event we can
    //subscribe to from a fabric mod, so the current solar term is watched instead.
    private static volatile Object lastSeason = null;

    public static void onClientTick(net.minecraft.world.level.Level level) {
        if (level == null) {
            lastSeason = null;
            return;
        }
        if (!enabled() || !VoxyConfig.CONFIG.seasonalSnowAutoRefresh) {
            return;
        }
        Object token;
        try {
            token = SeasonalSnowHooks.seasonToken();
        } catch (Throwable t) {
            disable("reading the current season", t);
            return;
        }
        if (token == null) {
            return;
        }
        if (lastSeason == null) {
            lastSeason = token;//First sight of a world is not a change
            return;
        }
        if (token.equals(lastSeason)) {
            return;
        }
        lastSeason = token;
        refresh(level, "the season changed to " + token);
    }

    /** Returns null when a refresh started, or a reason it did not. */
    public static String refresh(net.minecraft.world.level.Level level, String why) {
        var engine = me.cortex.voxy.commonImpl.WorldIdentifier.ofEngineNullable(level);
        if (engine == null) {
            return "no voxy world for this level";
        }
        String blocked = SeasonalSnowRefresher.start(level, engine);
        if (blocked == null) {
            Logger.info("Refreshing seasonal snow over stored lods, " + why);
        }
        return blocked;
    }

    private static void disable(String doing, Throwable t) {
        if (broken) {
            return;
        }
        broken = true;
        Logger.error("EclipticSeasons compat failed while " + doing
                + ", turning seasonal snow on lods off for this session", t);
    }
}
