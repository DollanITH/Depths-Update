package sayys.depthsupdate.util;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;

import sayys.depthsupdate.DepthsUpdateConfig;
import sayys.depthsupdate.registry.DeepslateRegistry;

public class BlockUtils {
    private static IBlockState cachedDeepslateBlockState;
    private static IBlockState cachedCheeseDebugBlockState;
    private static IBlockState cachedSpaghettiDebugBlockState;
    private static IBlockState cachedRiverDebugBlockState;

    private BlockUtils() {}

    private static final java.util.Map<Block, Block> DEEPSLATE_ORE_MAP = new java.util.HashMap<>();

    static {}

    public static void initializeOreMap() {
        DEEPSLATE_ORE_MAP.clear();
        DEEPSLATE_ORE_MAP.put(Blocks.COAL_ORE, DeepslateRegistry.deepslate_coal_ore);
        DEEPSLATE_ORE_MAP.put(Blocks.IRON_ORE, DeepslateRegistry.deepslate_iron_ore);
        DEEPSLATE_ORE_MAP.put(Blocks.GOLD_ORE, DeepslateRegistry.deepslate_gold_ore);
        DEEPSLATE_ORE_MAP.put(Blocks.REDSTONE_ORE, DeepslateRegistry.deepslate_redstone_ore);
        DEEPSLATE_ORE_MAP.put(Blocks.LIT_REDSTONE_ORE, DeepslateRegistry.deepslate_redstone_ore);
        DEEPSLATE_ORE_MAP.put(Blocks.LAPIS_ORE, DeepslateRegistry.deepslate_lapis_ore);
        DEEPSLATE_ORE_MAP.put(Blocks.DIAMOND_ORE, DeepslateRegistry.deepslate_diamond_ore);
        DEEPSLATE_ORE_MAP.put(Blocks.EMERALD_ORE, DeepslateRegistry.deepslate_emerald_ore);
        DEEPSLATE_ORE_MAP.put(DeepslateRegistry.copper_ore, DeepslateRegistry.deepslate_copper_ore);
    }

    public static void clearCaches() {
        cachedDeepslateBlockState = null;
        cachedCheeseDebugBlockState = null;
        cachedSpaghettiDebugBlockState = null;
        cachedRiverDebugBlockState = null;
    }

    public static IBlockState getDeepslateBlockState() {
        if (cachedDeepslateBlockState != null) return cachedDeepslateBlockState;

        String blockName = DepthsUpdateConfig.deepslateBlock;
        Block block = Block.getBlockFromName(blockName);

        if (block == null || block == Blocks.AIR) {
            cachedDeepslateBlockState = DeepslateRegistry.deepslate.getDefaultState();
        } else {
            cachedDeepslateBlockState = block.getDefaultState();
        }

        return cachedDeepslateBlockState;
    }

    public static IBlockState getDebugBlockState(String blockName, Block fallback, IBlockState currentCache) {
        if (currentCache != null) return currentCache;

        Block block = Block.getBlockFromName(blockName);

        return (block == null || block == Blocks.AIR) ? fallback.getDefaultState() : block.getDefaultState();
    }

    public static IBlockState getCheeseDebugBlockState() {
        cachedCheeseDebugBlockState = getDebugBlockState(DepthsUpdateConfig.DEBUG.cheeseDebugBlock, Blocks.SPONGE, cachedCheeseDebugBlockState);

        return cachedCheeseDebugBlockState;
    }

    public static IBlockState getSpaghettiDebugBlockState() {
        cachedSpaghettiDebugBlockState = getDebugBlockState(DepthsUpdateConfig.DEBUG.spaghettiDebugBlock, Blocks.GLASS, cachedSpaghettiDebugBlockState);

        return cachedSpaghettiDebugBlockState;
    }

    public static IBlockState getRiverDebugBlockState() {
        cachedRiverDebugBlockState = getDebugBlockState(DepthsUpdateConfig.DEBUG.riverDebugBlock, Blocks.GLOWSTONE, cachedRiverDebugBlockState);

        return cachedRiverDebugBlockState;
    }

    public static IBlockState getDeepslateVariant(IBlockState oreState) {
        Block ore = oreState.getBlock();
        Block deepVariant = DEEPSLATE_ORE_MAP.get(ore);

        return deepVariant != null ? deepVariant.getDefaultState() : oreState;
    }

    public static boolean isDeepslate(IBlockState state) {
        if (state == null) return false;

        Block block = state.getBlock();

        return block == DeepslateRegistry.deepslate || (block.getRegistryName() != null && block.getRegistryName().toString().equals(DepthsUpdateConfig.deepslateBlock));
    }
}
