package sayys.depthsupdate.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import sayys.depthsupdate.core.HeightManager;

/**
 * Adjusts the void damage Y threshold to match the configured void damage level
 * instead of vanilla's hardcoded -64.
 *
 * <h2>★ 这是"深层实体消失"的根因所在（反汇编已确认）</h2>
 *
 * <p>原版 {@code Entity.onEntityUpdate()} 尾部：
 * <pre>
 *  391: aload_0
 *  392: getfield  posY
 *  395: ldc2_w    -64.0d          &lt;-- 本 mixin 修改的常量
 *  398: dcmpg
 *  399: ifge 406                 if (posY &gt;= 阈值) 跳过
 *  402: aload_0
 *  403: invokevirtual outOfWorld()
 * </pre>
 *
 * <p>而 {@code Entity.outOfWorld()} 的实现是：
 * <pre>
 *  protected void outOfWorld() {
 *      this.setDead();          &lt;-- 直接标记死亡：无伤害、无事件、无日志
 *  }
 * </pre>
 *
 * <p>因此只要 {@code posY &lt; 阈值}，实体就<b>静默死亡</b>。这解释了全部实测现象：
 * <ul>
 *   <li>深层实体"消失" —— 被 setDead() 移除；</li>
 *   <li>岩浆/火焰伤害无效 —— 实体在受到环境伤害之前就已经死了；</li>
 *   <li>坠落伤害却有效 —— 坠落结算发生在同一个 tick 的死亡判定之前；</li>
 *   <li>存档里 y&lt;0 实体 = 0 —— 死掉的实体不会写盘；</li>
 *   <li>玩家在 y=-55 不受影响 —— -55 &gt; -64，仍在阈值之上。</li>
 * </ul>
 *
 * <p>配置里 {@code Void Damage Level = -128}，所以这里必须把阈值替换成
 * {@link HeightManager#getVoidDamageLevel(World)}。若替换失效，阈值仍是 -64，
 * 深层实体就会在 y&lt;-64 被静默杀死。
 */
@Mixin(Entity.class)
public abstract class MixinEntity {
    @Shadow
    public World world;

    @ModifyConstant(method = "onEntityUpdate", constant = @Constant(doubleValue = -64.0D))
    private double depthsupdate$modifyVoidDamageLevel(double original) {
        double level = HeightManager.getVoidDamageLevel(this.world);

        return level;
    }
}
