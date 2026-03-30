package sayys.depthsupdate.client;

import com.cleanroommc.assetmover.AssetMoverAPI;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import sayys.depthsupdate.DepthsUpdateMod;

@SideOnly(Side.CLIENT)
public class AssetHandler {
    /**
     * Loads asset mappings from META-INF/assetmover.json and registers them with AssetMover.
     */
    public static void setup() {
        try (InputStream is = AssetHandler.class.getResourceAsStream("/META-INF/assetmover.json")) {
            if (is == null) {
                DepthsUpdateMod.LOGGER.error("Could not find META-INF/assetmover.json in the classpath.");

                return;
            }

            Map<String, Map<String, String>> data = new Gson().fromJson(
                new InputStreamReader(is, StandardCharsets.UTF_8),
                new TypeToken<Map<String, Map<String, String>>>(){}.getType()
            );

            if (data != null) {
                data.forEach((version, assets) -> {
                    AssetMoverAPI.fromMinecraft(version, assets);

                    DepthsUpdateMod.LOGGER.debug("Registered {} assets for version {} via AssetMover.", assets.size(), version);
                });
            }
        } catch (Exception e) {
            DepthsUpdateMod.LOGGER.error("An error occurred while parsing META-INF/assetmover.json", e);
        }
    }
}
