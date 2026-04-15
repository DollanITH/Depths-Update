package sayys.depthsupdate.world.generation;

import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkPrimer;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import sayys.depthsupdate.core.HeightContext;

/**
 * A ChunkPrimer subclass that delegates getBlockState/setBlockState to an existing Chunk's storage arrays.
 */
public class ChunkPrimerAdapter extends ChunkPrimer {
    private static final IBlockState AIR = Blocks.AIR.getDefaultState();

    private final Chunk chunk;
    private final HeightContext ctx;

    public ChunkPrimerAdapter(Chunk chunk, HeightContext ctx) {
        this.chunk = chunk;
        this.ctx = ctx;
    }

    @Override
    public IBlockState getBlockState(int x, int y, int z) {
        int storageIdx = ctx.toStorageIndex(y);
        ExtendedBlockStorage[] arrays = chunk.getBlockStorageArray();

        if (storageIdx < 0 || storageIdx >= arrays.length) {
            return AIR;
        }

        ExtendedBlockStorage section = arrays[storageIdx];

        if (section == Chunk.NULL_BLOCK_STORAGE) {
            return AIR;
        }

        return section.get(x, y & 15, z);
    }

    @Override
    public void setBlockState(int x, int y, int z, IBlockState state) {
        int storageIdx = ctx.toStorageIndex(y);
        ExtendedBlockStorage[] arrays = chunk.getBlockStorageArray();

        if (storageIdx < 0 || storageIdx >= arrays.length) {
            return;
        }

        ExtendedBlockStorage section = arrays[storageIdx];
        if (section == Chunk.NULL_BLOCK_STORAGE) {
            if (state.getBlock() == Blocks.AIR) {
                return;
            }

            section = new ExtendedBlockStorage(y >> 4 << 4, chunk.getWorld().provider.hasSkyLight());
            arrays[storageIdx] = section;
        }

        section.set(x, y & 15, z, state);
    }

    @Override
    public int findGroundBlockIdx(int x, int z) {
        ExtendedBlockStorage[] arrays = chunk.getBlockStorageArray();

        for (int y = ctx.maxY() - 1; y >= ctx.minY(); --y) {
            int storageIdx = ctx.toStorageIndex(y);

            if (storageIdx < 0 || storageIdx >= arrays.length) {
                continue;
            }

            ExtendedBlockStorage section = arrays[storageIdx];

            if (section == Chunk.NULL_BLOCK_STORAGE) {
                continue;
            }

            IBlockState state = section.get(x, y & 15, z);

            if (state != null && state != AIR) {
                return y;
            }
        }

        return ctx.minY();
    }
}
