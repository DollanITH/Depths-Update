package sayys.depthsupdate.mixin.mod.worldedit;

import com.sk89q.worldedit.forge.ForgeWorld;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import sayys.depthsupdate.core.HeightManager;

import java.lang.ref.WeakReference;

@Mixin(value = ForgeWorld.class, remap = false)
public class MixinForgeWorld {

    @Shadow
    @Final
    private WeakReference<World> worldRef;

    /**
     * @author 1
     * @reason 1
     */
    @Overwrite
    public int getMinY() {
            if (HeightManager.isExtended(worldRef.get( ))) {
                return HeightManager.getMinY(worldRef.get());
            }

            return 0;
        }
}
