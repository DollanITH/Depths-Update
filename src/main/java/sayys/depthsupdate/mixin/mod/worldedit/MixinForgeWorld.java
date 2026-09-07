package sayys.depthsupdate.mixin.mod.worldedit;

import com.sk89q.worldedit.forge.ForgeWorld;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
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

    @Dynamic
    @Inject(method = "getMinY", at = @At(value = "HEAD"), cancellable = true, remap = false)
    public void getMinY(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(HeightManager.isExtended(worldRef.get()) ? HeightManager.getMinY(worldRef.get()) : 0);
    }

    @Inject(method = "getMaxY", at = @At(value = "HEAD"), cancellable = true)
    public void getMaxY(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(HeightManager.isExtended(worldRef.get()) ? HeightManager.getMaxY(worldRef.get()) - 1 : getWorld().getHeight() - 1);    }
}
