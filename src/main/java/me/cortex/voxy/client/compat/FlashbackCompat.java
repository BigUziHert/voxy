package me.cortex.voxy.client.compat;

import me.cortex.voxy.common.Logger;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;

public class FlashbackCompat {
    public static final boolean FLASHBACK_INSTALLED = FabricLoader.getInstance().isModLoaded("flashback");

    public static Path getReplayStoragePath() {
        if (!FLASHBACK_INSTALLED) {
            return null;
        }
        return getReplayStoragePath0();
    }

    private static Path getReplayStoragePath0() {
        try {
            Class<?> flashbackClass = Class.forName("com.moulberry.flashback.Flashback");
            Method getReplayServer = flashbackClass.getMethod("getReplayServer");
            Object replayServer = getReplayServer.invoke(null);
            if (replayServer == null) {
                return null;
            }

            Object meta = replayServer.getClass().getMethod("getMetadata").invoke(replayServer);
            if (meta != null) {
                var path = ((IFlashbackMeta)meta).getVoxyPath();
                if (path != null) {
                    Logger.info("Flashback replay server exists and meta exists");
                    if (path.exists()) {
                        Logger.info("Flashback voxy path exists in filesystem, using this as lod data source");
                        return path.toPath();
                    } else {
                        Logger.warn("Flashback meta had voxy path saved but path doesnt exist");
                    }
                }
            }
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException e) {
            Logger.warn("Flashback compatibility could not inspect replay metadata", e);
        } catch (InvocationTargetException e) {
            Logger.warn("Flashback replay metadata lookup threw an exception", e.getCause() != null ? e.getCause() : e);
        } catch (ClassCastException e) {
            Logger.warn("Flashback metadata did not implement IFlashbackMeta", e);
        }
        return null;
    }
}
