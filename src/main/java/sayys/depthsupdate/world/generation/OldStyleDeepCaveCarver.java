package sayys.depthsupdate.world.generation;

import java.util.Random;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkPrimer;

import sayys.depthsupdate.core.HeightContext;
import sayys.depthsupdate.core.HeightManager;
import sayys.depthsupdate.util.BlockUtils;

/**
 * Carves vanilla 1.12.2-style (MapGenCaves) caves through a deep stone region that
 * was filled below the old terrain. The vanilla cave carver only applies its results
 * to Y&gt;=0 (its populate pass writes blocks 0..255) and runs before the deep slab is
 * filled, so without this pass the deep region ends up as solid rock with no caves.
 *
 * <p>Used by both paths that create deep stone:
 * <ul>
 *   <li>newly generated extended chunks (after the deep fill in
 *       {@code MixinChunkProviderServer}), and</li>
 *   <li>converted old-world chunks (after {@code fillBelowY0} in
 *       {@code MixinAnvilChunkLoader}).</li>
 * </ul>
 *
 * <p>Like {@code MapGenBase.generate}, carving a chunk re-plays the seeds of all
 * surrounding chunks inside the generator range. Tunnels seeded by a neighbouring
 * chunk are carved into this chunk too, so caves pass continuously across chunk
 * boundaries instead of being cut off at a 16-block edge, and the result is
 * independent of the order chunks are generated in.
 *
 * <p>Tunnel behaviour is the vanilla 1.12.2 one, but the deep cave population is scaled
 * to the deep region's share of the world height (with start positions biased toward
 * the surface), so the deep is clearly sparser than the surface and thins out with
 * depth instead of being a crowded lattice or a solid slab.
 */
public final class OldStyleDeepCaveCarver {

    /** MapGenBase default generation range (chunks). */
    private static final int RANGE = 8;

    private OldStyleDeepCaveCarver() {
    }

    /**
     * Carves old-style caves into {@code primer} for the chunk at {@code (chunkX, chunkZ)}.
     *
     * @param topY exclusive upper bound of the carved region (in world Y). Tunnels may
     *             wander but carving is clamped to {@code [minY, topY)}.
     * @return true if at least one block was actually dug (false when the region was
     *         already carved by a previous run — the carve is deterministic and
     *         idempotent, so re-running it is a safe no-op).
     */
    public static boolean carve(World world, int chunkX, int chunkZ, ChunkPrimer primer,
                                HeightContext ctx, int topY) {
        int minY = ctx.minY();
        int maxY = ctx.maxY();

        if (topY > maxY) {
            topY = maxY;
        }

        if (topY <= minY) {
            return false;
        }

        // Vanilla MapGenBase.generate seeding: derive per-chunk multipliers from the
        // world seed, then re-play the seeds of every chunk in the generation range.
        long worldSeed = world.getWorldInfo().getSeed();
        Random seedRand = new Random(worldSeed);
        long k = seedRand.nextLong() / 2L * 2L + 1L;
        long l = seedRand.nextLong() / 2L * 2L + 1L;

        boolean[] dug = {false};

        for (int j = chunkX - RANGE; j <= chunkX + RANGE; ++j) {
            for (int k1 = chunkZ - RANGE; k1 <= chunkZ + RANGE; ++k1) {
                long chunkSeed = (long) j * k + (long) k1 * l;
                Random rand = new Random(chunkSeed ^ worldSeed);
                recursiveGenerate(world, rand, j, k1, chunkX, chunkZ, primer, ctx, topY, dug);
            }
        }

        return dug[0];
    }

    /**
     * Port of vanilla 1.12.2 MapGenCaves.recursiveGenerate: rolls how many cave
     * branches a (possibly neighbouring) chunk contributes, then starts wandering
     * tunnels inside the deep region. {@code neighborX/Z} seed the tunnel's world
     * position (vanilla starts them from the generating chunk's origin); the tunnels
     * are carved into the chunk at {@code chunkX/Z}.
     */
    private static void recursiveGenerate(World world, Random rand,
                                          int neighborX, int neighborZ,
                                          int chunkX, int chunkZ,
                                          ChunkPrimer primer, HeightContext ctx, int topY,
                                          boolean[] dug) {
        int minY = ctx.minY();
        int deepHeight = Math.max(1, topY - minY);
        int totalHeight = Math.max(deepHeight, ctx.totalHeight());
        double heightShare = (double) deepHeight / (double) totalHeight;

        // Vanilla density: most chunks contribute no caves at all.
        int branches = rand.nextInt(rand.nextInt(rand.nextInt(15) + 1) + 1);

        if (rand.nextInt(7) != 0) {
            branches = 0;
        }

        // The deep slab is only a fraction of the world height; keep only a matching
        // fraction of the vanilla cave population, so the deep is clearly sparser than
        // the surface instead of a crowded lattice.
        if (branches > 0 && rand.nextFloat() > heightShare) {
            branches = 0;
        }

        int lavaLevel = HeightManager.getLavaLevel(world);

        for (int i = 0; i < branches; ++i) {
            double d0 = neighborX * 16 + rand.nextInt(16);
            double d2 = neighborZ * 16 + rand.nextInt(16);

            // Bias start-Y toward the top of the deep region (near the old surface), so
            // caves concentrate where they connect to the surface and thin out with
            // depth, like the natural tail of the surface cave population.
            double d1 = topY - 1 - (double) (rand.nextInt(deepHeight) * rand.nextInt(deepHeight)) / deepHeight;

            int k = 1;

            // Vanilla room roll: roughly 1 in 4 branches is a "room" instead of a tunnel.
            if (rand.nextInt(4) == 0) {
                tunnel(rand, chunkX, chunkZ, primer, ctx, minY, topY, lavaLevel,
                        d0, d1, d2, 1.0F + rand.nextFloat() * 6.0F, 0.0F, 0.0F, -1, -1, 0.5D, dug);
                k += rand.nextInt(4);
            }

            for (int j = 0; j < k; ++j) {
                float f = rand.nextFloat() * (float) Math.PI * 2.0F;
                float f1 = (rand.nextFloat() - 0.5F) * 2.0F / 8.0F;
                float f2 = rand.nextFloat() * 2.0F + rand.nextFloat();

                if (rand.nextInt(10) == 0) {
                    f2 *= rand.nextFloat() * rand.nextFloat() * 3.0F + 1.0F;
                }

                int calculatedLength = (RANGE * 16 - 16) * 3;
                int tunnelDistance = calculatedLength - rand.nextInt(calculatedLength / 4);

                tunnel(rand, chunkX, chunkZ, primer, ctx, minY, topY, lavaLevel,
                        d0, d1, d2, f2, f, f1, 0, tunnelDistance, 1.0D, dug);
            }
        }
    }

    /**
     * Port of vanilla 1.12.2 MapGenCaves.addTunnel, constrained to the deep region
     * {@code [minY, topY)}. Carves a wandering ellipsoid tunnel, spawning sub-tunnels
     * at the halfway point like vanilla. {@code chunkX/Z} is the chunk being carved;
     * the tunnel's world position {@code (x, y, z)} may have originated in a neighbour.
     */
    private static void tunnel(Random random, int chunkX, int chunkZ, ChunkPrimer primer,
                               HeightContext ctx, int minY, int topY, int lavaLevel,
                               double x, double y, double z, float sizeX, float yaw, float pitch,
                               int stepStart, int stepEnd, double speed, boolean[] dug) {
        double d0 = chunkX * 16 + 8;
        double d1 = chunkZ * 16 + 8;
        float f = 0.0F;
        float f1 = 0.0F;

        if (stepEnd <= 0) {
            int i = RANGE * 16 - 16;
            stepEnd = i - random.nextInt(i / 4);
        }

        boolean flag2 = false;

        if (stepStart == -1) {
            stepStart = stepEnd / 2;
            flag2 = true;
        }

        int j = random.nextInt(stepEnd / 2) + stepEnd / 4;

        for (boolean flag = random.nextInt(6) == 0; stepStart < stepEnd; ++stepStart) {
            double d2 = 1.5D + MathHelper.sin((float) stepStart * (float) Math.PI / (float) stepEnd) * sizeX;
            double d3 = d2 * speed;
            float f2 = MathHelper.cos(pitch);
            float f3 = MathHelper.sin(pitch);

            x += MathHelper.cos(yaw) * f2;
            y += f3;
            z += MathHelper.sin(yaw) * f2;

            if (flag) {
                pitch *= 0.92F;
            } else {
                pitch *= 0.7F;
            }

            pitch += f1 * 0.1F;
            yaw += f * 0.1F;
            f1 *= 0.9F;
            f *= 0.75F;
            f1 += (random.nextFloat() - random.nextFloat()) * random.nextFloat() * 2.0F;
            f += (random.nextFloat() - random.nextFloat()) * random.nextFloat() * 4.0F;

            if (!flag2 && stepStart == j && sizeX > 1.0F && stepEnd > 0) {
                tunnel(random, chunkX, chunkZ, primer, ctx, minY, topY, lavaLevel,
                        x, y, z, random.nextFloat() * 0.5F + 0.5F,
                        yaw - (float) Math.PI / 2.0F, pitch / 3.0F, stepStart, stepEnd, 1.0D, dug);
                tunnel(random, chunkX, chunkZ, primer, ctx, minY, topY, lavaLevel,
                        x, y, z, random.nextFloat() * 0.5F + 0.5F,
                        yaw + (float) Math.PI / 2.0F, pitch / 3.0F, stepStart, stepEnd, 1.0D, dug);
                return;
            }

            if (flag2 || random.nextInt(4) != 0) {
                double d4 = x - d0;
                double d5 = z - d1;
                double d6 = stepEnd - stepStart;
                double d7 = sizeX + 2.0F + 16.0F;

                if (d4 * d4 + d5 * d5 - d6 * d6 > d7 * d7) {
                    return;
                }

                if (x >= d0 - 16.0D - d2 * 2.0D
                        && z >= d1 - 16.0D - d2 * 2.0D
                        && x <= d0 + 16.0D + d2 * 2.0D
                        && z <= d1 + 16.0D + d2 * 2.0D) {
                    int k2 = MathHelper.floor(x - d2) - chunkX * 16 - 1;
                    int k = MathHelper.floor(x + d2) - chunkX * 16 + 1;
                    int l2 = MathHelper.floor(y - d3) - 1;
                    int l = MathHelper.floor(y + d3) + 1;
                    int i3 = MathHelper.floor(z - d2) - chunkZ * 16 - 1;
                    int i1 = MathHelper.floor(z + d2) - chunkZ * 16 + 1;

                    if (k2 < 0) k2 = 0;
                    if (k > 16) k = 16;
                    if (i3 < 0) i3 = 0;
                    if (i1 > 16) i1 = 16;
                    if (l2 < minY) l2 = minY;
                    if (l > topY) l = topY;

                    for (int j3 = k2; j3 < k; ++j3) {
                        double d10 = ((double) (j3 + chunkX * 16) + 0.5D - x) / d2;

                        for (int i2 = i3; i2 < i1; ++i2) {
                            double d8 = ((double) (i2 + chunkZ * 16) + 0.5D - z) / d2;

                            if (d10 * d10 + d8 * d8 < 1.0D) {
                                for (int j2 = l; j2 > l2; --j2) {
                                    double d9 = ((double) (j2 - 1) + 0.5D - y) / d3;

                                    if (d9 > -0.7D && d10 * d10 + d9 * d9 + d8 * d8 < 1.0D) {
                                        dig(primer, ctx, j3, j2, i2, minY, topY, lavaLevel, dug);
                                    }
                                }
                            }
                        }
                    }

                    if (flag2) {
                        break;
                    }
                }
            }
        }
    }

    /**
     * Carve a single block of the deep slab. Only stone/deepslate placed by the fill
     * is removed; the bottom bedrock layer is left intact. Below the lava level the
     * hole fills with lava, matching vanilla cave behaviour.
     */
    private static void dig(ChunkPrimer primer, HeightContext ctx, int bx, int y, int bz,
                            int minY, int topY, int lavaLevel, boolean[] dug) {
        if (y < minY || y >= topY || y >= ctx.maxY()) {
            return;
        }

        IBlockState state = primer.getBlockState(bx, y, bz);
        Block block = state.getBlock();

        if (block != Blocks.STONE && !BlockUtils.isDeepslate(state)) {
            return;
        }

        primer.setBlockState(bx, y, bz,
                y < lavaLevel ? Blocks.LAVA.getDefaultState() : Blocks.AIR.getDefaultState());
        dug[0] = true;
    }
}
