package sayys.depthsupdate.core;

/**
 * 让其它 mixin 能触发"整区块深层光照播种"的接口。
 *
 * <h2>为什么需要它</h2>
 * 播种逻辑需要访问 {@code MixinChunk} 的 {@code @Unique} 成员
 * （{@code scanExtendedColumn} / {@code extendedEmitterSections} 等），
 * 因此实现只能留在 {@code MixinChunk} 里；而<b>调用的时机</b>却在别处 ——
 * {@code ChunkProviderServer.provideChunk} 返回时（深层填充完成之后）。
 *
 * <p>与 {@link ChunkCarveMarker} 同一套路：一个普通接口，由 {@code MixinChunk}
 * 实现，其它 mixin 通过把 chunk 转型到本接口来调用。
 * （{@code @Accessor} 无法解析另一个 mixin 注入的 {@code @Unique} 成员，
 * Mixin 会报 "Cannot find field in target class"，所以不能用访问器。）
 *
 * <h2>★ 为什么必须在"深层填充之后"再播种一次</h2>
 * 反汇编 {@code ChunkProviderServer.provideChunk} 可见其顺序：
 * <pre>
 *    3: loadChunk(x, z)               // 从磁盘加载：深层段已存在
 *    8: ifnonnull 144                 // 命中则直接返回
 *   24: chunkGenerator.generateChunk(x, z)   // 新生成：只有浅层地形
 *  125: loadedChunks.put(key, chunk)
 *  132: chunk.onLoad()                // ★ onLoad 在这里
 * </pre>
 * 而本模组的深层填充（bedrock / deepslate / stone / 岩浆）挂在
 * {@code provideChunk} 的 <b>RETURN</b> 上 —— 也就是 {@code onLoad} <b>之后</b>。
 *
 * <p>于是对新生成的区块：
 * <ol>
 *   <li>{@code onLoad} 里的播种先跑 —— 此时深层段<b>还不存在</b>，
 *       {@code extendedEmitterSections()} 返回空，什么也没播；</li>
 *   <li>深层填充才创建出含岩浆的深层段 —— 但已经没人再播种了。</li>
 * </ol>
 * 结果：新探索区域的深层岩浆旁边永远是黑的（实测 {@code /depthsupdate scan}
 * 报出 920 处 {@code dark air}），而玩家以前去过、从磁盘加载的区块反而正常
 * —— 因为那条路径上深层段在 {@code onLoad} 之前就已从 NBT 恢复。
 *
 * <p>因此深层填充结束后必须再调一次本方法。实现内部有幂等标志，
 * 重复调用不会造成多余开销。
 */
public interface ExtendedLightSeeder {

    /**
     * 对整区块（全部 256 列）的深层发光方块补做一次光照播种。
     *
     * <p>幂等：已播种过、或深层段尚不存在时直接返回。
     */
    void depthsupdate$seedExtendedLight();

    /**
     * 让"深层发光段"的候选缓存失效，强制下一次播种重新扫描。
     *
     * <p>深层填充是<b>直接操作 {@code ExtendedBlockStorage}</b> 的，不经过
     * {@code Chunk.setBlockState}，因此不会触发那条路径上的缓存失效。
     * 若不显式失效，填充前算出的"空候选列表"会被一直复用，
     * 单格岩浆这类孤立光源就永远不会被播种（连自身都不亮）。
     */
    void depthsupdate$invalidateEmitterCache();
}
