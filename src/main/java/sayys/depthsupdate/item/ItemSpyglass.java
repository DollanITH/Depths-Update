package sayys.depthsupdate.item;

import javax.annotation.Nullable;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.EnumAction;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundCategory;
import net.minecraft.world.World;
import net.minecraft.item.IItemPropertyGetter;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import sayys.depthsupdate.Reference;
import sayys.depthsupdate.registry.StandaloneRegistry;

public class ItemSpyglass extends Item {
    public ItemSpyglass() {
        this.setRegistryName(Reference.MOD_ID, "spyglass");
        this.setTranslationKey("spyglass");
        this.setMaxStackSize(1);
        this.setCreativeTab(CreativeTabs.TOOLS);

        this.addPropertyOverride(new ResourceLocation(Reference.MOD_ID, "in_hand"), new IItemPropertyGetter() {
            @Override
            @SideOnly(Side.CLIENT)
            public float apply(ItemStack stack, @Nullable World worldIn, @Nullable EntityLivingBase entityIn) {
                return entityIn != null && worldIn != null ? 1.0F : 0.0F;
            }
        });
    }

    @Override
    public int getMaxItemUseDuration(ItemStack stack) {
        return 72000;
    }

    @Override
    public EnumAction getItemUseAction(ItemStack stack) {
        return EnumAction.NONE;
    }

    @Override
    public ActionResult<ItemStack> onItemRightClick(World worldIn, EntityPlayer playerIn, EnumHand handIn) {
        ItemStack itemstack = playerIn.getHeldItem(handIn);
        playerIn.setActiveHand(handIn);

        if (!worldIn.isRemote) {
            worldIn.playSound(null, playerIn.posX, playerIn.posY, playerIn.posZ, StandaloneRegistry.spyglass_use, SoundCategory.PLAYERS, 1.0F, 1.0F);
        }

        return new ActionResult<>(EnumActionResult.SUCCESS, itemstack);
    }

    @Override
    public void onPlayerStoppedUsing(ItemStack stack, World worldIn, EntityLivingBase entityLiving, int timeLeft) {
        if (!worldIn.isRemote) {
            worldIn.playSound(null, entityLiving.posX, entityLiving.posY, entityLiving.posZ, StandaloneRegistry.spyglass_stop, SoundCategory.PLAYERS, 1.0F, 1.0F);
        }
    }
}
