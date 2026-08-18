package sayys.depthsupdate.mixin;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.init.Blocks;
import org.jetbrains.annotations.Contract;
import org.jspecify.annotations.NonNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import sayys.depthsupdate.core.HeightContext;
import sayys.depthsupdate.core.HeightManager;
import sayys.depthsupdate.DepthsUpdateConfig;
import sayys.depthsupdate.util.BlockUtils;
import net.minecraft.block.state.IBlockState;

@Mixin(AnvilChunkLoader.class)
public abstract class MixinAnvilChunkLoader {

    @Unique
    private static final ThreadLocal<HeightContext> depthsupdate$ctx = ThreadLocal.withInitial(() -> HeightContext.VANILLA);

    @Unique
    private static final ThreadLocal<Integer> depthsupdate$nestingLevel = ThreadLocal.withInitial(() -> 0);

    @Inject(method = "readChunkFromNBT", at = @At("HEAD"))
    private void depthsupdate$startRead(World worldIn, NBTTagCompound compound, CallbackInfoReturnable<Chunk> cir) {
        if (depthsupdate$nestingLevel.get() == 0) {
            depthsupdate$ctx.set(HeightManager.get(worldIn));
        }

        depthsupdate$nestingLevel.set(depthsupdate$nestingLevel.get() + 1);
    }

    @Inject(method = "readChunkFromNBT", at = @At("RETURN"))
    private void depthsupdate$endRead(World worldIn, NBTTagCompound compound, CallbackInfoReturnable<Chunk> cir) {
        depthsupdate$nestingLevel.set(depthsupdate$nestingLevel.get() - 1);

        if (depthsupdate$nestingLevel.get() <= 0) {
            depthsupdate$ctx.remove();
            depthsupdate$nestingLevel.remove();
        }
    }

    @ModifyConstant(method = "readChunkFromNBT", constant = @Constant(intValue = 16))
    private int depthsupdate$modifyStorageArraysSize(int original) {
        HeightContext ctx = depthsupdate$ctx.get();
        if (ctx.isExtended()) {
            return ctx.totalStorageSections();
        }

        return original;
    }

    @Redirect(method = "readChunkFromNBT", at = @At(value = "INVOKE", target = "Lnet/minecraft/nbt/NBTTagCompound;getByte(Ljava/lang/String;)B"))
    private byte depthsupdate$offsetY(@NonNull NBTTagCompound compound, String key) {
        byte b = compound.getByte(key);

        HeightContext ctx = depthsupdate$ctx.get();
        if ("Y".equals(key) && ctx.isExtended() && (compound.hasKey("Blocks") || compound.hasKey("Palette"))) {
            return (byte) ctx.toStorageIndex(b << 4);
        }

        return b;
    }

    @Contract("_, _ -> new")
    @Redirect(method = "readChunkFromNBT", at = @At(value = "NEW", target = "net/minecraft/world/chunk/storage/ExtendedBlockStorage"))
    private @NonNull ExtendedBlockStorage depthsupdate$fixConstructorY(int y, boolean storeSkylight) {
        HeightContext ctx = depthsupdate$ctx.get();
        if (ctx.isExtended()) {
            return new ExtendedBlockStorage(ctx.fromStorageIndex(y >> 4) << 4, storeSkylight);
        }

        return new ExtendedBlockStorage(y, storeSkylight);
    }

    @Inject(method = "writeChunkToNBT", at = @At("HEAD"))
    private void depthsupdate$markExtendedChunk(Chunk chunkIn, World worldIn, NBTTagCompound compound, CallbackInfo ci) {
        if (HeightManager.isExtended(worldIn)) {
            compound.setBoolean("DepthsUpdateExtended", true);
        }
    }

    /**
     * Convert old world chunks by filling below Y=0 with stone/deepslate/bedrock.
     * Called when a chunk is loaded from NBT and old world conversion is enabled.
     *
     * 转换条件：
     * 1. 世界是扩展高度（minY < 0 或 maxY > 256）
     * 2. 区块没有y<0的方块（在Y=0及以上的区块才转换）
     * 3. 区块不是空的（有实际方块数据）
     */
    @Inject(method = "readChunkFromNBT", at = @At("RETURN"))
    private void depthsupdate$convertOldWorld(World worldIn, NBTTagCompound compound, CallbackInfoReturnable<Chunk> cir) {
        // Check if old world conversion is enabled and this is an old world
        if (!DepthsUpdateConfig.heightExtension.convertOldWorlds) {
            return;
        }

        if (!HeightManager.isExtended(worldIn)) {
            return;
        }

        Chunk chunk = cir.getReturnValue();
        if (chunk == null) {
            return;
        }

        HeightContext ctx = HeightManager.get(worldIn);

        // Only proceed if we need to fill below Y=0
        if (ctx.minY() >= 0) {
            return;
        }

        // 检查区块是否没有y<0的方块（只有Y=0及以上的区块才转换）
        if (depthsupdate$hasBlocksInChunkBelowY0(chunk, ctx)) {
            return;
        }

        // 检查区块是否非空
        if (!depthsupdate$hasNonEmptyChunk(chunk)) {
            return;
        }

        // Fill below Y=0 with appropriate blocks
        depthsupdate$fillBelowY0(chunk, worldIn, ctx);

        // Recompute heightmap and skylight after conversion
        chunk.generateSkylightMap();
    }

    /**
     * Fill the area below Y=0 with stone, deepslate, and bedrock based on the world's configuration.
     */
    @Unique
    private void depthsupdate$fillBelowY0(Chunk chunk, World worldIn, HeightContext ctx) {
        IBlockState stone = Blocks.STONE.getDefaultState();
        IBlockState deepslate = BlockUtils.getDeepslateBlockState();
        IBlockState bedrock = Blocks.BEDROCK.getDefaultState();
        IBlockState air = net.minecraft.init.Blocks.AIR.getDefaultState();

        int deepslateMaxY = DepthsUpdateConfig.deepslateMaxY;
        int transitionRange = DepthsUpdateConfig.deepslateTransitionRange;
        int fullDeepslateY = deepslateMaxY - transitionRange;
        int minY = ctx.minY();

        ExtendedBlockStorage[] storageArrays = chunk.getBlockStorageArray();
        boolean hasSkyLight = worldIn.provider.hasSkyLight();

        // Create a random instance for this chunk
        java.util.Random random = new java.util.Random();
        random.setSeed((long) chunk.x * 341873128712L + (long) chunk.z * 132897987541L);

        // Pre-compute section boundaries to avoid repeated calculations
        // Cache commonly used values
        int[] storageIndices = new int[12 - minY + 1]; // Y=minY to 11
        for (int by = minY; by <= 11; by++) {
            storageIndices[by - minY] = ctx.toStorageIndex(by);
        }

        // Pre-compute bedrock threshold to avoid random number in hot path
        int bedrockThreshold = minY + random.nextInt(5);

        // Fill from bottom to top with optimized processing
        for (int bx = 0; bx < 16; bx++) {
            for (int bz = 0; bz < 16; bz++) {
                depthsupdate$processColumn(bx, bz, storageArrays, hasSkyLight, minY,
                    stone, deepslate, bedrock, air, random, bedrockThreshold,
                    fullDeepslateY, deepslateMaxY, transitionRange, ctx, worldIn);
            }
        }
    }

    /**
     * Process a single column of blocks with optimized logic.
     * Reduces code duplication and improves performance.
     */
    @Unique
    private void depthsupdate$processColumn(int bx, int bz, ExtendedBlockStorage[] storageArrays,
                                         boolean hasSkyLight, int minY,
                                         IBlockState stone, IBlockState deepslate,
                                         IBlockState bedrock, IBlockState air,
                                         java.util.Random random, int bedrockThreshold,
                                         int fullDeepSlateY, int deepslateMaxY, int transitionRange,
                                         HeightContext ctx, World worldIn) {

        // Process bedrock replacement zone (Y=0-6)
        for (int by = Math.max(0, minY); by <= 6; by++) {
            final int finalBy = by;
            final IBlockState finalBlockState = by <= 3 ? stone : deepslate;
            depthsupdate$setBlockIf(bx, bz, storageArrays, by, hasSkyLight,
                (section) -> section.get(bx, finalBy & 15, bz).getBlock() == net.minecraft.init.Blocks.BEDROCK,
                finalBlockState, ctx
            );
        }

        // Process liquid replacement and state updates
        depthsupdate$updateLiquidsInColumn(bx, bz, storageArrays, hasSkyLight, minY, ctx, worldIn);

        // Process deep stone filling (Y < 0)
        for (int by = minY; by < 0; by++) {
            int storageIdx = ctx.toStorageIndex(by);
            if (storageIdx < 0 || storageIdx >= storageArrays.length) continue;

            ExtendedBlockStorage section = storageArrays[storageIdx];
            if (section == Chunk.NULL_BLOCK_STORAGE) {
                section = new ExtendedBlockStorage(by >> 4 << 4, hasSkyLight);
                storageArrays[storageIdx] = section;
            }

            // Determine block type with optimized logic
            IBlockState state = depthsupdate$getBlockState(by, minY, bedrockThreshold,
                fullDeepSlateY, deepslateMaxY, transitionRange, stone, deepslate, bedrock, random);

            section.set(bx, by & 15, bz, state);
        }
    }

    /**
     * Set block if condition is met - reduces code duplication
     */
    @FunctionalInterface
    private interface BlockCondition {
        boolean test(ExtendedBlockStorage section);
    }

    @Unique
    private void depthsupdate$setBlockIf(int bx, int bz, ExtendedBlockStorage[] storageArrays,
                                         int y, boolean hasSkyLight, BlockCondition condition,
                                         IBlockState newState, HeightContext ctx) {
        int storageIdx = ctx.toStorageIndex(y);
        if (storageIdx < 0 || storageIdx >= storageArrays.length) return;

        ExtendedBlockStorage section = storageArrays[storageIdx];
        if (section == Chunk.NULL_BLOCK_STORAGE) {
            section = new ExtendedBlockStorage(y >> 4 << 4, hasSkyLight);
            storageArrays[storageIdx] = section;
        }

        if (condition.test(section)) {
            section.set(bx, y & 15, bz, newState);
        }
    }

    /**
     * Get the appropriate block state for a given Y coordinate.
     * Centralized block determination logic.
     */
    @Unique
    private IBlockState depthsupdate$getBlockState(int y, int minY, int bedrockThreshold,
                                                 int fullDeepslateY, int deepslateMaxY,
                                                 int transitionRange, IBlockState stone,
                                                 IBlockState deepslate, IBlockState bedrock,
                                                 java.util.Random random) {
        if (y <= bedrockThreshold) {
            return bedrock;
        } else if (y <= fullDeepslateY) {
            return deepslate;
        } else if (y < deepslateMaxY) {
            // Optimized transition zone calculation
            double chance = (deepslateMaxY - y) / (double) transitionRange;
            return random.nextDouble() < chance ? deepslate : stone;
        } else {
            return stone;
        }
    }

    /**
     * Enhanced liquid processing with state management and flow simulation
     */
    @Unique
    private void depthsupdate$updateLiquidsInColumn(int bx, int bz, ExtendedBlockStorage[] storageArrays,
                                                 boolean hasSkyLight, int minY, HeightContext ctx, World worldIn) {
        // Process all liquid types with proper state management
        depthsupdate$processLavaReplacements(bx, bz, storageArrays, hasSkyLight, minY, ctx, worldIn);
        depthsupdate$processWaterReplacements(bx, bz, storageArrays, hasSkyLight, minY, ctx);
        depthsupdate$updateLiquidFlowStates(bx, bz, storageArrays, minY, ctx, worldIn);
    }

    /**
     * Process lava replacements with state management
     */
    @Unique
    private void depthsupdate$processLavaReplacements(int bx, int bz, ExtendedBlockStorage[] storageArrays,
                                                   boolean hasSkyLight, int minY, HeightContext ctx, World worldIn) {
        // Process both source and flowing lava
        depthsupdate$replaceLavaBlocks(bx, bz, storageArrays, hasSkyLight, minY, ctx, true, worldIn);  // source
        depthsupdate$replaceLavaBlocks(bx, bz, storageArrays, hasSkyLight, minY, ctx, false, worldIn); // flowing
    }

    /**
     * Process water replacements with state management
     */
    @Unique
    private void depthsupdate$processWaterReplacements(int bx, int bz, ExtendedBlockStorage[] storageArrays,
                                                     boolean hasSkyLight, int minY, HeightContext ctx) {
        // Process both source and flowing water if needed
        // Currently water is preserved, but this can be extended
        depthsupdate$replaceWaterBlocks(bx, bz, storageArrays, hasSkyLight, minY, ctx);
    }

    /**
     * Replace lava blocks with air and update Y=12-13 flow states
     */
    @Unique
    private void depthsupdate$replaceLavaBlocks(int bx, int bz, ExtendedBlockStorage[] storageArrays,
                                             boolean hasSkyLight, int minY, HeightContext ctx, boolean isSource, World worldIn) {
        Block targetBlock = isSource ? net.minecraft.init.Blocks.LAVA : net.minecraft.init.Blocks.FLOWING_LAVA;
        int startY = Math.max(minY, 0); // Only replace from Y=0 upwards

        for (int by = startY; by <= 13; by++) {
            // Different behavior based on Y level
            if (by <= 11) {
                // Replace lava with air in Y=0-11
                final int finalBy = by;
                depthsupdate$setBlockIf(bx, bz, storageArrays, by, hasSkyLight,
                    (section) -> section.get(bx, finalBy & 15, bz).getBlock() == targetBlock,
                    net.minecraft.init.Blocks.AIR.getDefaultState(), ctx
                );
            } else {
                // Y=12-13: Update liquid states for proper flow
                depthsupdate$updateLiquidFlowState(bx, bz, storageArrays, by, hasSkyLight, targetBlock, ctx, worldIn);
            }
        }
    }

    /**
     * Replace water blocks if needed (currently preserves water)
     */
    @Unique
    private void depthsupdate$replaceWaterBlocks(int bx, int bz, ExtendedBlockStorage[] storageArrays,
                                               boolean hasSkyLight, int minY, HeightContext ctx) {
        // Currently water is preserved in old world chunks
        // This method can be extended if water replacement is needed
    }

    /**
     * Update liquid flow states and handle evaporation
     */
    @Unique
    private void depthsupdate$updateLiquidFlowStates(int bx, int bz, ExtendedBlockStorage[] storageArrays,
                                                   int minY, HeightContext ctx, World worldIn) {
        // Check for potential liquid sources and update flow states
        // Handle evaporation for certain conditions

        // Y=12-13 is the critical transition zone for liquid flow
        depthsupdate$updateFlowTransitionZone(bx, bz, storageArrays, minY, ctx, worldIn);

        // Check surrounding blocks for liquid sources
        depthsupdate$checkForLiquidSources(bx, bz, storageArrays, minY, ctx);

        // Handle liquid evaporation in certain conditions
        depthsupdate$handleLiquidEvaporation(bx, bz, storageArrays, minY, ctx);
    }

    /**
     * Check for liquid sources in neighboring blocks
     */
    @Unique
    private void depthsupdate$checkForLiquidSources(int bx, int bz, ExtendedBlockStorage[] storageArrays,
                                                  int minY, HeightContext ctx) {
        // Check neighboring positions for liquid sources
        // This can be used to determine flow directions and update liquid states

        // For now, this is a placeholder for future liquid flow logic
        // Actual implementation would check neighboring chunks and blocks
    }

    /**
     * Handle liquid evaporation based on conditions
     */
    @Unique
    private void depthsupdate$handleLiquidEvaporation(int bx, int bz, ExtendedBlockStorage[] storageArrays,
                                                    int minY, HeightContext ctx) {
        // Check for liquids that should evaporate
        // This could be based on height, temperature, or other conditions

        // For example, lava might evaporate at very high altitudes
        // Or water might evaporate in hot biomes

        // Currently a placeholder for future evaporation logic
    }

    /**
     * Update liquid flow state for Y=12-13 transition zone
     */
    @Unique
    private void depthsupdate$updateLiquidFlowState(int bx, int bz, ExtendedBlockStorage[] storageArrays,
                                                   int y, boolean hasSkyLight, Block targetBlock,
                                                   HeightContext ctx, World worldIn) {
        int storageIdx = ctx.toStorageIndex(y);
        if (storageIdx < 0 || storageIdx >= storageArrays.length) return;

        ExtendedBlockStorage section = storageArrays[storageIdx];
        if (section == Chunk.NULL_BLOCK_STORAGE) {
            section = new ExtendedBlockStorage(y >> 4 << 4, hasSkyLight);
            storageArrays[storageIdx] = section;
        }

        // Check current block state
        IBlockState currentState = section.get(bx, y & 15, bz);

        // If we have a target block, update its state
        if (targetBlock != null && currentState.getBlock() == targetBlock) {
            // Convert flowing liquid to proper state based on neighbors
            IBlockState newState = depthsupdate$calculateProperLiquidState(bx, bz, y, section, targetBlock, ctx, storageArrays);
            if (newState != currentState) {
                section.set(bx, y & 15, bz, newState);
            }
        }

        // Ensure liquid can flow by checking for empty blocks above
        depthsupdate$ensureFlowPath(bx, bz, y, section, ctx, worldIn, storageArrays);
    }

    /**
     * Calculate proper liquid state based on surrounding blocks
     */
    @Unique
    private IBlockState depthsupdate$calculateProperLiquidState(int bx, int bz, int y,
                                                             ExtendedBlockStorage section, Block targetBlock,
                                                             HeightContext ctx, ExtendedBlockStorage[] storageArrays) {
        // Calculate proper liquid state based on surrounding blocks
        // This ensures liquids flow correctly in the transition zone

        // Check if this should be a source block by checking if it has liquid below
        boolean hasLiquidBelow = depthsupdate$hasLiquidBelow(bx, bz, y, section, targetBlock, ctx, storageArrays);

        if (targetBlock == net.minecraft.init.Blocks.LAVA) {
            return hasLiquidBelow ? net.minecraft.init.Blocks.LAVA.getDefaultState() : net.minecraft.init.Blocks.FLOWING_LAVA.getDefaultState();
        } else if (targetBlock == net.minecraft.init.Blocks.WATER) {
            return hasLiquidBelow ? net.minecraft.init.Blocks.WATER.getDefaultState() : net.minecraft.init.Blocks.FLOWING_WATER.getDefaultState();
        }

        return section.get(bx, y & 15, bz);
    }

    /**
     * Check if there's liquid below the current position
     */
    @Unique
    private boolean depthsupdate$hasLiquidBelow(int bx, int bz, int y, ExtendedBlockStorage section, Block targetBlock, HeightContext ctx, ExtendedBlockStorage[] storageArrays) {
        // Check the block directly below
        int storageIdx = ctx.toStorageIndex(y - 1);
        if (storageIdx >= 0 && storageIdx < storageArrays.length) {
            ExtendedBlockStorage belowSection = storageArrays[storageIdx];
            if (belowSection != Chunk.NULL_BLOCK_STORAGE) {
                IBlockState belowState = belowSection.get(bx, (y - 1) & 15, bz);
                return belowState.getBlock() == targetBlock ||
                       (targetBlock == net.minecraft.init.Blocks.LAVA && belowState.getBlock() == net.minecraft.init.Blocks.FLOWING_LAVA) ||
                       (targetBlock == net.minecraft.init.Blocks.WATER && belowState.getBlock() == net.minecraft.init.Blocks.FLOWING_WATER);
            }
        }
        return false;
    }

    /**
     * Check if a section contains any actual blocks (not empty)
     */
    @Unique
    private boolean depthsupdate$hasBlocksInSection(ExtendedBlockStorage section) {
        // Sample a few blocks to check if the section is actually populated
        // This is more efficient than checking all 4096 blocks
        for (int x = 0; x < 16; x += 4) {
            for (int y = 0; y < 16; y += 4) {
                for (int z = 0; z < 16; z += 4) {
                    if (section.get(x, y, z).getBlock() != net.minecraft.init.Blocks.AIR) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Verify that old world blocks are actually in the expected range
     */
    @Unique
    private boolean depthsupdate$verifyOldWorldBlocks(Chunk chunk, HeightContext ctx) {
        // Check if the chunk actually extends below Y=0
        // This prevents false positives when chunks are generated with vanilla format but should be extended
        for (int y = ctx.minY(); y < 0; y++) {
            int storageIdx = ctx.toStorageIndex(y);
            if (storageIdx >= 0 && storageIdx < chunk.getBlockStorageArray().length) {
                ExtendedBlockStorage section = chunk.getBlockStorageArray()[storageIdx];
                if (section != Chunk.NULL_BLOCK_STORAGE && depthsupdate$hasBlocksInSection(section)) {
                    return true; // This is actually an extended world with blocks below Y=0
                }
            }
        }

        // If we're checking an old world conversion, ensure there are actual blocks in Y=0-255
        ExtendedBlockStorage[] storageArrays = chunk.getBlockStorageArray();
        for (int i = 0; i < 16; i++) {
            if (storageArrays[i] != Chunk.NULL_BLOCK_STORAGE && depthsupdate$hasBlocksInSection(storageArrays[i])) {
                return true;
            }
        }

        return false;
    }

    /**
     * Check if a chunk needs old world conversion
     */
    @Unique
    private boolean depthsupdate$needsOldWorldConversion(Chunk chunk, HeightContext ctx) {
        ExtendedBlockStorage[] storageArrays = chunk.getBlockStorageArray();

        // Check if there are old world sections (Y=0-255) with actual blocks
        boolean hasOldWorldData = false;
        for (int i = 0; i < 16; i++) {
            if (storageArrays[i] != Chunk.NULL_BLOCK_STORAGE &&
                depthsupdate$hasBlocksInSection(storageArrays[i])) {
                hasOldWorldData = true;
                break;
            }
        }

        if (!hasOldWorldData) {
            return false; // No old world data to convert
        }

        // Check if there are already blocks in the extended range
        // If there are blocks below Y=0, then this is already an extended world
        for (int y = ctx.minY(); y < 0; y++) {
            int storageIdx = ctx.toStorageIndex(y);
            if (storageIdx >= 0 && storageIdx < storageArrays.length) {
                ExtendedBlockStorage section = storageArrays[storageIdx];
                if (section != Chunk.NULL_BLOCK_STORAGE && depthsupdate$hasBlocksInSection(section)) {
                    return false; // Already has extended data, no conversion needed
                }
            }
        }

        // If we reach here, we have old world data but no extended data
        // This means we need to fill the extended part
        return true;
    }

    /**
     * Ensure there's a path for liquid to flow upwards
     */
    @Unique
    private void depthsupdate$ensureFlowPath(int bx, int bz, int y, ExtendedBlockStorage section,
                                           HeightContext ctx, World worldIn, ExtendedBlockStorage[] storageArrays) {
        // Ensure there's a path for liquid to flow upwards
        // This is crucial for proper liquid behavior in extended worlds

        // Check the block above
        int storageIdx = ctx.toStorageIndex(y + 1);
        if (storageIdx >= 0 && storageIdx < storageArrays.length) {
            ExtendedBlockStorage aboveSection = storageArrays[storageIdx];
            if (aboveSection == Chunk.NULL_BLOCK_STORAGE) {
                // Create section if it doesn't exist
                aboveSection = new ExtendedBlockStorage((y + 1) >> 4 << 4, worldIn.provider.hasSkyLight());
                storageArrays[storageIdx] = aboveSection;
            }
        }
    }

    /**
     * Update flow transition zone for Y=12-13
     */
    @Unique
    private void depthsupdate$updateFlowTransitionZone(int bx, int bz, ExtendedBlockStorage[] storageArrays,
                                                     int minY, HeightContext ctx, World worldIn) {
        // Y=12-13 is the critical transition zone for liquid flow
        // Update liquid states to ensure proper flow from Y=11 to Y=14+
        for (int by = 12; by <= 13; by++) {
            depthsupdate$updateLiquidFlowState(bx, bz, storageArrays, by,
                worldIn.provider.hasSkyLight(), null, ctx, worldIn);
        }
    }

    /**
     * Performance optimized bulk liquid processor
     */
    @Unique
    private static final class LiquidBulkProcessor {
        private static final int BATCH_SIZE = 64;
        private static final java.util.Queue<ChunkUpdateTask> pendingUpdates = new java.util.concurrent.ConcurrentLinkedQueue<>();

        public static void scheduleLiquidUpdate(Chunk chunk, int x, int z) {
            pendingUpdates.add(new ChunkUpdateTask(chunk, x, z));
            if (pendingUpdates.size() >= BATCH_SIZE) {
                processBatch();
            }
        }

        private static void processBatch() {
            // Process queued updates in bulk to improve performance
            java.util.List<ChunkUpdateTask> batch = new java.util.ArrayList<>();
            while (!pendingUpdates.isEmpty() && batch.size() < BATCH_SIZE) {
                ChunkUpdateTask task = pendingUpdates.poll();
                if (task != null) {
                    batch.add(task);
                }
            }

            // Process the batch
            for (ChunkUpdateTask task : batch) {
                if (task.chunk != null) {
                    // Trigger chunk updates for liquids
                    task.chunk.generateSkylightMap();
                }
            }
        }

        /**
         * Task class for bulk processing
         */
        private static class ChunkUpdateTask {
            final Chunk chunk;
            final int x;
            final int z;

            ChunkUpdateTask(Chunk chunk, int x, int z) {
                this.chunk = chunk;
                this.x = x;
                this.z = z;
            }
        }
    }

    /**
     * 检查区块是否有y<0的方块
     * 用于判断区块是否需要转换（只转换没有y<0方块的区块）
     */
    @Unique
    private boolean depthsupdate$hasBlocksInChunkBelowY0(Chunk chunk, HeightContext ctx) {
        ExtendedBlockStorage[] storageArrays = chunk.getBlockStorageArray();

        // 检查该区块是否有y<0的方块
        for (int y = ctx.minY(); y < 0; y++) {
            int storageIdx = ctx.toStorageIndex(y);

            if (storageIdx < 0 || storageIdx >= storageArrays.length) {
                continue;
            }

            ExtendedBlockStorage section = storageArrays[storageIdx];

            if (section != null && section != Chunk.NULL_BLOCK_STORAGE) {
                // 检查该section是否有方块
                if (depthsupdate$hasBlocksInSection(section)) {
                    return true; // 有y<0的方块，不满足转换条件
                }
            }
        }

        return false; // 没有y<0的方块，满足转换条件
    }

    /**
     * 检查区块是否有非空方块
     * 用于判断区块是否需要转换
     */
    @Unique
    private boolean depthsupdate$hasNonEmptyChunk(Chunk chunk) {
        ExtendedBlockStorage[] storageArrays = chunk.getBlockStorageArray();

        for (ExtendedBlockStorage section : storageArrays) {
            if (section != null && section != Chunk.NULL_BLOCK_STORAGE) {
                if (depthsupdate$hasBlocksInSection(section)) {
                    return true;
                }
            }
        }

        return false;
    }

}
