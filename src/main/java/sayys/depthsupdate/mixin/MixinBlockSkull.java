//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by Fernflower decompiler)
//

package sayys.depthsupdate.mixin;

import net.minecraft.block.BlockSkull;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntitySkull;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import sayys.depthsupdate.core.HeightManager;

@Mixin({BlockSkull.class})
public abstract class MixinBlockSkull {
    @Redirect(
            method = {"checkWitherSpawn"},
            at = @At(
                    value = "INVOKE",
                    ordinal = 0,
                    target = "Lnet/minecraft/util/math/BlockPos;getY()I"
            )
    )
    private int depthsupdate$witherSpawnY(BlockPos instance, World worldIn, BlockPos pos, TileEntitySkull te) {
        return instance.getY() - HeightManager.getMinY(worldIn);
    }

    @Redirect(
            at = @At(
                    value = "INVOKE",
                    ordinal = 0,
                    target = "Lnet/minecraft/util/math/BlockPos;getY()I"
            ),
            method = {"canDispenserPlace"}
    )
    private int depthsupdate$dispenserPlaceY(BlockPos instance, World worldIn, BlockPos pos, ItemStack stack) {
        return instance.getY() - HeightManager.getMinY(worldIn);
    }
}
