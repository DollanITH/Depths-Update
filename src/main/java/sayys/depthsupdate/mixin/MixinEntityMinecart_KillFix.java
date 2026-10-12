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
package sayys.depthsupdate.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import sayys.depthsupdate.core.HeightManager;

import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
@Mixin(EntityMinecart.class)
public abstract class MixinEntityMinecart_KillFix extends Entity {

    public MixinEntityMinecart_KillFix(World worldIn) {
        super(worldIn);
    }

    /**
     * Replace -64 constant, to avoid killing minecarts below y=-64
     */
    @ModifyConstant(method = "onUpdate", constant = @Constant(doubleValue = -64.0D), require = 1)
    private double getDeathY(double originalY) {
        // ★ 修正: 原来写的是 getMinY(world) + originalY，即 (-64) + (-64) = -128，
        // 语义上是"双重相减"（原版本意是"相对 minY 的偏移"）。数值恰好与配置里的
        // Void Damage Level 一致，所以一直没暴露问题，但表达式本身是错的。
        //
        // 这里直接取该维度的虚空伤害线 —— 那正是"实体/载具何时该被判为坠入虚空"的
        // 唯一权威来源（overworld 配置为 -128）。若维度未配置则回退到原值 -64。
        int level = HeightManager.getVoidDamageLevel(world);

        return level == 0 ? originalY : level;
    }
}