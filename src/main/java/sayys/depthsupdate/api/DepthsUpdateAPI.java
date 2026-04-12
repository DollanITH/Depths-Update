package sayys.depthsupdate.api;

import net.minecraft.world.World;

import sayys.depthsupdate.core.HeightManager;

public final class DepthsUpdateAPI {
    private DepthsUpdateAPI() {}

    /**
     * Returns height info for the given world's dimension.
     * Returns vanilla bounds (0-256) for non-extended dimensions.
     */
    public static HeightInfo getHeightInfo(World world) {
        return HeightManager.get(world);
    }

    /**
     * Returns height info for the given dimension ID.
     * Returns vanilla bounds (0-256) for non-extended dimensions.
     */
    public static HeightInfo getHeightInfo(int dimensionId) {
        return HeightManager.get(dimensionId);
    }

    /**
     * Whether the given world's dimension has extended height (beyond vanilla 0-256).
     */
    public static boolean isHeightExtended(World world) {
        return HeightManager.isExtended(world);
    }

    /**
     * Whether the given dimension ID has extended height (beyond vanilla 0-256).
     */
    public static boolean isHeightExtended(int dimensionId) {
        return HeightManager.isExtended(dimensionId);
    }
}
