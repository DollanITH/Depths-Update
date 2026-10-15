package sayys.depthsupdate.core;

import net.minecraft.world.World;

import javax.annotation.Nullable;

/**
 * 记录"当前线程正在为哪个维度生成区块/ChunkPrimer"。
 *
 * <h2>为什么需要它</h2>
 * 生成管线里有若干位置<b>拿不到 {@link World}</b>，却被硬性要求按维度计算高度范围：
 *
 * <ul>
 *   <li>{@code ChunkPrimer.getBlockIndex(int,int,int)} 是 <b>static</b>，
 *       签名里没有世界/维度，只能靠"当时正在生成谁"来推断；</li>
 *   <li>{@code Sponge 的 ChunkPrimerBuffer.getBlockStart(int,int)} 同样是 <b>static</b>。</li>
 * </ul>
 *
 * <h2>曾经的错误做法与后果</h2>
 * 这些地方此前一律使用 {@link HeightManager#getMaxContext()} ——
 * 那是"所有已配置维度里范围最大的那个"，而不是当前维度的。
 *
 * <p>本项目服务器上的实际配置：
 * <pre>
 *   dim  0 (主世界)     : minY = -64, maxY = 400   &lt;- 即 maxContext
 *   dim  3 (formwork)   : minY =   0, maxY = 320
 * </pre>
 * 于是生成 formwork（模板世界）时缓冲起点被设成 {@code -64}，
 * 而该维度基准是 {@code 0}，整个缓冲<b>偏移 64 格</b>，
 * 表现为"服务端该维度完全虚空、客户端正常"
 * （客户端没有 Sponge，不经过 {@code ChunkPrimerBuffer}）。
 *
 * <h2>★ 哨兵值必须与合法维度 ID 完全不重叠</h2>
 * 本类最初用 {@code dimensionId + 1} 编码维度、{@code 0} 表示"无记录"。
 * 这在维度 ID 为 <b>-1（下界）</b> 时发生冲突：{@code -1 + 1 == 0}，
 * 于是下界被误判成"没有记录"，调用方回退到 {@code getMaxContext()}（dim 0，minY = -64），
 * 使下界的索引整体 {@code y - (-64) = y + 64} —— <b>世界被下移 64 格</b>。
 *
 * <p>现在直接存储维度 ID，并用 {@link #NO_DIMENSION}（{@code Integer.MIN_VALUE}）
 * 作为"无记录"哨兵 —— 这个值不可能是任何真实维度 ID，因此不存在冲突。
 *
 * <h2>用法</h2>
 * 在能拿到 {@link World} 的上游（区块生成入口）调用 {@link #push(World)}，
 * 结束时 {@link #pop()}；下游的 static 方法用 {@link #current()} 取代 getMaxContext()。
 * 采用栈式记录以支持嵌套生成。
 */
public final class ChunkGenContext {

    /** "没有记录"的哨兵。真实维度 ID 不可能是这个值。 */
    private static final int NO_DIMENSION = Integer.MIN_VALUE;

    /** 每线程一份的维度栈（直接存维度 ID）。 */
    private static final ThreadLocal<int[]> STACK = ThreadLocal.withInitial(() -> new int[8]);

    /** 当前栈深度。 */
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private ChunkGenContext() {
    }

    /**
     * 进入一次"为 {@code world} 生成区块"的过程。
     */
    public static void push(@Nullable World world) {
        int depth = DEPTH.get();
        int[] stack = STACK.get();

        if (depth >= stack.length) {
            int[] grown = new int[stack.length * 2];
            System.arraycopy(stack, 0, grown, 0, stack.length);
            STACK.set(grown);
            stack = grown;
        }

        // 直接存维度 ID（下界是 -1，合法且可表示）。
        stack[depth] = world == null ? NO_DIMENSION : world.provider.getDimension();
        DEPTH.set(depth + 1);
    }

    /** 退出一次生成过程。 */
    public static void pop() {
        int depth = DEPTH.get();

        if (depth > 0) {
            DEPTH.set(depth - 1);
        }
    }

    /**
     * 当前正在生成的维度 ID；没有记录时返回 {@link #NO_DIMENSION}。
     */
    public static int currentDimensionId() {
        int depth = DEPTH.get();

        return depth <= 0 ? NO_DIMENSION : STACK.get()[depth - 1];
    }

    /**
     * 当前生成过程适用的高度上下文。
     *
     * <p>有记录时返回<b>该维度的</b>上下文；没有记录时退回
     * {@link HeightContext#VANILLA}（而不是 getMaxContext()，
     * 以免再次把"其它维度的范围"套到当前读写上）。
     */
    public static HeightContext current() {
        int dim = currentDimensionId();

        return dim == NO_DIMENSION ? HeightContext.VANILLA : HeightManager.get(dim);
    }

    /**
     * 当前生成过程适用的上下文；<b>没有记录时同样退回该维度的默认值</b>。
     *
     * <p>注意：{@code fallback} 只在"完全没有记录"时使用。
     * 早期版本由调用方传 {@code getMaxContext()} 作为兜底，
     * 这会在哨兵冲突/未接线时把最大维度的范围误用到当前维度上
     * （例如把下界的索引整体偏移 64 格）。因此这里对"有维度记录但该维度未配置扩展"
     * 的情况，一律返回 {@link HeightContext#VANILLA}，绝不返回别的维度的范围。
     *
     * @param fallback 仅在完全没有维度记录时使用的兜底上下文
     */
    public static HeightContext currentOr(HeightContext fallback) {
        int dim = currentDimensionId();

        if (dim == NO_DIMENSION) {
            return fallback;
        }

        // ★ 有明确维度：该维度没配置扩展时返回 VANILLA，而不是别的维度。
        return HeightManager.get(dim);
    }
}
