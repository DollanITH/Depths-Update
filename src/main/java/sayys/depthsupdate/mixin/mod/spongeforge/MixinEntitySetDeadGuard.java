package sayys.depthsupdate.mixin.mod.spongeforge;

import net.minecraft.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import sayys.depthsupdate.core.LoadEntityGuard;


/**
 * ★ 修复"深层/超高层实体在重启服务器后消失"。
 *
 * <h2>为什么在 {@code Entity.setDead} 上拦截</h2>
 * 调用栈（实测多次一致）：
 * <pre>
 *   Entity.setDead()                                &lt;- 唯一不依赖 Sponge 命名的锚点
 *     &lt;- WorldServer.handler$bbi000$sponge$spongeLoadEntities()
 *     &lt;- WorldServer.func_175650_b()
 *     &lt;- ChunkIOProvider.syncCallback()             &lt;- 区块从磁盘加载
 *     &lt;- WorldManager.createWorldFromProperties()   &lt;- 服务器启动
 * </pre>
 *
 * <p>Sponge 那段 handler 硬编码 {@code posY &gt;= 0}，于是深层（y&lt;0）与
 * 超高层（y&gt;=256）实体在区块加载的瞬间被无条件 {@code setDead()}。
 *
 * <p>针对 Sponge 内部方法名做过三次注入尝试全部未命中，因此改在
 * {@code Entity.setDead} 上拦截 —— 它是调用栈里唯一名字确定、绕不过去的地方。
 *
 * <p>判定条件由 {@link LoadEntityGuard#shouldKeep} 收紧为
 * "加载阶段 + 位于扩展区间内"两条同时成立，因此不会影响正常的
 * 死亡、despawn、区块卸载等流程。
 */
@Mixin(value = Entity.class, priority = 1100)
public abstract class MixinEntitySetDeadGuard {

    @Inject(method = "setDead", at = @At("HEAD"), cancellable = true)
    private void depthsupdate$keepExtendedHeightEntityOnLoad(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;

        if (!LoadEntityGuard.shouldKeep(self)) {
            return;
        }

        // ★ 只是取消 setDead 是【不够的】—— 这是本项目排查最久的一个坑。
        //
        // Sponge 的 spongeLoadEntities 字节码：
        //    75: ifge 86                 if (posY >= 0) 跳去"加入世界"
        //    78: entity.func_70106_y()   // setDead()  <- 守卫在这里取消
        //    83: goto 30                 // ★ 然后【跳到下一个实体】
        //    86: if (func_184165_i(entity)) { ...加入待 spawn 列表... }
        //
        // 即：取消 setDead 后控制流仍走 `goto 30` —— 实体既没被删除，
        // 也【没有】被加入世界，只是留在 chunk.entityLists 里；等区块卸载/重载时
        // 就真的消失了。实测印证：KEEP 日志正常打印，但实体重启后仍不见。
        //
        // 因此这里补上 Sponge 因走删除分支而跳过的那一步：主动把实体加入世界。

        ci.cancel();

        // 记下这个实体：它没被删除，但也因 Sponge 的 `goto 30` 没被加入世界。
        // 稍后在 Chunk.onLoad 的 RETURN 统一激活（见 MixinChunk 与 LoadEntityGuard）。
        // 只在"区块加载阶段"记录待激活实体。
        // 非加载阶段（例如下落方块在正常 tick 中被 Sponge 误删而取消）
        // 不应进入队列 —— 否则下次区块加载会把它重复注册一次。
        if (sayys.depthsupdate.core.LoadEntityGuard.isLoading()) {
            sayys.depthsupdate.core.LoadEntityGuard.markForActivation(self);
        }

        // ★★ 不要在这里调用 world.spawnEntity(self) —— 实测会造成严重副作用。
        //
        // 曾经加过 `self.world.spawnEntity(self)`，想补上 Sponge 因走删除分支而跳过的
        // "加入世界"那一步。结果服务端开始刷：
        //     EntitySheep[x=-72.55, y=-53.00, z=14.45] is not in chunk (-5, 1)
        //     and is instead in chunk (-5, 0) ..., skipping save.
        //     EntityBat[x=-65.58, y=1.64, z=-20.83] is not in chunk (-4, -2) ...
        // 即实体被登记到了与自身坐标不符的区块，Sponge 存盘时会 skip 掉它 ——
        // 实体虽"没被删除"，却【依然不会被保存】。
        //
        // 原因：本注入点在 Sponge spongeLoadEntities 循环【内部】，此时实体刚由 NBT
        // 反序列化，其区块归属尚未确定；在这里再调一次 spawnEntity 会用过时的 chunk
        // 信息重复登记，导致 (chunk.x, chunk.z) 与实际坐标错位。连 y=1.64 的蝙蝠都被
        // 波及（它并不满足深/高层条件），说明重复登记会污染整个加载流程。
        //
        // 因此这里【只取消 setDead】，不再自行登记 —— 保留实体原先在
        // chunk.entityLists 中的归属，交由 Sponge/原版的加载流程继续处理：
        // 实体既不会被删除，也不会被重复登记。
    }
}