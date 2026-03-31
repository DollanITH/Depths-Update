package sayys.depthsupdate;

import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLConstructionEvent;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.registry.GameRegistry;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NonNull;

import sayys.depthsupdate.client.AssetHandler;
import sayys.depthsupdate.registry.RegistryHandler;
import sayys.depthsupdate.world.generation.AmethystGeodeGenerator;
import sayys.depthsupdate.world.generation.DripstoneCavesGenerator;
import sayys.depthsupdate.world.generation.LushCavesGenerator;

@Mod(
    modid = Reference.MOD_ID,
    name = Reference.MOD_NAME,
    version = Reference.VERSION
)
public class DepthsUpdateMod {
    public static final Logger LOGGER = LogManager.getLogger(Reference.MOD_NAME);

    /**
     * <a href="https://cleanroommc.com/wiki/forge-mod-development/event#overview">
     * Take a look at how many FMLStateEvents you can listen to via
     * the @Mod.EventHandler annotation here
     * </a>
     */
    @Mod.EventHandler
    @SideOnly(Side.CLIENT)
    public void construct(@NonNull FMLConstructionEvent event) {
        AssetHandler.setup();
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        RegistryHandler.init();

        LushCavesGenerator.register();
        DripstoneCavesGenerator.register();
        AmethystGeodeGenerator.register();
    }
}
