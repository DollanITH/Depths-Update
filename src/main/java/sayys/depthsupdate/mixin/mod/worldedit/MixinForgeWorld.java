package sayys.depthsupdate.mixin.mod.worldedit;

import com.sk89q.worldedit.forge.ForgeWorld;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sayys.depthsupdate.core.HeightManager;

import java.lang.ref.WeakReference;

@Mixin(ForgeWorld.class)
public abstract class MixinForgeWorld {

    @Shadow
    @Final
    private WeakReference<World> worldRef;

    @Shadow
    public abstract World getWorld();

    @Inject(method = "getMinY", at = @At(value = "HEAD"), cancellable = true, remap = false)
    public void getMinY(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(HeightManager.isExtended(worldRef.get()) ? HeightManager.getMinY(worldRef.get()) : 0);
        cir.cancel();
    }

    /**
     * @author 1
     * @reason 1
     */
    @Overwrite
    public int getMaxY() {
        if (!HeightManager.isExtended(worldRef.get())) {
            return 255;
        }
        return HeightManager.getMaxY(worldRef.get());
    }
}
