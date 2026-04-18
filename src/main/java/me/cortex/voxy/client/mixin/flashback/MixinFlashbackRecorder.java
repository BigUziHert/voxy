package me.cortex.voxy.client.mixin.flashback;

import me.cortex.voxy.client.VoxyClientInstance;
import me.cortex.voxy.client.compat.IFlashbackMeta;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.core.RegistryAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

@Pseudo
@Mixin(targets = "com.moulberry.flashback.record.Recorder", remap = false)
public class MixinFlashbackRecorder {
    @Inject(method = "<init>", at = @At("TAIL"))
    private void voxy$getStoragePath(RegistryAccess registryAccess, CallbackInfo retInf) {
        if (VoxyCommon.isAvailable()) {
            var instance = VoxyCommon.getInstance();
            if (instance instanceof VoxyClientInstance ci) {
                Object metadata = getMetadata();
                if (metadata instanceof IFlashbackMeta flashbackMeta) {
                    flashbackMeta.setVoxyPath(ci.getStorageBasePath().toFile());
                }
            }
        }
    }

    private Object getMetadata() {
        try {
            Field field = this.getClass().getDeclaredField("metadata");
            field.setAccessible(true);
            return field.get(this);
        } catch (ReflectiveOperationException e) {
            Logger.warn("Failed to access Flashback recorder metadata", e);
            return null;
        }
    }
}
