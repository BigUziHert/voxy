package me.cortex.voxy.client.core.compat.seasons;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
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
        SeasonalSnowIds.ORACLE = SeasonalSnow::markSection;
        Logger.info("EclipticSeasons found, seasonal snow on lods is available");
    }

    private static void markSection(long[] data, Mapper mapper, VoxelizedSection section) {
        if (!enabled()) {
            return;
        }
        try {
            SeasonalSnowHooks.markSection(data, mapper, section);
        } catch (Throwable t) {
            disable("deciding snow over a freshly ingested section", t);
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

    //Last solar term seen by the tick poll, as a fallback for the snow-change flag below
    private static volatile int lastTerm = Integer.MIN_VALUE;
    private static int tickCounter = 0;

    //A season change is broadcast as a run of updates while it is snowing, and each pass walks the
    //whole store, so passes are spaced out rather than queued up behind each other
    private static final long AUTO_COOLDOWN_MILLIS = 60_000;

    public static void onClientTick(net.minecraft.world.level.Level level) {
        //Always, even with the feature off: a run that was cancelled or a world that changed can
        //still leave rebuilds queued, and they have to drain rather than pile up
        SeasonalSnowRefresher.drainDirty();
        announceFinished();
        announceProgress();

        if (level == null) {
            lastTerm = Integer.MIN_VALUE;
            return;
        }
        if (!enabled()) {
            return;
        }
        if (!VoxyConfig.CONFIG.seasonalSnowAutoRefresh && !VoxyConfig.CONFIG.seasonalColourReload) {
            return;//Nothing to do on a season change
        }
        //Once a second is plenty
        if ((tickCounter++ % 20) != 0) {
            return;
        }
        //Checked before anything is read, so a change that lands inside the window is deferred
        //rather than consumed and dropped: the flag stays raised and the term stays unrecorded,
        //and the first tick past the window acts on it.
        if (SeasonalSnowRefresher.isRunning()) {
            return;
        }
        if (System.currentTimeMillis() - SeasonalSnowRefresher.lastPassEndMillis < AUTO_COOLDOWN_MILLIS) {
            return;
        }

        boolean changed;
        int term;
        try {
            //EclipticSeasons raises this itself when the snow situation moves. Its client agent is a
            //no-op implementation until the mod installs the real one though, so a false is not proof
            //that nothing changed: the solar term is watched as well, and either one is enough.
            changed = SeasonalSnowHooks.consumeSnowChange();
            term = SeasonalSnowHooks.solarTermToken();
        } catch (Throwable t) {
            disable("reading the current season", t);
            return;
        }
        if (lastTerm == Integer.MIN_VALUE) {
            lastTerm = term;//First sight of a world is not a change
            return;
        }
        if (term != lastTerm) {
            lastTerm = term;
            changed = true;
        }
        if (!changed) {
            return;
        }
        if (VoxyConfig.CONFIG.seasonalColourReload) {
            reloadColours();
        }
        if (VoxyConfig.CONFIG.seasonalSnowAutoRefresh) {
            refresh(level, "the snow changed with the season");
        }
    }

    /**
     * Rebuilds voxy's lod models so their tint is recaptured for the current season.
     *
     * Snow lives in the voxel data, which the refresher rewrites, but colour does not. EclipticSeasons
     * recolours foliage by mixing into BlockColors and BiomeColors, and voxy reads its tints through
     * exactly that path in ModelFactory. It only reads them once though, when it builds the per model
     * and biome colour table it uploads to the gpu, so the colours are correct for whichever season
     * was current when they were captured and frozen from then on. Rebuilding is what recaptures them.
     *
     * Only the colour buffer is rewritten. No model is re-baked and no geometry is rebuilt, so this
     * lands without a hitch.
     */
    public static void reloadColours() {
        if (!MOD_PRESENT) {
            return;
        }
        try {
            var renderSystem = IGetVoxyRenderSystem.getNullable();
            if (renderSystem == null) {
                return;//No renderer yet, whatever gets built will capture the current season anyway
            }
            renderSystem.getModelService().requestColourRecapture();
        } catch (Throwable t) {
            disable("asking for lod colours to be recaptured", t);
        }
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

    /** Puts a finished walk in chat, so nobody has to poll status to find out it ended. */
    private static void announceFinished() {
        String message = SeasonalSnowRefresher.takeFinishedMessage();
        if (message == null) {
            return;
        }
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.gui != null) {
            mc.gui.getChat().addMessage(net.minecraft.network.chat.Component.literal(message));
        }
    }

    //A walk that is running says so every few seconds. Whether a refresh actually gets anywhere is
    //the question that has been hardest to answer from the outside, and making it answer itself is
    //cheaper than asking for a status every time.
    private static int progressTicks = 0;

    private static void announceProgress() {
        if (!SeasonalSnowRefresher.isRunning()) {
            progressTicks = 0;
            return;
        }
        if ((progressTicks++ % 100) != 0) {
            return;
        }
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.gui != null) {
            mc.gui.getChat().addMessage(net.minecraft.network.chat.Component.literal(
                    "Seasonal snow refresh: " + SeasonalSnowRefresher.describe()));
        }
    }

    public static boolean isBroken() {
        return broken;
    }

    /** Everything the debug command reports about the state of the feature. */
    public static void report(net.minecraft.world.level.Level level, java.util.List<String> out) {
        out.add("EclipticSeasons installed: " + MOD_PRESENT
                + ", seasonal snow on lods: " + VoxyConfig.CONFIG.seasonalSnowLod
                + ", auto refresh: " + VoxyConfig.CONFIG.seasonalSnowAutoRefresh
                + ", colour reload: " + VoxyConfig.CONFIG.seasonalColourReload);
        out.add("working: " + enabled() + (broken ? " (turned itself off after an error, see the log)" : ""));
        out.add("refresh: " + (SeasonalSnowRefresher.isRunning() ? "running, " : "")
                + SeasonalSnowRefresher.describe()
                + ", " + SeasonalSnowRefresher.pendingRebuilds() + " rebuilds queued");
        if (!MOD_PRESENT || level == null) {
            return;
        }
        var engine = me.cortex.voxy.commonImpl.WorldIdentifier.ofEngineNullable(level);
        if (engine == null) {
            out.add("no voxy world for this level");
            return;
        }
        try {
            SeasonalSnowHooks.describeState(level, engine.getMapper(), out);
        } catch (Throwable t) {
            out.add("could not read EclipticSeasons: " + t);
        }
    }

    /** Everything the debug command reports about one position, lod by lod. */
    public static void probe(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos,
                             java.util.List<String> out) {
        if (!MOD_PRESENT) {
            out.add("EclipticSeasons is not installed");
            return;
        }
        var engine = me.cortex.voxy.commonImpl.WorldIdentifier.ofEngineNullable(level);
        if (engine == null) {
            out.add("no voxy world for this level");
            return;
        }
        try {
            SeasonalSnowHooks.describeVanilla(level, pos, out);
            SeasonalSnowRefresher.probe(level, engine, pos, out);
        } catch (Throwable t) {
            out.add("probe failed: " + t);
        }
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
