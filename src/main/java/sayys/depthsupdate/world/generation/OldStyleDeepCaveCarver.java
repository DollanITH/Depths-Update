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
 * Carves the deep slab (below the old terrain) with the exact same cave network
 * that the vanilla carver carves above it.
 *
 * <p><b>Why this exists.</b> The vanilla cave carver runs on a ChunkPrimer whose
 * data below Y=0 is lost when {@code Chunk(World, ChunkPrimer, int, int)} packs the
 * primer into storage arrays (it only reads Y 0..255). The extended-world deep slab
 * is therefore re-filled afterwards (DeepFill) and would stay solid rock with no
 * caves. This pass re-carves that deep slab.
 *
 * <p><b>Seamless across Y=0.</b> To keep the deep caves continuous with the
 * surface caves instead of a separate unconnected network, this class replays the
 * vanilla carver's exact seed derivation and random stream
 * ({@link #recursiveGenerate}, {@link #tunnel}, {@link #addRoom} mirror
 * {@code MixinMapGenCaves.depthsupdate$recursiveGenerate / addTunnel} and the
 * vanilla {@code MapGenCaves.addRoom} block for block, same {@code nextInt /
 * nextFloat / nextLong} order). Each tunnel is therefore carved by the vanilla
 * carver for its Y>=0 span and by this pass for its below-Y0 span: one continuous
 * tunnel crossing Y=0. {@code topY} (typically 6) limits this pass to the deep
 * slab plus the former bedrock backstop y=0..5; Y>=6 keeps whatever caves the
 * surface already has and is never re-carved here. A tunnel whose centre stays
 * above Y=0 has no below-Y0 span and therefore no deep continuation — that is the
 * vanilla geometry itself, not a cut. To get dense, fully connected deep caves
 * instead, enable the 1.18-style noise caves (world-coordinate noise, continuous
 * and smooth by construction).
 *
 * <p>Like {@code MapGenBase.generate}, carving a chunk re-plays the seeds of all
 * surrounding chunks inside the generation range, so caves pass continuously
 * across chunk boundaries and the result is independent of chunk generation order.
 */
public final class OldStyleDeepCaveCarver {

    /** MapGenBase default generation range (chunks). Must match MapGenBase.range. */
    private static final int RANGE = 8;

    private OldStyleDeepCaveCarver() {
    }

    /**
     * Carves old-style caves into {@code primer} for the chunk at {@code (chunkX, chunkZ)}.
     *
     * @param topY exclusive upper bound of the carved region (in world Y). Pass 6 to
     *              carve the deep slab plus the former bedrock backstop y=0..5 only;
     *              larger values are clamped inside to the vanilla carver's own upper
     *              bound (carverMaxY) as a safety net.
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

        // The vanilla carver's own upper bound (MixinMapGenCaves anchors tunnel starts
        // with carverMaxY = min(180, minY + totalHeight - 8)). Clamp to it so this pass
        // carves exactly the region the vanilla carver owns. Surface caves bottom out at
        // whatever height the tunnel's dig box happens to reach (not a fixed Y=5), so the
        // deep pass must reach the same upper bound to fill the gap between the deep caves
        // and the surface cave floor. Blocks the vanilla carver already dug are air and are
        // skipped by dig(), so overlapping the region is a safe no-op.
        int carverMaxY = Math.min(180, minY + (maxY - minY) - 8);

        if (topY > carverMaxY) {
            topY = carverMaxY;
        }

        if (topY <= minY) {
            return false;
        }

        // Vanilla MapGenBase.generate seeding, byte for byte: derive per-chunk
        // multipliers from the world seed, then re-play every chunk in the range
        // with the same chunk seed the vanilla carver would use.
        long worldSeed = world.getWorldInfo().getSeed();
        Random seedRand = new Random(worldSeed);
        long i = seedRand.nextLong();
        long j = seedRand.nextLong();

        boolean[] dug = {false};

        for (int k = chunkX - RANGE; k <= chunkX + RANGE; ++k) {
            for (int l = chunkZ - RANGE; l <= chunkZ + RANGE; ++l) {
                long chunkSeed = (long) k * i ^ (long) l * j ^ worldSeed;
                Random rand = new Random(chunkSeed);
                recursiveGenerate(world, rand, k, l, chunkX, chunkZ, primer, ctx, topY, dug);
            }
        }

        return dug[0];
    }

    /**
     * Mirrors {@code MixinMapGenCaves.depthsupdate$recursiveGenerate} exactly
     * (same random calls, same order, same derived Y range) so that, per
     * neighbouring chunk seed, the same caves exist here as in the vanilla carver.
     * The only difference is that the carved region is clamped to {@code [minY, topY)}.
     */
    private static void recursiveGenerate(World world, Random rand,
                                          int neighborX, int neighborZ,
                                          int chunkX, int chunkZ,
                                          ChunkPrimer primer, HeightContext ctx, int topY,
                                          boolean[] dug) {
        int minY = ctx.minY();
        int maxY = ctx.maxY();
        int lavaLevel = HeightManager.getLavaLevel(world);

        int i = rand.nextInt(rand.nextInt(rand.nextInt(15) + 1) + 1);

        if (rand.nextInt(7) != 0) {
            i = 0;
        }

        // Identical Y-range derivation as MixinMapGenCaves.
        int carverMinY = minY + 8;
        int carverMaxY = Math.min(180, minY + (maxY - minY) - 8);
        int yRange = Math.max(8, carverMaxY - carverMinY - 8);

        for (int j = 0; j < i; ++j) {
            double d0 = (double) (neighborX * 16 + rand.nextInt(16));

            double vanillaLikeY = rand.nextInt(yRange) + 8;
            double d1 = (double) (rand.nextInt((int) vanillaLikeY) + carverMinY);

            double d2 = (double) (neighborZ * 16 + rand.nextInt(16));
            int k = 1;

            if (rand.nextInt(4) == 0) {
                long roomSeed = rand.nextLong();
                addRoom(new Random(roomSeed), chunkX, chunkZ, primer, ctx, minY, topY, lavaLevel,
                        d0, d1, d2, dug);
                k += rand.nextInt(4);
            }

            for (int l = 0; l < k; ++l) {
                float f = rand.nextFloat() * (float) Math.PI * 2.0F;
                float f1 = (rand.nextFloat() - 0.5F) * 2.0F / 8.0F;
                float f2 = rand.nextFloat() * 2.0F + rand.nextFloat();

                if (rand.nextInt(10) == 0) {
                    f2 *= rand.nextFloat() * rand.nextFloat() * 3.0F + 1.0F;
                }

                long tunnelSeed = rand.nextLong();
                tunnel(new Random(tunnelSeed), chunkX, chunkZ, primer, ctx, minY, topY, lavaLevel,
                        d0, d1, d2, f2, f, f1, 0, 0, 1.0D, dug);
            }
        }
    }

    /**
     * Mirror of the vanilla 1.12.2 {@code MapGenCaves.addRoom} (a large carved
     * sphere grown three times), clamped to {@code [minY, topY)}. Uses the same
     * {@code new Random(p_180703_1_)} instance and the same random call order.
     */
    private static void addRoom(Random random, int chunkX, int chunkZ, ChunkPrimer primer,
                                HeightContext ctx, int minY, int topY, int lavaLevel,
                                double x, double y, double z, boolean[] dug) {
        double d0 = (double) (chunkX * 16 + 8);
        double d1 = (double) (chunkZ * 16 + 8);
        float f = 0.0F;
        float f1 = 0.0F;

        for (int j = 0; j < 3; ++j) {
            double d2 = 1.0D + (double) (random.nextFloat() * 6.0F);
            double d3 = (double) (random.nextFloat() * 0.5F);
            double d4 = (double) (random.nextFloat() * 0.5F);
            float f2 = (float) (random.nextFloat() * 2.0F) * (float) Math.PI;
            float f3 = (float) (random.nextFloat() * 2.0F) * (float) Math.PI;
            double d5 = x + (double) (MathHelper.cos(f2) * f3) * d3;
            double d6 = y + (double) (MathHelper.sin(f3) * f3) * d4;
            double d7 = z + (double) (MathHelper.sin(f2) * f3) * d3;

            int k = MathHelper.floor(d5 - d2) - chunkX * 16 - 1;
            int l = MathHelper.floor(d5 + d2) - chunkX * 16 + 1;
            int i1 = MathHelper.floor(d6 - 3.0D) - 1;
            int j1 = MathHelper.floor(d6 + 3.0D) + 1;
            int k1 = MathHelper.floor(d7 - d2) - chunkZ * 16 - 1;
            int l1 = MathHelper.floor(d7 + d2) - chunkZ * 16 + 1;

            if (k < 0) {
                k = 0;
            }

            if (l > 16) {
                l = 16;
            }

            if (i1 < minY) {
                i1 = minY;
            }

            if (j1 > topY) {
                j1 = topY;
            }

            if (k1 < 0) {
                k1 = 0;
            }

            if (l1 > 16) {
                l1 = 16;
            }

            if (d5 >= d0 - 16.0D - d2 * 2.0D && d7 >= d1 - 16.0D - d2 * 2.0D
                    && d5 <= d0 + 16.0D + d2 * 2.0D && d7 <= d1 + 16.0D + d2 * 2.0D) {
                for (int i2 = k; i2 < l; ++i2) {
                    double d8 = ((double) (i2 + chunkX * 16) + 0.5D - d5) / d2;

                    for (int j2 = k1; j2 < l1; ++j2) {
                        double d9 = ((double) (j2 + chunkZ * 16) + 0.5D - d7) / d2;

                        if (d8 * d8 + d9 * d9 < 1.0D) {
                            for (int k2 = j1; k2 > i1; --k2) {
                                double d10 = ((double) (k2 - 1) + 0.5D - d6) / 3.0D;

                                if (d8 * d8 + d10 * d10 / 6.0D + d9 * d9 < 1.0D) {
                                    dig(primer, ctx, i2, k2, j2, minY, topY, lavaLevel, dug);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Mirror of {@code MixinMapGenCaves.depthsupdate$addTunnel} — the vanilla
     * 1.12.2 ellipsoid tunnel walker, same random call order, carving clamped to
     * {@code [minY, topY)}. {@code random} is a per-tunnel instance seeded from
     * {@code nextLong()}, exactly like the vanilla {@code new Random(p_180702_1_)}.
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
                tunnel(new Random(random.nextLong()), chunkX, chunkZ, primer, ctx, minY, topY, lavaLevel,
                        x, y, z, random.nextFloat() * 0.5F + 0.5F,
                        yaw - (float) Math.PI / 2.0F, pitch / 3.0F, stepStart, stepEnd, 1.0D, dug);
                tunnel(new Random(random.nextLong()), chunkX, chunkZ, primer, ctx, minY, topY, lavaLevel,
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

                    if (k2 < 0) {
                        k2 = 0;
                    }

                    if (k > 16) {
                        k = 16;
                    }

                    if (i3 < 0) {
                        i3 = 0;
                    }

                    if (i1 > 16) {
                        i1 = 16;
                    }

                    // Clamp carving to the deep slab: the vanilla carver owns Y>=0.
                    if (l2 < minY) {
                        l2 = minY;
                    }

                    if (l > topY) {
                        l = topY;
                    }

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
