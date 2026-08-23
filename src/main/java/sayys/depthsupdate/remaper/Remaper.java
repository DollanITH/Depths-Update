package sayys.depthsupdate.remaper;

import net.minecraft.util.datafix.FixTypes;
import net.minecraftforge.common.util.CompoundDataFixer;
import net.minecraftforge.common.util.ModFixs;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import sayys.depthsupdate.Reference;

@Mod.EventBusSubscriber
public class Remaper {

    public static void registerFixable() {
        CompoundDataFixer fixer = FMLCommonHandler.instance().getDataFixer();
        ModFixs modFixs = fixer.init(Reference.MOD_ID,1340);
        modFixs.registerFix(FixTypes.CHUNK, BlockFlatteningDefinitions.createBlockFlattening());
        modFixs.registerFix(FixTypes.ITEM_INSTANCE, ItemFlatteningDefinitions.createItemFlattening());
    }

    public Remaper() {
    }

}
