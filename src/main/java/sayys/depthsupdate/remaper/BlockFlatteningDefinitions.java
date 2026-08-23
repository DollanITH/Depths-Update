package sayys.depthsupdate.remaper;

import com.google.common.collect.ImmutableList;
import net.minecraft.util.EnumFacing;
import sayys.depthsupdate.block.BlockDeepslate;
import sayys.depthsupdate.registry.DeepslateRegistry;

/**
 * Manages the flattening definitions for the {@link BlockFlattening} data fixer.
 *
 * @author Choonster
 */
public class BlockFlatteningDefinitions {
	/**
	 * Creates an instance of the BlockFlattening data fixer with the definitions of the blocks to flatten.
	 *
	 * @return The BlockFlattening instance
	 */
	public static BlockFlattening createBlockFlattening() {
        final ImmutableList.Builder<BlockFlattening.FlatteningDefinition> flatteningDefinitions = new ImmutableList.Builder<>();

        flatteningDefinitions.add(new BlockFlattening.FlatteningDefinition(
				"dollancustomized",
                "deepslate",
                0,
				DeepslateRegistry.deepslate,
                (block, tileEntityNBT) -> block.getDefaultState().withProperty(BlockDeepslate.AXIS, EnumFacing.Axis.Y),
                null
        ));

        flatteningDefinitions.add(new BlockFlattening.FlatteningDefinition(
				"dollancustomized",
                "deepslate",
                1,
				DeepslateRegistry.deepslate_bricks,
                (block, tileEntityNBT) -> block.getDefaultState(),
                null
        ));

        flatteningDefinitions.add(new BlockFlattening.FlatteningDefinition(
				"dollancustomized",
                "deepslate",
                2,
				DeepslateRegistry.deepslate_tiles,
                (block, tileEntityNBT) -> block.getDefaultState(),
                null
        ));

        flatteningDefinitions.add(new BlockFlattening.FlatteningDefinition(
				"dollancustomized",
                "graystoneblock",
                0,
				DeepslateRegistry.tuff,
                (block, tileEntityNBT) -> block.getDefaultState(),
                null
        ));

        flatteningDefinitions.add(new BlockFlattening.FlatteningDefinition(
				"dollancustomized",
                "cracked_deepslate",
                0,
				DeepslateRegistry.cracked_deepslate_bricks,
                (block, tileEntityNBT) -> block.getDefaultState(),
                null
        ));

        flatteningDefinitions.add(new BlockFlattening.FlatteningDefinition(
				"dollancustomized",
                "cracked_deepslate",
                1,
				DeepslateRegistry.cracked_deepslate_tiles,
                (block, tileEntityNBT) -> block.getDefaultState(),
                null
        ));

        flatteningDefinitions.add(new BlockFlattening.FlatteningDefinition(
				"dollancustomized",
                "calcite",
                0,
				DeepslateRegistry.calcite,
                (block, tileEntityNBT) -> block.getDefaultState(),
                null
        ));

        flatteningDefinitions.add(new BlockFlattening.FlatteningDefinition(
				"dollancustomized",
                "polished_deepslate",
                0,
				DeepslateRegistry.polished_deepslate,
                (block, tileEntityNBT) -> block.getDefaultState(),
                null
        ));

		return new BlockFlattening(flatteningDefinitions.build());
	}
}