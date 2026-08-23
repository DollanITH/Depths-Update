package sayys.depthsupdate.remaper;

import com.google.common.collect.ImmutableList;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import sayys.depthsupdate.registry.DeepslateRegistry;

/**
 * Manages the flattening definitions for the {@link ItemFlattening} data fixer.
 *
 * @author Choonster
 */
public class ItemFlatteningDefinitions {
	/**
	 * Creates an instance of the ItemFlattening data fixer with the definitions of the items to flatten.
	 *
	 * @return The ItemFlattening instance
	 */
	public static ItemFlattening createItemFlattening() {
		final ImmutableList.Builder<ItemFlattening.FlatteningDefinition> flatteningDefinitions = new ImmutableList.Builder<>();

		flatteningDefinitions.add(new ItemFlattening.FlatteningDefinition(
				"dollancustomized",
				"deepslate",
				0,
				Item.getItemFromBlock(DeepslateRegistry.deepslate),
				(item, oldMetadata, oldStackTagCompound) -> new ItemStack(item)
		));


		flatteningDefinitions.add(new ItemFlattening.FlatteningDefinition(
				"dollancustomized",
				"deepslate",
				1,
				Item.getItemFromBlock(DeepslateRegistry.deepslate_bricks),
				(item, oldMetadata, oldStackTagCompound) -> new ItemStack(item)
		));

		flatteningDefinitions.add(new ItemFlattening.FlatteningDefinition(
				"dollancustomized",
				"deepslate",
				2,
				DeepslateRegistry.deepslate_tiles,
				(item, oldMetadata, oldStackTagCompound) -> new ItemStack(item)
		));

		flatteningDefinitions.add(new ItemFlattening.FlatteningDefinition(
				"dollancustomized",
				"graystoneblock",
				0,
				DeepslateRegistry.tuff,
				(item, oldMetadata, oldStackTagCompound) -> new ItemStack(item)
		));

		flatteningDefinitions.add(new ItemFlattening.FlatteningDefinition(
				"dollancustomized",
				"cracked_deepslate",
				0,
				DeepslateRegistry.cracked_deepslate_bricks,
				(item, oldMetadata, oldStackTagCompound) -> new ItemStack(item)
		));

		flatteningDefinitions.add(new ItemFlattening.FlatteningDefinition(
				"dollancustomized",
				"cracked_deepslate",
				1,
				DeepslateRegistry.cracked_deepslate_tiles,
				(item, oldMetadata, oldStackTagCompound) -> new ItemStack(item)
		));

		flatteningDefinitions.add(new ItemFlattening.FlatteningDefinition(
				"dollancustomized",
				"calcite",
				0,
				DeepslateRegistry.calcite,
				(item, oldMetadata, oldStackTagCompound) -> new ItemStack(item)
		));

		flatteningDefinitions.add(new ItemFlattening.FlatteningDefinition(
				"dollancustomized",
				"polished_deepslate",
				0,
				DeepslateRegistry.polished_deepslate,
				(item, oldMetadata, oldStackTagCompound) -> new ItemStack(item)
		));
		return new ItemFlattening(flatteningDefinitions.build());
	}
}