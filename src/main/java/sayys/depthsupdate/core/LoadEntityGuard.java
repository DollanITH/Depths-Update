package sayys.depthsupdate.core;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;

/**
 * "区块加载实体"阶段标志 + 扩展区间实体保留判定。
 *
 * <p>为什么单独放在普通类里（而不是 Mixin 的静态字段）：
 * Mixin 明确禁止 mixin 类含有<b>非 private 的 static 字段</b>，否则 APPLY 阶段直接报
 * <pre>
 *   InvalidMixinException: ... contains non-private static field
 * </pre>
 * 并导致 {@code NoClassDefFoundError: net/minecraft/world/WorldServer} → 服务端无法启动。
 * 因此把共享状态放到这个普通的工具类中，mixin 只调用它的静态方法。
 *
 * <h2>背景（实体消失的根因）</h2>
 * {@code Entity.setDead()} 的调用栈实测为：
 * <pre>
 *   Entity.setDead()
 *     &lt;- WorldServer.handler$bbi000$sponge$spongeLoadEntities()
 *     &lt;- WorldServer.func_175650_b()
 *     &lt;- ChunkIOProvider.syncCallback()            // 区块从磁盘加载
 *     &lt;- WorldManager.createWorldFromProperties()  // 服务器启动
 * </pre>
 * Sponge 那段 handler 的字节码硬编码了 {@code posY &gt;= 0}：
 * <pre>
 *   68: entity.field_70163_u   // posY
 *   73: dconst_0
 *   75: ifge 86                if (posY &gt;= 0.0) 保留
 *   78: entity.func_70106_y()  // setDead()
 * </pre>
 * 于是深层（y&lt;0）与超高层（y&gt;=256）实体在区块加载时被无条件删除。
 */
public final class LoadEntityGuard {

    private LoadEntityGuard() {
    }

    /**
     * "区块加载实体"阶段的嵌套深度。
     *
     * <p>★ 必须是<b>计数器</b>而不是布尔值 —— 这是本项目实测踩到的坑。
     *
     * <p>{@code Chunk.onLoad} 会<b>嵌套</b>调用（加载区块 A 的过程中触发加载区块 B）。
     * 若用布尔标志：
     * <pre>
     *   onLoad(A) HEAD   -&gt; LOADING = true
     *     onLoad(B) HEAD -&gt; LOADING = true      (嵌套)
     *     onLoad(B) RET  -&gt; LOADING = false     &lt;- ★ 提前清除
     *     ...A 的实体加载仍在继续，但标志已为 false，实体失去保护...
     *   onLoad(A) RET    -&gt; LOADING = false
     * </pre>
     * 实测表现正是"同一批实体里，一个被保留、下一个又被删除"交替出现 ——
     * 日志里 {@code KEEP entity=EntitySkeleton y=-1.0} 与
     * {@code SETDEAD entity=EntitySkeleton y=-0.005} 反复交替，就是这个原因。
     *
     * <p>用 {@link ThreadLocal} 的整数深度：区块读取可能发生在异步 IO 线程上，
     * 全局变量会误判。
     */
    private static final ThreadLocal<Integer> LOAD_DEPTH = ThreadLocal.withInitial(() -> 0);

    /** 进入区块加载阶段（可嵌套）。 */
    public static void beginLoad() {
        LOAD_DEPTH.set(LOAD_DEPTH.get() + 1);
    }

    /** 退出区块加载阶段；只在最外层退出时真正清除。 */
    public static void endLoad() {
        int depth = LOAD_DEPTH.get() - 1;

        if (depth <= 0) {
            LOAD_DEPTH.remove();
        } else {
            LOAD_DEPTH.set(depth);
        }
    }

    public static boolean isLoading() {
        return LOAD_DEPTH.get() > 0;
    }

    // ------------------------------------------------------------------
    // 世界注册（激活）支持
    // ------------------------------------------------------------------

    /**
     * 从待加载集合中取出"扩展区间内"的实体，使 Sponge 的过滤器看不到它们。
     *
     * <h2>为什么需要这一步</h2>
     * Sponge 的 {@code spongeLoadEntities} 字节码：
     * <pre>
     *   68: entity.posY
     *   73: dconst_0
     *   75: ifge 86                 if (posY &gt;= 0) 跳去"加入世界"
     *   78: entity.func_70106_y()   // setDead()
     *   83: goto 30                 // ★ 跳到下一个实体
     *   86: if (func_184165_i(entity)) { ...加入世界... }
     * </pre>
     *
     * <p>此前我们只在 {@code Entity.setDead} 上取消了删除 —— 实体确实不再被删，
     * <b>但控制流仍走 {@code goto 30}</b>，于是实体既没被删、也<b>没有被加入世界</b>，
     * 只是留在 {@code chunk.entityLists} 里。实测后果：
     * <ul>
     *   <li>实体能被存盘（存档里 {@code y<0} 实体从 0 变成 179）；</li>
     *   <li>但下次加载后不可见、无 AI、{@code /kill} 也打不到它
     *       （因为它不在 {@code world.loadedEntityList} 里）。</li>
     * </ul>
     *
     * <p>因此改为：在 Sponge 处理之前把这些实体<b>从集合里取走</b>，
     * 让它完全不参与 Sponge 的那段逻辑；等 Sponge 处理完再自己把它们注册进世界。
     *
     * @param entities Sponge 即将处理的实体集合
     * @param out      取出的实体将加入此列表
     */
    public static void pullExtended(Collection<Entity> entities, List<Entity> out) {
        Iterator<Entity> it = entities.iterator();

        while (it.hasNext()) {
            Entity e = it.next();

            if (isExtendedEntity(e)) {
                out.add(e);
                it.remove();
            }
        }
    }

    // ------------------------------------------------------------------
    // 待激活队列
    //
    // 为什么需要它：Sponge 的过滤逻辑所在的方法（loadEntities / func_175650_b）
    // 用 SRG 名与 MCP 名【都无法命中】注入（实测 PULL/ACTIVATE 计数恒为 0，
    // 但 SETDEAD 有输出，证明那段代码确实在执行）。
    //
    // 因此不再尝试注入那个方法，改为：
    //   1. 在【已验证可命中】的 Entity.setDead 里，取消删除的同时把实体记下来；
    //   2. 在【已验证可命中】的 Chunk.onLoad RETURN 里统一激活。
    // 两个注入点本项目都长期在用，稳定性有保证。
    // ------------------------------------------------------------------

    private static final java.util.Set<Entity> PENDING_ACTIVATION =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    /** 记下一个"已取消删除、但尚未加入世界"的实体。 */
    public static void markForActivation(Entity entity) {
        if (entity != null) {
            PENDING_ACTIVATION.add(entity);
        }
    }

    /**
     * 激活所有待处理实体。
     *
     * <p>在 {@code Chunk.onLoad} 的 RETURN 调用 —— 此时 Sponge 的实体过滤已经跑完，
     * 这些实体既没被删除、也（因 Sponge 的 {@code goto 30}）没被加入世界，
     * 正需要我们自己补上这一步。
     */
    public static void activatePending() {
        if (PENDING_ACTIVATION.isEmpty()) {
            return;
        }

        java.util.List<Entity> batch = new java.util.ArrayList<>(PENDING_ACTIVATION);
        PENDING_ACTIVATION.clear();

        activate(batch);
    }
    /** 实体是否位于其维度的扩展区间（且在原版区间之外）。 */
    public static boolean isExtendedEntity(Entity e) {
        if (e == null || e.world == null) {
            return false;
        }

        HeightContext ctx = HeightManager.get(e.world);

        if (!ctx.isExtended()) {
            return false;
        }

        double y = e.posY;

        return (y < 0.0D || y >= 256.0D) && y >= ctx.minY() && y < ctx.maxY();
    }

    /**
     * 把先前取出的实体正式注册进世界（等价于 Sponge 那条被跳过的
     * {@code 86:} 分支所做的事）。
     *
     * <p>关键：实体此时<b>已经在 {@code chunk.entityLists} 里</b>（由
     * {@code AnvilChunkLoader.readChunkEntity} 在反序列化阶段加入）。
     * 若直接调用 {@code world.spawnEntity}，会再执行一次 {@code Chunk.addEntity}
     * 造成<b>重复登记</b> —— 实测后果正是 Sponge 刷
     * <pre>
     *   EntitySheep[x=-72.55, y=-53.00, z=14.45] is not in chunk (-5, 1)
     *     and is instead in chunk (-5, 0) ..., skipping save.
     * </pre>
     * 即实体同时挂在两个区块的列表里，存盘时被判为非法而跳过。
     *
     * <p>因此这里先从原列表移除、清掉 {@code addedToChunk} 标记，再调用
     * {@code world.spawnEntity}，保证只有一次干净的登记。
     */
    public static void activate(List<Entity> pulled) {
        for (Entity e : pulled) {
            try {
                World world = e.world;

                if (world == null) {
                    continue;
                }

                BlockPos pos = new BlockPos(e.posX, e.posY, e.posZ);
                Chunk chunk = world.getChunk(pos);

                // 先把实体从它当前所在的 entityLists 槽位移除（避免重复登记）。
                net.minecraft.util.ClassInheritanceMultiMap<Entity>[] lists = chunk.getEntityLists();
                int k = e.chunkCoordY;

                if (lists != null && k >= 0 && k < lists.length && lists[k] != null) {
                    lists[k].remove(e);
                }

                e.addedToChunk = false;

                // 现在做一次干净的注册：加入区块 lists + world.loadedEntityList + 追踪器。
                world.spawnEntity(e);
            } catch (Throwable ignored) {
                // 激活失败不应影响区块加载流程；实体保持原状即可。
            }
        }
    }

    /**
     * 是否应当保留该实体（即忽略这次 {@code setDead()}）。
     *
     * <p>判定条件只有一个：实体位于该维度的扩展区间 {@code [minY, maxY)} 内。
     * </ol>
     * 因此正常的死亡、despawn、区块卸载都不受影响 —— 只有"Sponge 因
     * {@code posY >= 0} 假设而删除合法深层实体"这一种情况会被拦下。
     *
     * @return true 表示应当取消这次 {@code setDead()}
     */
    /**
     * 是否应当保留该实体（即取消这次 {@code setDead()}）。<b>默认不保留。</b>
     *
     * <h2>★ 为什么必须限定"哪一次删除"</h2>
     *
     * <p>根因回顾：Sponge 的 {@code spongeLoadEntities} 里硬编码了 {@code posY &gt;= 0}，
     * 于是深层（y&lt;0）与超高层（y&gt;=256）实体在<b>区块从磁盘加载</b>的那一刻被
     * 无条件 {@code setDead()}。
     *
     * <p>但 {@code setDead()} 是<b>通用</b>的移除手段 —— 死亡、despawn、区块卸载、
     * 掉落物被清理、方块实体转换……全都走它。曾经有一版为了图省事只按坐标判断，
     * 结果把扩展区间内<b>所有</b>实体的移除都拦了下来，直接导致：
     * <pre>
     *   掉落物一大堆、打都打不掉
     * </pre>
     * （掉落物合并不掉、超时清理不掉，越积越多。）这是必须避免的严重副作用。
     *
     * <h2>判定条件</h2>
     * 只拦"Sponge 在区块加载阶段因 y 坐标假设而误删实体"这一种情况，因此两条必须同时成立：
     * <ol>
     *   <li>该实体<b>正处于区块加载阶段</b>被处理（{@link #isLoading()}）；</li>
     *   <li>实体位于该维度扩展区间 {@code [minY, maxY)} 内（即原版区间之外的部分）。</li>
     * </ol>
     *
     * <p>条件 1 由 {@link #beginLoad()} 置位 —— 它挂在
     * {@code AnvilChunkLoader.readChunkFromNBT} 上（那是"从磁盘读区块"的确定入口，
     * 且是本项目已经稳定使用多年的注入点），而不是去猜 Sponge 内部的方法名。
     *
     * @return true 表示应当取消这次 {@code setDead()}
     */
    public static boolean shouldKeep(Entity entity) {
        if (entity == null) {
            return false;
        }

        // ★ 下落方块【不走】这条保留逻辑 —— 已撤回。
        //
        // 曾经为下落方块加过一个专用分支（取消 Sponge 的 setDead，让实体继续存活），
        // 但实测产生了**不符合原版的行为**：
        //   原版语义是「落成方块」与「产生掉落物」二选一；
        //   而取消 setDead 后，实体继续跑 onUpdate，既落成了方块，
        //   Sponge 那边被取消的删除流程又留下了痕迹 —— 变成了两者都发生。
        //
        // 正确的方向不是"取消删除"，而是让 Sponge 的事件提交
        // （TrackingUtil.processBlockCaptures）在扩展区间也能成功，
        // 或者让下落方块走完全不受 Sponge 事件管线影响的落地路径。
        // 在那之前，宁可保持原状，也不要引入偏离原版的副作用。
        // ★ 下落方块：取消 Sponge 的误删，让它走【原版】落地逻辑。
        //
        // Sponge 的 EntityFallingBlockMixin.onWorldSetBlockToAir：
        //   @Inject(method = "onUpdate",
        //            at = @At(INVOKE World.setBlockToAir, shift = AFTER), cancellable)
        //   34-51: if (!ctx.state.doesBulkBlockCapture(ctx)) goto 72
        //   54-58: if (TrackingUtil.processBlockCaptures(ctx)) goto 97   // 成功 -> 放行
        //   61-66: else { setDead(); ci.cancel(); }                       // ★ 失败 -> 删除
        //   72-86: if (getBlockState(pos) != AIR) { setDead(); ci.cancel(); }
        //
        // 实测：setBlockToAir 成功（returned=true），该格确实变成空气，
        // 且返回的就是 Blocks.AIR.getDefaultState() 单例（sameInstance=true）
        // —— 所以 72-86 那条不是原因；触发的是 54-66：
        // 扩展区间内 doesBulkBlockCapture 为真，而 processBlockCaptures
        // 在扩展区间无法完成提交而返回 false，于是实体被删除并 ci.cancel()，
        // 本 tick 后续的落地逻辑全部被跳过 —— 既不成方块也不掉落。
        //
        // 处理：在扩展区间内取消这次删除，把控制权交回原版 onUpdate。
        // ★ 注意【不要】在这里调用 world.spawnEntity —— 之前那样做过，
        //   会让落方块"既变成方块又产生掉落物"，偏离原版"二选一"的语义。
        // ★ 下落方块：只拦【第一 tick 中 Sponge 误判】的那一次 setDead。
        //
        // 上一版这里无条件返回 isExtendedEntity(entity) —— 结果连"正常落地后
        // 的收尾 setDead"也一并取消了，实体永不消失、反复执行落地逻辑，
        // 实测表现为：已经落成方块、却持续刷出无限掉落物（打掉方块也会再刷）。
        // 这是危险的副作用，必须收窄。
        //
        // 判别依据 fallTime：原版 EntityFallingBlock.onUpdate 开头是
        //   ++fallTime; if (旧值 != 0) 跳过"清空自己那格"的分支
        // 因此 Sponge 在 setBlockToAir 之后误判的那一次，fallTime 刚被加到 1；
        // 而正常落地结算发生在 fallTime > 1 且 onGround 为真时。
        // 所以只保护 fallTime <= 1 的情况，其余 setDead 一律放行。
        if (entity instanceof net.minecraft.entity.item.EntityFallingBlock) {
            int fallTime = ((net.minecraft.entity.item.EntityFallingBlock) entity).fallTime;

            if (fallTime > 1 || !isExtendedEntity(entity)) {
                return false;
            }

            // 再要求"实体所占那一格确实是空气" —— 这精确对应
            // Sponge 在 setBlockToAir 成功之后误判的那一次。
            // （原版在"该格方块与 fallTile 不符"时也会在同一 tick 调 setDead，
            //   那种情况此格不是空气，因而不会被我们误保护。）
            double y = entity.posY;
            net.minecraft.util.math.BlockPos pos =
                    new net.minecraft.util.math.BlockPos(entity.posX, y, entity.posZ);

            return entity.world != null
                    && entity.world.getBlockState(pos).getBlock() == net.minecraft.init.Blocks.AIR;
        }

        if (!isLoading()) {
            return false;
        }

        World world = entity.world;

        if (world == null) {
            return false;
        }

        HeightContext ctx = HeightManager.get(world);

        if (!ctx.isExtended()) {
            return false;
        }

        double y = entity.posY;

        // 只保护"原版区间之外、但属于扩展区间"的坐标。
        // 0..255 一律放行，避免影响任何原版行为（曾因放行过宽导致掉落物堆积）。
        boolean keep = (y < 0.0D || y >= 256.0D) && y >= ctx.minY() && y < ctx.maxY();

        return keep;
    }
}
