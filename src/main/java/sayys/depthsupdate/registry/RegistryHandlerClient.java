package sayys.depthsupdate.registry;

import net.minecraft.client.renderer.block.statemap.StateMap;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import sayys.depthsupdate.Reference;
import sayys.depthsupdate.block.BlockPointedDripstone;

@SideOnly(Side.CLIENT)
@Mod.EventBusSubscriber(modid = Reference.MOD_ID, value = Side.CLIENT)
public class RegistryHandlerClient {
    @SubscribeEvent
    public static void registerModels(ModelRegistryEvent event) {
        RegistryHandler.registerModelsCommon(event);

        // Dripstone needs a custom state mapper that ignores the WATERLOGGED property.
        // StateMap is a vanilla client-only class, so this must stay in a client-only class.
        if (DeepslateRegistry.DRIPSTONE_FEATURE.isEnabled()) {
            ModelLoader.setCustomStateMapper(DeepslateRegistry.pointed_dripstone,
                    (new StateMap.Builder()).ignore(BlockPointedDripstone.WATERLOGGED).build());
        }
    }
}
