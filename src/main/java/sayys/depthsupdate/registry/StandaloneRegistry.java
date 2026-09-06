package sayys.depthsupdate.registry;

import net.minecraft.block.SoundType;
import net.minecraft.client.renderer.block.model.ModelBakery;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundEvent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.common.registry.GameRegistry;

import sayys.depthsupdate.DepthsUpdateConfig;
import sayys.depthsupdate.Reference;
import sayys.depthsupdate.item.ItemSpyglass;

public class StandaloneRegistry {
    public static final Item spyglass = new ItemSpyglass();

    public static final SoundEvent spyglass_use = createSoundEvent("item.spyglass.use");
    public static final SoundEvent spyglass_stop = createSoundEvent("item.spyglass.stop");
    public static final SoundEvent deepslate_break = createSoundEvent("block.deepslate.break");
    public static final SoundEvent deepslate_step = createSoundEvent("block.deepslate.step");
    public static final SoundEvent deepslate_place = createSoundEvent("block.deepslate.place");
    public static final SoundEvent deepslate_hit = createSoundEvent("block.deepslate.hit");
    public static final SoundEvent deepslate_fall = createSoundEvent("block.deepslate.fall");
    public static final SoundEvent deepslate_bricks_break = createSoundEvent("block.deepslate_bricks.break");
    public static final SoundEvent deepslate_bricks_step = createSoundEvent("block.deepslate_bricks.step");
    public static final SoundEvent deepslate_bricks_place = createSoundEvent("block.deepslate_bricks.place");
    public static final SoundEvent deepslate_bricks_hit = createSoundEvent("block.deepslate_bricks.hit");
    public static final SoundEvent deepslate_bricks_fall = createSoundEvent("block.deepslate_bricks.fall");
    public static final SoundEvent tuff_break = createSoundEvent("block.tuff.break");
    public static final SoundEvent tuff_step = createSoundEvent("block.tuff.step");
    public static final SoundEvent tuff_place = createSoundEvent("block.tuff.place");
    public static final SoundEvent tuff_hit = createSoundEvent("block.tuff.hit");
    public static final SoundEvent tuff_fall = createSoundEvent("block.tuff.fall");
    public static final SoundEvent calcite_break = createSoundEvent("block.calcite.break");
    public static final SoundEvent calcite_step = createSoundEvent("block.calcite.step");
    public static final SoundEvent calcite_place = createSoundEvent("block.calcite.place");
    public static final SoundEvent calcite_hit = createSoundEvent("block.calcite.hit");
    public static final SoundEvent calcite_fall = createSoundEvent("block.calcite.fall");
    public static final SoundType DEEPSLATE = new SoundType(1.0f, 1.0f, deepslate_break, deepslate_step, deepslate_place, deepslate_hit, deepslate_fall);
    public static final SoundType DEEPSLATE_BRICKS = new SoundType(1.0f, 1.0f, deepslate_bricks_break, deepslate_bricks_step, deepslate_bricks_place, deepslate_bricks_hit, deepslate_bricks_fall);
    public static final SoundType TUFF = new SoundType(1.0f, 1.0f, tuff_break, tuff_step, tuff_place, tuff_hit, tuff_fall);
    public static final SoundType CALCITE = new SoundType(1.0f, 1.0f, calcite_break, calcite_step, calcite_place, calcite_hit, calcite_fall);

    public static final RegistrationFeature SPYGLASS_FEATURE = new RegistrationFeature(
        () -> DepthsUpdateConfig.REGISTRY.enableSpyglass
    ).add(spyglass, spyglass_use, spyglass_stop)
    .withModelOverrides(event -> {
        ModelBakery.registerItemVariants(spyglass, spyglass.getRegistryName(), new ResourceLocation(Reference.MOD_ID, "item/spyglass_3d"));
    })
    .withInit(() -> {
        GameRegistry.addShapedRecipe(new ResourceLocation(Reference.MOD_ID, "spyglass"), null, new ItemStack(spyglass),
            " A ", " I ", " I ",
            'A', AmethystRegistry.amethyst_shard,
            'I', DeepslateRegistry.copper_ingot);
    });

    private static SoundEvent createSoundEvent(String name) {
        ResourceLocation location = new ResourceLocation(Reference.MOD_ID, name);
        SoundEvent event = new SoundEvent(location);

        event.setRegistryName(location);

        return event;
    }
}
