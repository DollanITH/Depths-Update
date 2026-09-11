/*
 *  This file is part of Cubic Chunks Mod, licensed under the MIT License (MIT).
 *
 *  Copyright (c) 2015-2021 OpenCubicChunks
 *  Copyright (c) 2015-2021 contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 */
package sayys.depthsupdate.mixin.mod.optifine;

import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sayys.depthsupdate.core.HeightManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

@Pseudo
@Mixin(targets = "net.optifine.render.ChunkVisibility")
public class MixinChunkVisibility {

    // Static state tracking with fallbacks
    private static int lastRenderDistance = -1;
    private static World lastWorld = null;
    private static int lastPlayerChunkX = Integer.MIN_VALUE;
    private static int lastPlayerChunkY = Integer.MIN_VALUE;
    private static int lastPlayerChunkZ = Integer.MIN_VALUE;

    // Cached results
    private static int cachedMaxY = 0;
    private static boolean cacheValid = false;

    // Mixin target fields
    @Shadow(remap = false)
    private static int iMaxStatic;

    @Shadow(remap = false)
    private static int iMaxStaticFinal;

    @Shadow(remap = false)
    private static int counter;

    @Shadow(remap = false)
    private static World worldLast;

    @Shadow(remap = false)
    private static int pcxLast;

    @Shadow(remap = false)
    private static int pczLast;

    /**
     * Override getMaxChunkY method to improve Optifine compatibility
     */
    @Inject(method = "getMaxChunkY", at = @At("HEAD"), cancellable = true, remap = false)
    private static void getMaxChunkYOptifineCompat(World world, Entity viewEntity, int renderDistanceChunks, CallbackInfoReturnable<Integer> cir) {
        // Early return for extended height worlds
        if (HeightManager.isExtended(world)) {
            cir.setReturnValue(Integer.MAX_VALUE - 1);
            return;
        }

        // Calculate player chunk coordinates
        int pcx = (int) viewEntity.posX >> 4;
        int pcy = (int) viewEntity.posY >> 4;
        int pcz = (int) viewEntity.posZ >> 4;

        // Check if we can use cached result
        if (cacheValid && renderDistanceChunks == lastRenderDistance &&
            world == lastWorld && Math.abs(pcx - lastPlayerChunkX) < 4 &&
            Math.abs(pcy - lastPlayerChunkY) < 4 &&
            Math.abs(pcz - lastPlayerChunkZ) < 4) {
            cir.setReturnValue(cachedMaxY);
            return;
        }

        // Update static variables for original method behavior
        if (world != worldLast || pcx != pcxLast || pcz != pczLast) {
            counter = 0;
            iMaxStaticFinal = HeightManager.getMaxY(world) >> 4;
            worldLast = world;
            pcxLast = pcx;
            pczLast = pcz;
        }

        // Simple fallback for performance
        if (renderDistanceChunks > 32) {
            cir.setReturnValue(255); // Vanilla maximum
            return;
        }

        // Use simplified approach for better compatibility
        int maxY = findMaxYSimple(world, pcx, pcy, pcz, renderDistanceChunks);

        // Update cache
        lastRenderDistance = renderDistanceChunks;
        lastWorld = world;
        lastPlayerChunkX = pcx;
        lastPlayerChunkY = pcy;
        lastPlayerChunkZ = pcz;
        cachedMaxY = maxY;
        cacheValid = true;

        cir.setReturnValue(maxY);
    }

    /**
     * Simplified method to find maximum Y coordinate within render distance
     */
    private static int findMaxYSimple(World world, int pcx, int pcy, int pcz, int renderDistance) {
        int maxY = 0;

        // Check chunks around player
        for (int cx = pcx - renderDistance; cx <= pcx + renderDistance; cx++) {
            for (int cz = pcz - renderDistance; cz <= pcz + renderDistance; cz++) {
                try {
                    Chunk chunk = world.getChunk(cx, cz);
                    if (chunk == null || isEmptyChunk(chunk)) {
                        continue;
                    }

                    // Get the highest Y in this chunk
                    int chunkMaxY = getChunkHighestY(chunk);
                    maxY = Math.max(maxY, chunkMaxY);

                    // Optimization: if we've reached world height, stop searching
                    if (maxY >= 255) {
                        return 255;
                    }
                } catch (Exception e) {
                    // Skip problematic chunks
                }
            }
        }

        return maxY;
    }

    /**
     * Check if chunk is empty using multiple methods
     */
    private static boolean isEmptyChunk(Chunk chunk) {
        try {
            // Method 1: isEmpty() method
            Method isEmpty = Chunk.class.getMethod("isEmpty");
            return (boolean) isEmpty.invoke(chunk);
        } catch (Exception e1) {
            try {
                // Method 2: BlockState count
                Method getBlockStateCount = Chunk.class.getMethod("getBlockStateCount");
                int count = (int) getBlockStateCount.invoke(chunk);
                return count == 0;
            } catch (Exception e2) {
                // If we can't determine, assume it's not empty
                return false;
            }
        }
    }

    /**
     * Get the highest Y coordinate in a chunk
     */
    private static int getChunkHighestY(Chunk chunk) {
        try {
            // Try to use chunk's getHeightmap if available
            Object heightmap = getChunkHeightmap(chunk);
            if (heightmap != null) {
                return invokeHeightmapMethod(heightmap, "getHighest");
            }
        } catch (Exception e) {
            // Fallback to using top filled segment
            return getTopFilledChunkY(chunk);
        }

        return getTopFilledChunkY(chunk);
    }

    /**
     * Get highest Y from top filled segment
     */
    private static int getTopFilledChunkY(Chunk chunk) {
        try {
            Method getTopFilledSegment = Chunk.class.getMethod("getTopFilledSegment");
            int topSection = (int) getTopFilledSegment.invoke(chunk);
            if (topSection >= 0) {
                return (topSection << 4) + 15;
            }
        } catch (Exception e) {
            // If reflection fails, try to get from chunk properties
        }

        // Final fallback to vanilla world height
        return 255;
    }

    /**
     * Reflection helper to get chunk heightmap
     */
    private static Object getChunkHeightmap(Chunk chunk) {
        try {
            // Try to access heightmap field
            Field heightmapField = Chunk.class.getDeclaredField("heightmap");
            heightmapField.setAccessible(true);
            return heightmapField.get(chunk);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Invoke method on heightmap object
     */
    private static int invokeHeightmapMethod(Object heightmap, String methodName) {
        try {
            java.lang.reflect.Method method = heightmap.getClass().getMethod(methodName);
            Object result = method.invoke(heightmap);
            if (result instanceof Number) {
                return ((Number) result).intValue();
            }
        } catch (Exception e) {
            // Ignore reflection errors
        }
        return 0;
    }

    /**
     * Reset cache when needed
     */
    @Inject(method = "reset", at = @At("HEAD"), remap = false)
    private static void resetCache(CallbackInfo ci) {
        cacheValid = false;
    }
}