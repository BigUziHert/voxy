package me.cortex.voxy.client;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.common.DebugUtils;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.cortex.voxy.commonImpl.importers.DHImporter;
import me.cortex.voxy.commonImpl.importers.WorldImporter;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;


public class VoxyCommands {

    public static LiteralArgumentBuilder<FabricClientCommandSource> register() {
        var imports = ClientCommandManager.literal("import")
                .then(ClientCommandManager.literal("world")
                        .then(ClientCommandManager.argument("world_name", StringArgumentType.string())
                                .suggests(VoxyCommands::importWorldSuggester)
                                .executes(VoxyCommands::importWorld)))
                .then(ClientCommandManager.literal("bobby")
                        .then(ClientCommandManager.argument("world_name", StringArgumentType.string())
                                .suggests(VoxyCommands::importBobbySuggester)
                                .executes(VoxyCommands::importBobby)))
                .then(ClientCommandManager.literal("raw")
                        .then(ClientCommandManager.argument("path", StringArgumentType.string())
                                .executes(VoxyCommands::importRaw)))
                .then(ClientCommandManager.literal("zip")
                        .then(ClientCommandManager.argument("zipPath", StringArgumentType.string())
                                .executes(VoxyCommands::importZip)
                                .then(ClientCommandManager.argument("innerPath", StringArgumentType.string())
                                        .executes(VoxyCommands::importZip))))
                .then(ClientCommandManager.literal("current")
                        .executes(VoxyCommands::importCurrentWorldIn))
                .then(ClientCommandManager.literal("cancel")
                        .executes(VoxyCommands::cancelImport));

        if (DHImporter.HasRequiredLibraries) {
            imports = imports
                    .then(ClientCommandManager.literal("distant_horizons")
                            .then(ClientCommandManager.argument("sqlDbPath", StringArgumentType.string())
                                    .executes(VoxyCommands::importDistantHorizons)));
        }

        var debug = ClientCommandManager.literal("debug")
                .then(ClientCommandManager.literal("verifyTLNChildMask")
                        .executes(ctx->verifyTLNs(ctx, false))
                        .then(ClientCommandManager.argument("attemptRepair", BoolArgumentType.bool())
                                .executes(ctx->verifyTLNs(ctx, BoolArgumentType.getBool(ctx, "attemptRepair"))))
                );

        var seasonalSnow = ClientCommandManager.literal("seasonalsnow")
                .then(ClientCommandManager.literal("refresh")
                        .executes(VoxyCommands::refreshSeasonalSnow))
                .then(ClientCommandManager.literal("cancel")
                        .executes(VoxyCommands::cancelSeasonalSnow))
                .then(ClientCommandManager.literal("status")
                        .executes(VoxyCommands::seasonalSnowStatus))
                .then(ClientCommandManager.literal("colours")
                        .executes(VoxyCommands::reloadSeasonalColours))
                .then(ClientCommandManager.literal("debug")
                        .executes(VoxyCommands::seasonalSnowDebug))
                .then(ClientCommandManager.literal("watch")
                        .executes(ctx -> seasonalSnowWatch(ctx, null))
                        .then(ClientCommandManager.argument("x", IntegerArgumentType.integer())
                                .then(ClientCommandManager.argument("y", IntegerArgumentType.integer())
                                        .then(ClientCommandManager.argument("z", IntegerArgumentType.integer())
                                                .executes(ctx -> seasonalSnowWatch(ctx, new net.minecraft.core.BlockPos(
                                                        IntegerArgumentType.getInteger(ctx, "x"),
                                                        IntegerArgumentType.getInteger(ctx, "y"),
                                                        IntegerArgumentType.getInteger(ctx, "z"))))))))
                .then(ClientCommandManager.literal("probe")
                        .executes(ctx -> seasonalSnowProbe(ctx, null))
                        .then(ClientCommandManager.argument("x", IntegerArgumentType.integer())
                                .then(ClientCommandManager.argument("y", IntegerArgumentType.integer())
                                        .then(ClientCommandManager.argument("z", IntegerArgumentType.integer())
                                                .executes(ctx -> seasonalSnowProbe(ctx, new net.minecraft.core.BlockPos(
                                                        IntegerArgumentType.getInteger(ctx, "x"),
                                                        IntegerArgumentType.getInteger(ctx, "y"),
                                                        IntegerArgumentType.getInteger(ctx, "z"))))))));

        return ClientCommandManager.literal("voxy")//.requires((ctx)-> VoxyCommon.getInstance() != null)
                .then(ClientCommandManager.literal("reload")
                        .executes(VoxyCommands::reloadInstance))
                .then(imports)
                .then(seasonalSnow)
                .then(debug);
    }

    private static int seasonalSnowWatch(CommandContext<FabricClientCommandSource> ctx,
                                        net.minecraft.core.BlockPos at) {
        me.cortex.voxy.client.core.compat.seasons.SeasonalSnowRefresher.watch(at);
        ctx.getSource().sendFeedback(Component.literal(at == null
                ? "No longer narrating any block"
                : "Narrating every seasonal snow decision about " + at.getX() + " " + at.getY()
                        + " " + at.getZ() + " to the log. Run a refresh, then send the lines "
                        + "tagged [snow watch]"));
        return 0;
    }

    private static int refreshSeasonalSnow(CommandContext<FabricClientCommandSource> ctx) {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            ctx.getSource().sendError(Component.literal("Not in a world"));
            return 1;
        }
        //Asked for by hand, so reconsider everything: a section written off as unsnowable by an
        //earlier pass stays written off for the session, and those classifications have been wrong
        //before now
        me.cortex.voxy.client.core.compat.seasons.SeasonalSnowRefresher.forgetBarren();
        String blocked = me.cortex.voxy.client.core.compat.seasons.SeasonalSnow.refresh(level, "asked for by command");
        if (blocked != null) {
            ctx.getSource().sendError(Component.literal("Could not start: " + blocked));
            return 1;
        }
        me.cortex.voxy.client.core.compat.seasons.SeasonalSnow.reloadColours();
        ctx.getSource().sendFeedback(Component.literal(
                "Refreshing seasonal snow over stored LODs and rebuilding their colours. "
                        + "The snow walk runs in the background, /voxy seasonalsnow status to watch it"));
        return 0;
    }

    private static int reloadSeasonalColours(CommandContext<FabricClientCommandSource> ctx) {
        me.cortex.voxy.client.core.compat.seasons.SeasonalSnow.reloadColours();
        ctx.getSource().sendFeedback(Component.literal(
                "Rebuilding LODs so their grass and leaf colours match the current season"));
        return 0;
    }

    private static int cancelSeasonalSnow(CommandContext<FabricClientCommandSource> ctx) {
        me.cortex.voxy.client.core.compat.seasons.SeasonalSnowRefresher.cancel();
        ctx.getSource().sendFeedback(Component.literal("Cancelling the seasonal snow refresh"));
        return 0;
    }

    /**
     * Dumps what the seasonal snow decision is actually working from. Written because three separate
     * bugs in this feature all looked identical from the outside ("the snow is wrong"), and each one
     * was found by reading the inputs rather than by reasoning about the code.
     */
    private static int seasonalSnowDebug(CommandContext<FabricClientCommandSource> ctx) {
        var level = Minecraft.getInstance().level;
        var out = new java.util.ArrayList<String>();
        me.cortex.voxy.client.core.compat.seasons.SeasonalSnow.report(level, out);

        var player = Minecraft.getInstance().player;
        if (level != null && player != null) {
            var engine = WorldIdentifier.ofEngineNullable(level);
            if (engine != null) {
                out.add("what the level 0 lods around you decide (buried voxels omitted):");
                try {
                    me.cortex.voxy.client.core.compat.seasons.SeasonalSnowRefresher.sample(
                            level, engine, player.blockPosition(), 1, out);
                } catch (Throwable t) {
                    out.add("  sampling failed: " + t);
                }
            }
        }
        send(ctx, out);
        return 0;
    }

    private static int seasonalSnowProbe(CommandContext<FabricClientCommandSource> ctx, net.minecraft.core.BlockPos at) {
        var level = Minecraft.getInstance().level;
        var player = Minecraft.getInstance().player;
        if (level == null || player == null) {
            ctx.getSource().sendError(Component.literal("Not in a world"));
            return 1;
        }
        net.minecraft.core.BlockPos pos = at;
        if (pos == null) {
            //Whatever you are looking at, or the ground under you when that is nothing
            var hit = Minecraft.getInstance().hitResult;
            pos = hit instanceof net.minecraft.world.phys.BlockHitResult block
                    ? block.getBlockPos()
                    : player.blockPosition().below();
        }
        var out = new java.util.ArrayList<String>();
        out.add("seasonal snow at x=" + pos.getX() + " z=" + pos.getZ()
                + ", looking at or below y=" + pos.getY() + ":");
        me.cortex.voxy.client.core.compat.seasons.SeasonalSnow.probe(level, pos, out);
        send(ctx, out);
        return 0;
    }

    private static void send(CommandContext<FabricClientCommandSource> ctx, java.util.List<String> lines) {
        for (String line : lines) {
            ctx.getSource().sendFeedback(Component.literal(line));
            Logger.info(line);
        }
    }

    private static int seasonalSnowStatus(CommandContext<FabricClientCommandSource> ctx) {
        ctx.getSource().sendFeedback(Component.literal(
                "Seasonal snow refresh: " + me.cortex.voxy.client.core.compat.seasons.SeasonalSnowRefresher.describe()));
        return 0;
    }

    private static int reloadInstance(CommandContext<FabricClientCommandSource> ctx) {
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendError(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        var wr = Minecraft.getInstance().levelRenderer;
        if (wr!=null) {
            ((IGetVoxyRenderSystem)wr).voxy$shutdownRenderer();
        }

        VoxyCommon.shutdownInstance();
        System.gc();
        VoxyCommon.createInstance();

        var r = Minecraft.getInstance().levelRenderer;
        if (r != null) r.allChanged();
        return 0;
    }

    private static int verifyTLNs(CommandContext<FabricClientCommandSource> ctx, boolean attemptRepair) {
        var instance = VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendError(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        if (Minecraft.getInstance().level == null) {
            throw new IllegalStateException("How you even do this");
        }
        DebugUtils.verifyAllTopLevelNodes(WorldIdentifier.ofEngine(Minecraft.getInstance().level), attemptRepair);
        return 0;
    }


    private static int importDistantHorizons(CommandContext<FabricClientCommandSource> ctx) {
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendError(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        var dbFile = new File(ctx.getArgument("sqlDbPath", String.class));
        if (!dbFile.exists()) {
            return 1;
        }
        if (dbFile.isDirectory()) {
            dbFile = dbFile.toPath().resolve("DistantHorizons.sqlite").toFile();
            if (!dbFile.exists()) {
                return 1;
            }
        }

        File dbFile_ = dbFile;
        var engine = WorldIdentifier.ofEngine(Minecraft.getInstance().level);
        if (engine==null)return 1;
        return instance.getImportManager().makeAndRunIfNone(engine, ()->
                new DHImporter(dbFile_, engine, Minecraft.getInstance().level, instance.getServiceManager(), instance.savingServiceRateLimiter))?0:1;
    }

    private static boolean fileBasedImporter(File directory) {
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            return false;
        }

        var engine = WorldIdentifier.ofEngine(Minecraft.getInstance().level);
        if (engine==null) return false;
        return instance.getImportManager().makeAndRunIfNone(engine, ()->{
            var importer = new WorldImporter(engine, Minecraft.getInstance().level, instance.getServiceManager(), instance.savingServiceRateLimiter);
            importer.importRegionDirectoryAsync(directory);
            return importer;
        });
    }

    private static int importRaw(CommandContext<FabricClientCommandSource> ctx) {
        if (VoxyCommon.getInstance() == null) {
            ctx.getSource().sendError(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }

        return fileBasedImporter(new File(ctx.getArgument("path", String.class)))?0:1;
    }

    private static int importBobby(CommandContext<FabricClientCommandSource> ctx) {
        if (VoxyCommon.getInstance() == null) {
            ctx.getSource().sendError(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }

        var file = new File(".bobby").toPath().resolve(ctx.getArgument("world_name", String.class)).toFile();
        return fileBasedImporter(file)?0:1;
    }

    private static CompletableFuture<Suggestions> importWorldSuggester(CommandContext<FabricClientCommandSource> ctx, SuggestionsBuilder sb) {
        return fileDirectorySuggester(Minecraft.getInstance().gameDirectory.toPath().resolve("saves"), sb);
    }
    private static CompletableFuture<Suggestions> importBobbySuggester(CommandContext<FabricClientCommandSource> ctx, SuggestionsBuilder sb) {
        return fileDirectorySuggester(Minecraft.getInstance().gameDirectory.toPath().resolve(".bobby"), sb);
    }

    private static CompletableFuture<Suggestions> fileDirectorySuggester(Path dir, SuggestionsBuilder sb) {
        var str = sb.getRemaining().replace("\\\\", "\\").replace("\\", "/");
        if (str.startsWith("\"")) {
            str = str.substring(1);
        }
        if (str.endsWith("\"")) {
            str = str.substring(0,str.length()-1);
        }
        var remaining = str;
        if (str.contains("/")) {
            int idx = str.lastIndexOf('/');
            remaining = str.substring(idx+1);
            try {
                dir = dir.resolve(str.substring(0, idx));
            } catch (Exception e) {
                return Suggestions.empty();
            }
            str = str.substring(0, idx+1);
        } else {
            str = "";
        }

        try {
            var worlds = Files.list(dir).toList();
            for (var world : worlds) {
                if (!world.toFile().isDirectory()) {
                    continue;
                }
                var wn = world.getFileName().toString();
                if (wn.equals(remaining)) {
                    continue;
                }
                if (SharedSuggestionProvider.matchesSubStr(remaining, wn) || SharedSuggestionProvider.matchesSubStr(remaining, '"'+wn)) {
                    wn = str+wn + "/";
                    sb.suggest(StringArgumentType.escapeIfRequired(wn));
                }
            }
        } catch (IOException e) {}

        return sb.buildFuture();
    }


    private static int importCurrentWorldIn(CommandContext<FabricClientCommandSource> ctx) {
        if (VoxyCommon.getInstance() == null) {
            ctx.getSource().sendError(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }

        var localServer = Minecraft.getInstance().getSingleplayerServer();
        if (localServer == null) {
            ctx.getSource().sendError(Component.translatable("You must be in single player to use this command"));
            return 1;
        }
        var regionPath = DimensionType.getStorageFolder(Minecraft.getInstance().level.dimension(), localServer.getWorldPath(LevelResource.ROOT)).resolve("region");
        if ((!regionPath.toFile().exists())||!regionPath.toFile().isDirectory()) {
            ctx.getSource().sendError(Component.translatable("Cannot find region folder for current dimension"));
            return 1;
        }
        return fileBasedImporter(regionPath.toFile())?0:1;
    }

    private static int importWorld(CommandContext<FabricClientCommandSource> ctx) {
        if (VoxyCommon.getInstance() == null) {
            ctx.getSource().sendError(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }

        var name = ctx.getArgument("world_name", String.class);
        var file = new File("saves").toPath().resolve(name);
        name = name.toLowerCase(Locale.ROOT);
        if (name.endsWith("/")) {
            name = name.substring(0, name.length()-1);
        }
        if (file.resolve("level.dat").toFile().exists()) {
            var dimFile = DimensionType.getStorageFolder(Minecraft.getInstance().level.dimension(), file)
                    .resolve("region")
                    .toFile();
            if (!dimFile.isDirectory()) return 1;
            return fileBasedImporter(dimFile)?0:1;
            //We are in a world directory, so import the current dimension we are in
            /*
            for (var dim : new String[]{"overworld", "the_nether", "the_end"}) {//This is so annoying that you cant loop through all the dimensions
                var id = ResourceKey.create(Registries.DIMENSION, Identifier.withDefaultNamespace(dim));
                var dimPath = DimensionType.getStorageFolder(id, file);
                dimPath = dimPath.resolve("region");
                var dimFile = dimPath.toFile();
                if (dimFile.isDirectory()) {//exists and is a directory
                    if (!fileBasedImporter(dimFile)) {
                        Logger.error("Failed to import dimension: " + id);
                    }
                }
            }*/
        } else {
            if (!(name.endsWith("region"))) {
                file = file.resolve("region");
            }
            return fileBasedImporter(file.toFile()) ? 0 : 1;
        }
    }

    private static int importZip(CommandContext<FabricClientCommandSource> ctx) {
        var zip =  new File(ctx.getArgument("zipPath", String.class));
        var innerDir = "region/";
        try {
            innerDir = ctx.getArgument("innerPath", String.class);
        } catch (Exception e) {}

        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendError(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        String finalInnerDir = innerDir;

        var engine = WorldIdentifier.ofEngine(Minecraft.getInstance().level);
        if (engine != null) {
            return instance.getImportManager().makeAndRunIfNone(engine, () -> {
                var importer = new WorldImporter(engine, Minecraft.getInstance().level, instance.getServiceManager(), instance.savingServiceRateLimiter);
                importer.importZippedRegionDirectoryAsync(zip, finalInnerDir);
                return importer;
            }) ? 0 : 1;
        }
        return 1;
    }

    private static int cancelImport(CommandContext<FabricClientCommandSource> ctx) {
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendError(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        var world = WorldIdentifier.ofEngineNullable(Minecraft.getInstance().level);
        if (world != null) {
            return instance.getImportManager().cancelImport(world)?0:1;
        }
        return 1;
    }
}