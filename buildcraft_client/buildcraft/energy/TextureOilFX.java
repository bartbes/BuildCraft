/**
 * Copyright (c) SpaceToad, 2011
 * http://www.mod-buildcraft.com
 *
 * BuildCraft is distributed under the terms of the Minecraft Mod Public
 * License 1.0, or MMPL. Please check the contents of the license located in
 * http://www.mod-buildcraft.com/MMPL-1.0.txt
 */

package buildcraft.energy;

import buildcraft.BuildCraftEnergy;
import buildcraft.core.TextureLiquidsFX;
import net.minecraft.src.forge.ITextureProvider;

public class TextureOilFX extends TextureLiquidsFX {

	public TextureOilFX() {
		super(10, 31, 10, 31, 10, 31, BuildCraftEnergy.oilStill.blockIndexInTexture,
				((ITextureProvider) BuildCraftEnergy.oilStill).getTextureFile());
	}
}
