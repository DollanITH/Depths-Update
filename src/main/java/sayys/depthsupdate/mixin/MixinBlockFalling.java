package sayys.depthsupdate.mixin;

import net.minecraft.block.Block;
import net.minecraft.block.BlockFalling;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.item.EntityFallingBlock;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sayys.depthsupdate.core.HeightManager;

@Mixin(BlockFalling.class)
public abstract class MixinBlockFalling {

    @Inject(
        at = @At("HEAD"),
        method = "checkFallable(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;)V",
        cancellable = true
    )
    private void depthsupdate$checkFallableExtended(World worldIn, BlockPos pos, CallbackInfo ci) {
        if (HeightManager.isExtended(worldIn)) {
            ci.cancel();
            int minY = HeightManager.getMinY(worldIn);

            if ((worldIn.isAirBlock(pos.down()) || canFallThrough(worldIn.getBlockState(pos.down()))) && pos.getY() > minY) {
                if (!BlockFalling.fallInstantly && worldIn.isAreaLoaded(pos.add(-32, -32, -32), pos.add(32, 32, 32))) {
                    if (!worldIn.isRemote) {
                        EntityFallingBlock entityfallingblock = new EntityFallingBlock(worldIn, (double)pos.getX() + (double)0.5F, (double)pos.getY(), (double)pos.getZ() + (double)0.5F, worldIn.getBlockState(pos));
                        onStartFalling(entityfallingblock);
                        worldIn.spawnEntity(entityfallingblock);
                    }
                } else {
                    IBlockState state = worldIn.getBlockState(pos);
                    worldIn.setBlockToAir(pos);

                    BlockPos blockpos = pos.down();
                    while ((worldIn.isAirBlock(blockpos) || canFallThrough(worldIn.getBlockState(blockpos))) && blockpos.getY() > minY) {
                        blockpos = blockpos.down();
                    }

                    if (blockpos.getY() > minY) {
                        worldIn.setBlockState(blockpos.up(), state);
                    }
                }
            }
        }
    }

    @Shadow
    protected void onStartFalling(EntityFallingBlock fallingEntity) {
    }

    @Shadow
    public static boolean canFallThrough(IBlockState state) {
        Block block = state.getBlock();
        Material material = state.getMaterial();
        return block == Blocks.FIRE || material == Material.AIR || material == Material.WATER || material == Material.LAVA;
    }
}