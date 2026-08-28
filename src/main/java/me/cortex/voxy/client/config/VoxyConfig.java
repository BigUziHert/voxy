package me.cortex.voxy.client.config;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import me.cortex.voxy.client.core.SSAO;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.cpu.CpuLayout;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.fabricmc.loader.api.FabricLoader;

import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public class VoxyConfig {
    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setPrettyPrinting()
            .excludeFieldsWithModifiers(Modifier.PRIVATE)
            .create();

    public static VoxyConfig CONFIG = loadOrCreate();

    public boolean enabled = true;
    public boolean enableRendering = true;
    public boolean ingestEnabled = true;
    public float sectionRenderDistance = 16;
    public int serviceThreads = (int) Math.max(CpuLayout.getCoreCount()/1.5, 1);
    public float subDivisionSize = 64;
    public int skyFogDistance = 96;
    public float fogIntensity = 1.0f;
    public float fogDensity = 0.0f;
    public boolean adaptCloudDistance = true;
    public int cloudDistance = 0;
    public boolean dontUseSodiumBuilderThreads = false;

    public String ssaoMode;

    public boolean useEnvironmentalFog = true;

    //Snow distant lod terrain in step with EclipticSeasons. Does nothing without that mod, and
    //only affects sections ingested from here on, since the snow is baked in at ingest time.
    public boolean seasonalSnowLod = true;

    //Re-walk the stored lods when the season changes. Off by default: it rewrites voxels in the
    //lod store, so it is opt in. /voxy seasonalsnow refresh runs the same pass by hand.
    public boolean seasonalSnowAutoRefresh = false;

    //Rebuild lod models when the season changes so their grass and leaf tints are recaptured.
    //Cheap next to the snow walk and it fixes an obvious wrong look, so it is on by default.
    public boolean seasonalColourReload = true;

    //Divisor of earths radius, so 1 would be true earth curvature and larger values a smaller,
    // more sharply curved planet. 0 disables the effect and leaves the world flat.
    public int earthCurveRatio = 0;

    //Below this the world drops by well under a block across voxys whole render distance, so the
    // effect would be invisible while still paying for itself, snap up to it instead
    private static final int MIN_EARTH_CURVE_RATIO = 50;
    private static final float EARTH_RADIUS_IN_BLOCKS = 6371000.0f;

    public static int clampEarthCurveRatio(int ratio) {
        return (ratio > 0 && ratio < MIN_EARTH_CURVE_RATIO) ? MIN_EARTH_CURVE_RATIO : Math.max(ratio, 0);
    }

    //Radius in blocks of the sphere the world is bent around, 0 when curvature is disabled
    public float getWorldCurveRadius() {
        int ratio = clampEarthCurveRatio(this.earthCurveRatio);
        return ratio == 0 ? 0.0f : EARTH_RADIUS_IN_BLOCKS / ratio;
    }

    public SSAO.SSAOMode getSSAOMode() {
        if (this.ssaoMode == null) return SSAO.SSAOMode.AUTO;
        try {
            return SSAO.SSAOMode.valueOf(this.ssaoMode.toUpperCase(Locale.ROOT));
        } catch (Exception e) { return SSAO.SSAOMode.AUTO; }
    }

    public void setSSAOMode(SSAO.SSAOMode mode) {
        this.ssaoMode = mode.name().toLowerCase(Locale.ROOT);
    }

    private static VoxyConfig loadOrCreate() {
        if (VoxyCommon.isAvailable()) {
            var path = getConfigPath();
            if (Files.exists(path)) {
                try (FileReader reader = new FileReader(path.toFile())) {
                    var conf = GSON.fromJson(reader, VoxyConfig.class);
                    if (conf != null) {
                        conf.save();
                        return conf;
                    } else {
                        Logger.error("Failed to load voxy config, resetting");
                    }
                } catch (IOException e) {
                    Logger.error("Could not parse config", e);
                }
            }
            Logger.info("Config doesnt exist, creating new");
            var config = new VoxyConfig();
            config.save();
            return config;
        } else {
            var config = new VoxyConfig();
            config.enabled = false;
            config.enableRendering = false;
            return config;
        }
    }

    public void save() {
        if (!VoxyCommon.isAvailable()) {
            Logger.info("Not saving config since voxy is unavalible");
            return;
        }

        try {
            Files.writeString(getConfigPath(), GSON.toJson(this));
        } catch (IOException e) {
            Logger.error("Failed to write config file", e);
        }
    }

    private static Path getConfigPath() {
        return FabricLoader.getInstance()
                .getConfigDir()
                .resolve("voxy-config.json");
    }

    public boolean isRenderingEnabled() {
        return VoxyCommon.isAvailable() && this.enabled && this.enableRendering;
    }
}
