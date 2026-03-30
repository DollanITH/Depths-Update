package sayys.depthsupdate.registry;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.client.renderer.block.model.ModelBakery;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.client.renderer.block.statemap.StateMap;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundEvent;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.registry.GameRegistry;
import net.minecraftforge.oredict.OreDictionary;
import net.minecraftforge.registries.IForgeRegistryEntry;

import sayys.depthsupdate.DepthsUpdateConfig;
import sayys.depthsupdate.Reference;
import sayys.depthsupdate.block.BlockModSlab;
import sayys.depthsupdate.block.BlockPointedDripstone;

@Mod.EventBusSubscriber(modid = Reference.MOD_ID)
public class RegistryHandler {
    private static final List<RegistrationFeature> FEATURES = new ArrayList<>();

    static {
        FEATURES.add(DeepslateRegistry.DEEPSLATE_FAMILY);
        FEATURES.add(DeepslateRegistry.DRIPSTONE_FEATURE);
        FEATURES.add(DeepslateRegistry.CALCITE_FEATURE);
        FEATURES.add(DeepslateRegistry.TUFF_FEATURE);
        FEATURES.add(DeepslateRegistry.SMOOTH_BASALT_FEATURE);
        FEATURES.add(AmethystRegistry.AMETHYST_FEATURE);
        FEATURES.add(PlantRegistry.MOSS_FEATURE);
        FEATURES.add(PlantRegistry.ROOTED_DIRT_FEATURE);
        FEATURES.add(PlantRegistry.AZALEA_FEATURE);
        FEATURES.add(PlantRegistry.SPORE_BLOSSOM_FEATURE);
        FEATURES.add(PlantRegistry.DRIPLEAF_FEATURE);
        FEATURES.add(PlantRegistry.VINE_FEATURE);
        FEATURES.add(StandaloneRegistry.SPYGLASS_FEATURE);
    }

    @SubscribeEvent
    public static void registerBlocks(RegistryEvent.Register<Block> event) {
        FEATURES.forEach(f -> f.registerBlocks(event));
    }

    @SubscribeEvent
    public static void registerItems(RegistryEvent.Register<Item> event) {
        FEATURES.forEach(f -> f.registerItems(event));
    }

    @SubscribeEvent
    public static void registerSounds(RegistryEvent.Register<SoundEvent> event) {
        FEATURES.forEach(f -> f.registerSounds(event));
    }

    @SubscribeEvent
    public static void registerModels(ModelRegistryEvent event) {
        FEATURES.forEach(f -> f.registerModels(event));
    }

    public static void init() {
        FEATURES.forEach(RegistrationFeature::init);
    }
}
