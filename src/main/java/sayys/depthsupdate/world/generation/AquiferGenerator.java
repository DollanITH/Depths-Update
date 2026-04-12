package sayys.depthsupdate.world.generation;

import java.util.Arrays;
import java.util.Random;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkPrimer;

import sayys.depthsupdate.DepthsUpdateConfig;
import sayys.depthsupdate.core.HeightContext;
import sayys.depthsupdate.core.HeightManager;
import sayys.depthsupdate.util.BlockUtils;
import sayys.depthsupdate.world.generation.noise.sponge.module.source.Perlin;

/**
 * Backport of Minecraft's Aquifer system. Instead of integrating into a density
 * function pipeline, this operates as a post-carving fill pass on ChunkPrimer. After noise
 * caves carve air pockets, this scans carved regions and fills them with
 * water or lava at locally-varying levels.
 *
 * The algorithm uses a Voronoi-like 3D grid where each cell has a random fluid center
 * with its own fluid level and type (water/lava). Adjacent cells with different fluid levels
 * create stone barriers between them.
 */
public class AquiferGenerator {
    // Grid layout
    private static final int X_SPACING = 16;
    private static final int Y_SPACING = 12;
    private static final int Z_SPACING = 16;
    private static final int X_RANGE = 10;
    private static final int Y_RANGE = 9;
    private static final int Z_RANGE = 10;

    // Default offsets for Y ranges (relative to world bounds)
    private static final int DEFAULT_MIN_Y_OFFSET = 4;  // minY + 4
    private static final int DEFAULT_MAX_Y = 30;
    // Lava thresholds as fraction of negative Y range
    private static final double LAVA_POSSIBLE_FRACTION = 0.15; // ~15% up from minY
    private static final double LAVA_ALWAYS_FRACTION = 0.9;    // ~90% down from 0

    // Similarity threshold for barriers
    private static final double SIMILARITY_THRESHOLD = 25.0;

    // Noise instances
    private final Perlin barrierNoise;
    private final Perlin floodednessNoise;
    private final Perlin spreadNoise;
    private final Perlin lavaNoise;

    // Offsets for deterministic per-world positioning
    private final double offsetX;
    private final double offsetZ;

    // Per-world Y bounds (computed from HeightContext)
    private final int aquiferMinY;
    private final int aquiferMaxY;
    private final int lavaPossibleY;
    private final int lavaAlwaysY;

    // Cached fluid status grid (dynamically sized per chunk)
    private long[] locationCache;
    private int[] fluidLevelCache;
    private boolean[] fluidTypeCache; // true = lava, false = water
    private boolean[] cacheValid;

    // Grid dimensions for current chunk
    private int minGridX, minGridY, minGridZ;
    private int gridSizeX, gridSizeY, gridSizeZ;

    private final long worldSeed;

    public AquiferGenerator(World world) {
        this.worldSeed = world.getSeed();
        Random rand = new Random(worldSeed);

        this.offsetX = rand.nextDouble() * 100000.0;
        this.offsetZ = rand.nextDouble() * 100000.0;

        // Compute Y bounds from world height config
        HeightContext ctx = HeightManager.get(world);
        this.aquiferMinY = ctx.minY() + DEFAULT_MIN_Y_OFFSET;
        this.aquiferMaxY = Math.min(DEFAULT_MAX_Y, ctx.maxY() - 1);
        // Lava thresholds scale with negative Y range
        int negRange = 0 - ctx.minY(); // how many blocks below Y=0
        this.lavaPossibleY = ctx.minY() + (int)(negRange * LAVA_POSSIBLE_FRACTION);
        this.lavaAlwaysY = ctx.minY() + (int)(negRange * (1.0 - LAVA_ALWAYS_FRACTION));

        // Barrier noise - creates stone walls between aquifer bodies
        this.barrierNoise = createNoise((int) worldSeed + 10000, 2, 0.5, 1.0);

        // Floodedness noise - determines if an area is flooded at all
        this.floodednessNoise = createNoise((int) worldSeed + 10001, 2, 0.5, 1.0);

        // Spread noise - randomizes the fluid surface level
        this.spreadNoise = createNoise((int) worldSeed + 10002, 2, 0.5, 1.0);

        // Lava noise - determines if a body is lava instead of water
        this.lavaNoise = createNoise((int) worldSeed + 10003, 1, 0.5, 1.0);
    }

    private static Perlin createNoise(int seed, int octaves, double persistence, double frequency) {
        Perlin perlin = new Perlin();
        perlin.setSeed(seed);
        perlin.setOctaveCount(octaves);
        perlin.setPersistence(persistence);
        perlin.setFrequency(frequency);

        return perlin;
    }

    /**
     * Runs the aquifer pass on a chunk primer, filling carved air pockets with water or lava.
     */
    public void generate(int chunkX, int chunkZ, ChunkPrimer primer) {
        if (!DepthsUpdateConfig.aquifers.enableAquifers) return;

        int worldX = chunkX * 16;
        int worldZ = chunkZ * 16;

        // Calculate grid bounds for this chunk (with padding for neighbor lookups)
        this.minGridX = gridCoord(worldX - X_RANGE);
        int maxGridX = gridCoord(worldX + 15 + X_RANGE);
        this.gridSizeX = maxGridX - minGridX + 1;

        this.minGridY = gridCoordY(aquiferMinY - Y_RANGE);
        int maxGridY = gridCoordY(aquiferMaxY + Y_RANGE);
        this.gridSizeY = maxGridY - minGridY + 1;

        this.minGridZ = gridCoord(worldZ - Z_RANGE);
        int maxGridZ = gridCoord(worldZ + 15 + Z_RANGE);
        this.gridSizeZ = maxGridZ - minGridZ + 1;

        // Allocate/resize cache based on actual grid dimensions
        int cacheSize = gridSizeX * gridSizeY * gridSizeZ;
        if (locationCache == null || locationCache.length < cacheSize) {
            this.locationCache = new long[cacheSize];
            this.fluidLevelCache = new int[cacheSize];
            this.fluidTypeCache = new boolean[cacheSize];
            this.cacheValid = new boolean[cacheSize];
        } else {
            Arrays.fill(cacheValid, 0, cacheSize, false);
        }

        IBlockState water = Blocks.WATER.getDefaultState();
        IBlockState lava = Blocks.LAVA.getDefaultState();
        IBlockState stone = Blocks.STONE.getDefaultState();
        IBlockState deepslate = BlockUtils.getDeepslateBlockState();

        // Estimate surface level per column for floodedness modulation
        int[] surfaceLevels = new int[16 * 16];

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                surfaceLevels[lx * 16 + lz] = estimateSurfaceLevel(primer, lx, lz);
            }
        }

        // Main fill pass - scan each block in the aquifer Y range
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int surfaceY = surfaceLevels[lx * 16 + lz];

                for (int y = aquiferMinY; y <= Math.min(aquiferMaxY, surfaceY - 4); y++) {
                    IBlockState current = primer.getBlockState(lx, y, lz);

                    // Only fill air blocks and do NOT fill bedrock-level
                    if (current.getBlock() != Blocks.AIR) continue;

                    int posX = worldX + lx;
                    int posZ = worldZ + lz;

                    // Find the 3 closest aquifer grid points
                    int anchorX = gridCoord(posX);
                    int anchorY = gridCoordY(y);
                    int anchorZ = gridCoord(posZ);

                    int dist1 = Integer.MAX_VALUE, dist2 = Integer.MAX_VALUE, dist3 = Integer.MAX_VALUE;
                    int idx1 = -1, idx2 = -1, idx3 = -1;

                    // Scan 2×3×2 neighborhood of grid cells
                    for (int gx = 0; gx <= 1; gx++) {
                        for (int gy = -1; gy <= 1; gy++) {
                            for (int gz = 0; gz <= 1; gz++) {
                                int cellX = anchorX + gx;
                                int cellY = anchorY + gy;
                                int cellZ = anchorZ + gz;

                                int index = getIndex(cellX, cellY, cellZ);

                                if (index < 0 || index >= locationCache.length) continue;

                                // Get or compute the aquifer center location for this cell
                                long location;

                                if (cacheValid[index]) {
                                    location = locationCache[index];
                                } else {
                                    computeFluidStatus(cellX, cellY, cellZ, index, surfaceY);
                                    location = locationCache[index];
                                }

                                int centerX = unpackX(location);
                                int centerY = unpackY(location);
                                int centerZ = unpackZ(location);

                                int dx = centerX - posX;
                                int dy = centerY - y;
                                int dz = centerZ - posZ;
                                int distSq = dx * dx + dy * dy + dz * dz;

                                if (distSq <= dist1) {
                                    dist3 = dist2; idx3 = idx2;
                                    dist2 = dist1; idx2 = idx1;
                                    dist1 = distSq; idx1 = index;
                                } else if (distSq <= dist2) {
                                    dist3 = dist2; idx3 = idx2;
                                    dist2 = distSq; idx2 = index;
                                } else if (distSq <= dist3) {
                                    dist3 = distSq; idx3 = index;
                                }
                            }
                        }
                    }

                    // No valid grid cells found — skip this block
                    if (idx1 < 0) continue;

                    // Get fluid status of the closest aquifer body
                    int fluidLevel1 = fluidLevelCache[idx1];
                    boolean isLava1 = fluidTypeCache[idx1];

                    // If the closest aquifer isn't flooded, skip
                    if (fluidLevel1 <= aquiferMinY - 10) continue;

                    // Check if this block is below the fluid surface
                    if (y >= fluidLevel1) continue;

                    // Calculate similarity between closest and second-closest
                    double similarity12 = similarity(dist1, dist2);

                    if (similarity12 <= 0.0) {
                        // Simple case - clearly belongs to closest aquifer
                        primer.setBlockState(lx, y, lz, isLava1 ? lava : water);
                    } else {
                        // Barrier zone - check if we should place a barrier or fluid
                        int fluidLevel2 = fluidLevelCache[idx2];
                        boolean isLava2 = fluidTypeCache[idx2];

                        double barrier = calculateBarrier(posX, y, posZ, similarity12, fluidLevel1, fluidLevel2, isLava1, isLava2);

                        if (barrier > 0.0) {
                            // Place barrier block
                            primer.setBlockState(lx, y, lz, y < 0 ? deepslate : stone);
                        } else {
                            // Fill with fluid
                            primer.setBlockState(lx, y, lz, isLava1 ? lava : water);
                        }
                    }
                }
            }
        }
    }

    /**
     * Computes similarity between two distances, used for barrier calculation.
     * Returns positive when distances are close (potential barrier zone).
     */
    private static double similarity(int distSq1, int distSq2) {
        return 1.0 - (double)(distSq2 - distSq1) / SIMILARITY_THRESHOLD;
    }

    /**
     * Calculates barrier pressure between two aquifer bodies.
     * Returns positive if a barrier should be placed (different fluid levels create walls).
     */
    private double calculateBarrier(int x, int y, int z, double similarity, int fluidLevel1, int fluidLevel2, boolean isLava1, boolean isLava2) {
        // Lava/water boundary - always barrier
        if (isLava1 != isLava2) {
            return 2.0;
        }

        int levelDiff = Math.abs(fluidLevel1 - fluidLevel2);

        if (levelDiff == 0) {
            return -1.0;
        }

        // Calculate how far the position is from the average fluid level
        double avgLevel = 0.5 * (fluidLevel1 + fluidLevel2);
        double distFromAvg = (double) y + 0.5 - avgLevel;
        double halfDiff = (double) levelDiff / 2.0;

        double distFromEdge = halfDiff - Math.abs(distFromAvg);
        double gradient;

        if (distFromAvg > 0.0) {
            // Above average - thinner barrier
            gradient = distFromEdge > 0.0 ? distFromEdge / 1.5 : distFromEdge / 2.5;
        } else {
            // Below average - thicker barrier
            double offset = 3.0 + distFromEdge;
            gradient = offset > 0.0 ? offset / 3.0 : offset / 10.0;
        }

        // Sample barrier noise to add randomness to the barrier shape
        double noiseValue;

        if (gradient < -2.0 || gradient > 2.0) {
            noiseValue = 0.0;
        } else {
            double noiseScale = 0.05;
            noiseValue = barrierNoise.getValue(x * noiseScale, y * noiseScale, z * noiseScale);
        }

        return similarity * 2.0 * (noiseValue + gradient);
    }

    /**
     * Computes and caches the fluid status for an aquifer grid cell.
     */
    private void computeFluidStatus(int gridX, int gridY, int gridZ, int index, int surfaceY) {
        long cellSeed = hashCell(gridX, gridY, gridZ, worldSeed);
        Random cellRandom = new Random(cellSeed);

        // Jittered center position within the cell
        int centerX = fromGrid(gridX) + cellRandom.nextInt(X_RANGE);
        int centerY = fromGridY(gridY) + cellRandom.nextInt(Y_RANGE);
        int centerZ = fromGrid(gridZ) + cellRandom.nextInt(Z_RANGE);

        locationCache[index] = packPos(centerX, centerY, centerZ);

        // Determine if cell is flooded
        double noiseScale = 0.012;
        double floodedness = floodednessNoise.getValue(
                centerX * noiseScale,
                centerY * noiseScale * 0.8,
                centerZ * noiseScale
        );
        // Normalize to roughly [-1, 1] without over-amplifying
        floodedness = MathHelper.clamp(floodedness, -1.0, 1.0);

        // Surface proximity modulation - caves near the surface are less likely to flood
        int depthBelowSurface = surfaceY - centerY;
        double surfaceFactor = depthBelowSurface > 0 ? MathHelper.clamp((double) depthBelowSurface / 64.0, 0.0, 1.0) : 0.0;

        // Thresholds
        double fullFloodThreshold = lerp(surfaceFactor, 0.9, 0.15);
        double partialFloodThreshold = lerp(surfaceFactor, 0.8, -0.05);

        double isFullyFlooded = floodedness - fullFloodThreshold;
        double isPartiallyFlooded = floodedness - partialFloodThreshold;

        int fluidLevel;

        if (isFullyFlooded > 0.0) {
            fluidLevel = Math.min(centerY + Y_SPACING, aquiferMaxY);
        } else if (isPartiallyFlooded > 0.0) {
            fluidLevel = computeRandomizedFluidLevel(centerX, centerY, centerZ, surfaceY);
        } else {
            fluidLevel = Integer.MIN_VALUE;
        }

        // Determine fluid type (water or lava)
        boolean isLava;

        if (centerY <= lavaAlwaysY) {
            isLava = true;
        } else if (centerY <= lavaPossibleY && fluidLevel > Integer.MIN_VALUE) {
            double lavaScale = 0.008;
            double lavaVal = lavaNoise.getValue(
                    centerX * lavaScale,
                    centerY * lavaScale,
                    centerZ * lavaScale
            );
            isLava = Math.abs(lavaVal) > 0.3;
        } else {
            isLava = false;
        }

        fluidLevelCache[index] = fluidLevel;
        fluidTypeCache[index] = isLava;
        cacheValid[index] = true;
    }

    /**
     * Computes a randomized fluid surface level for partially-flooded cells.
     */
    private int computeRandomizedFluidLevel(int x, int y, int z, int surfaceLevel) {
        // Larger cell for fluid level variation
        int cellX = Math.floorDiv(x, 16);
        int cellY = Math.floorDiv(y, 40);
        int cellZ = Math.floorDiv(z, 16);

        double spreadScale = 0.05;
        double spreadValue = spreadNoise.getValue(
                cellX * spreadScale,
                cellY * spreadScale,
                cellZ * spreadScale
        ) * 10.0;

        int cellMiddleY = cellY * 40 + 20;
        int spreadQuantized = quantize(spreadValue, 3);
        int targetLevel = cellMiddleY + spreadQuantized;

        return Math.min(surfaceLevel - 8, targetLevel);
    }

    /**
     * Estimates the surface level (highest solid block) for a given column in the primer.
     */
    private static int estimateSurfaceLevel(ChunkPrimer primer, int localX, int localZ) {
        // Scan down from a reasonable surface height
        int minY = HeightManager.getMaxContext().minY();
        for (int y = 80; y >= minY; y--) {
            IBlockState state = primer.getBlockState(localX, y, localZ);

            if (state.getBlock() != Blocks.AIR && state.getBlock() != Blocks.WATER && state.getBlock() != Blocks.LAVA) {
                return y;
            }
        }

        return 0;
    }

    // Grid coordinate helpers

    private static int gridCoord(int blockCoord) {
        return blockCoord >> 4; // divide by 16
    }

    private static int gridCoordY(int blockCoord) {
        return Math.floorDiv(blockCoord, Y_SPACING);
    }

    private static int fromGrid(int gridCoord) {
        return gridCoord << 4; // multiply by 16
    }

    private static int fromGridY(int gridCoord) {
        return gridCoord * Y_SPACING;
    }

    private int getIndex(int gridX, int gridY, int gridZ) {
        int x = gridX - minGridX;
        int y = gridY - minGridY;
        int z = gridZ - minGridZ;
        if (x < 0 || y < 0 || z < 0 || x >= gridSizeX || y >= gridSizeY || z >= gridSizeZ) {
            return -1;
        }

        return (y * gridSizeZ + z) * gridSizeX + x;
    }

    // Position packing

    private static long packPos(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) y & 0xFFFL) << 26 | ((long) z & 0x3FFFFFFL);
    }

    private static int unpackX(long packed) {
        return (int) (packed << 0 >> 38);
    }

    private static int unpackY(long packed) {
        return (int) ((packed << 26) >> 52);
    }

    private static int unpackZ(long packed) {
        return (int) (packed << 38 >> 38);
    }

    // Utility

    private static long hashCell(int gx, int gy, int gz, long seed) {
        long hash = seed;
        hash = hash * 6364136223846793005L + 1442695040888963407L;
        hash += gx;
        hash = hash * 6364136223846793005L + 1442695040888963407L;
        hash += gy;
        hash = hash * 6364136223846793005L + 1442695040888963407L;
        hash += gz;
        hash = hash * 6364136223846793005L + 1442695040888963407L;

        return hash;
    }

    private static int quantize(double value, int step) {
        return MathHelper.floor(value / step) * step;
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }
}
