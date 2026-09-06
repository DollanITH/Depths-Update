package sayys.depthsupdate.mixin.mod.worldedit;

import com.sk89q.worldedit.forge.ForgeWorld;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import sayys.depthsupdate.core.HeightManager;

import java.lang.ref.WeakReference;

@Mixin(ForgeWorld.class)
public class MixinForgeWorld {

    @Shadow
    @Final
    private WeakReference<World> worldRef;

    /**
     * @author 9
     * @reason 9
     */
    @Overwrite
    public int getMinY() {
        return HeightManager.isExtended(worldRef.get()) ? HeightManager.getMinY(worldRef.get()) : 0;
    }
}
