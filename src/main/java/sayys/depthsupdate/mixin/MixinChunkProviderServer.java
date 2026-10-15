/**
 * 合并版 ChunkProviderServer Mixin（fork 本地逻辑 + 上游架构）
 * Minecraft版本: 1.12.2
 * 模组: Depths Update
 * <p>
 * 这个Mixin修改了世界生成流程，合并后主要功能：
 * 1. 扩展世界高度（Height Extension）
 * 2. 基岩过滤（Bedrock Filter，上游机制：生成期间在源头取消 y=0..4 的基岩写入）
 * 3. 自定义世界深度填充（DeepFill 分带填充，上游机制）
 * 4. 生成地下河流（Underground Rivers）
 * 5. 生成洞穴噪音（Cave Noise）
 * 6. 老式深穴雕刻（OldStyleDeepCaveCarver，fork 保留）
 * 7. 含水层生成（Aquifers，fork 保留）
 * <p>
 * 与上游的差异（fork 保留的行为）：
 * - 上游只处理自定义世界；fork 同时处理原版主世界（generateChunk 返回后重填深度并雕刻）。
 * - 基岩过滤仅在自定义世界激活；原版主世界仍用事后替换（backstop）把 y=0..4 的基岩换成石头。
 * - 洞穴/含水层/老式深穴均保留 fork 的配置开关。
 */

package sayys.depthsupdate.mixin;

import java.util.Random;

import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.world.gen.ChunkGeneratorDebug;
import net.minecraft.world.gen.ChunkGeneratorFlat;
import net.minecraft.world.gen.ChunkGeneratorOverworld;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraft.world.gen.IChunkGenerator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import sayys.depthsupdate.DepthsUpdateConfig;
import sayys.depthsupdate.core.ChunkGenContext;
import sayys.depthsupdate.core.DeepFill;
import sayys.depthsupdate.core.HeightContext;
import sayys.depthsupdate.core.HeightManager;
import sayys.depthsupdate.core.ChunkCarveMarker;
import sayys.depthsupdate.util.BlockUtils;
import sayys.depthsupdate.world.generation.AquiferGenerator;
import sayys.depthsupdate.world.generation.ChunkPrimerAdapter;
import sayys.depthsupdate.world.generation.OldStyleDeepCaveCarver;
import sayys.depthsupdate.world.generation.noise.CaveNoiseGenerator;
import sayys.depthsupdate.world.generation.river.UndergroundRiverGenerator;

/**
 * Mixin用于修改ChunkProviderServer的行为
 */
// priority = 1100：SpongeForge 的 ChunkProviderServerMixin 会介入区块生成流程，
// 需要本 mixin 在其之后应用，保证 provideChunk 返回后的深度填充注入保留。
@Mixin(value = {ChunkProviderServer.class}, priority = 1100)
public class MixinChunkProviderServer {

    @Shadow
    @Final
    public IChunkGenerator chunkGenerator;

    @Shadow
    @Final
    public WorldServer world;

    @Unique
    private Random depthsupdate$fillRandom;

    @Unique
    private UndergroundRiverGenerator depthsupdate$riverGenerator;

    @Unique
    private CaveNoiseGenerator depthsupdate$noiseCaveGenerator;

    @Unique
    private AquiferGenerator depthsupdate$aquiferGenerator;

    /**
     * 已做过深度后处理的 chunk 坐标（(x<<32)|z）。provideChunk 对同一 chunk 会被
     * populate 期间反复调用（RTG 地牢等 getBlockState→getChunk 每次都命中缓存），
     * 若每个缓存命中都重跑填充+日志会刷屏并卡死 Server thread。用此集合保证每 chunk
     * 只处理一次。
     */
    @Unique
    private final java.util.Set<Long> depthsupdate$processedChunks = new java.util.HashSet<>();

    /**
     * Upper bound for {@link #depthsupdate$processedChunks}. The set is only used to
     * de-duplicate the repeated provideChunk() calls a single generation pass makes,
     * so it never needs to remember more than the chunks in flight. It is a
     * StrongReference: keeping every chunk coordinate ever generated for the whole
     * server lifetime leaked memory on long sessions, so the set is cleared once it
     * grows past this bound (a false negative merely re-runs the idempotent deep
     * post-processing, which is safe).
     */
    @Unique
    private static final int DEPTHSUPDATE_MAX_PROCESSED_CHUNKS = 8192;

    /**
     * 已在本会话内为其补做过"深层光照播种"的 chunk 坐标（(x<<32)|z）。
     *
     * <h2>为什么需要【独立于】processedChunks 的第二套去重</h2>
     * {@code isCarved()} 为真的区块走的是 {@code onProvideChunk} 里最靠前的
     * 那条 early-return 路径（读盘路径会无条件置位该标记），
     * 根本到不了 {@link #depthsupdate$processedChunks} 的去重逻辑。
     *
     * <p>而 {@code seedExtendedLight()} 自身的幂等标志
     * （{@code extendedLightSeeded}）<b>只在真的播种到光源时才置位</b>；
     * 对"深层没有发光方块的区块"（绝大多数！）它永远不会置位，
     * 于是每次 {@code provideChunk} 都会重扫 256 列 ——
     * 而本路径实测命中 1052 万次，TPS 会直接崩掉。
     *
     * <p>因此这里在调用侧再做一次去重，保证每区块每会话只播种一次。
     * 上界与 {@code processedChunks} 同源：触顶即整体清空
     * （最坏情况是重复播种，而播种本身幂等，安全）。
     *
     * <p>用 {@code synchronized} 而非并发集合：本方法虽主要在 Server thread
     * 上执行，但 Sponge 的异步光照/区块加载可能从别的线程进入，
     * 且 {@code HashSet} 非线程安全。该路径不是热路径（每区块只走一次），
     * 锁开销可忽略。
     */
    @Unique
    private final java.util.Set<Long> depthsupdate$lightSeededChunks = new java.util.HashSet<>();

    public MixinChunkProviderServer() {
        super();
    }

    /**
     * 进入区块生成时记录"当前正在为哪个维度生成"。
     *
     * <h2>为什么必须记录</h2>
     * 生成管线里有些地方<b>拿不到 World</b>，却必须按维度算高度范围：
     * {@code ChunkPrimer.getBlockIndex(int,int,int)} 与 Sponge 的
     * {@code ChunkPrimerBuffer.getBlockStart(int,int)} 都是 <b>static</b>。
     *
     * <p>它们此前一律读 {@code HeightManager.getMaxContext()} ——
     * "所有维度中范围最大的那个"，而不是当前维度的。本服配置为：
     * <pre>
     *   dim 0 (主世界)   : minY = -64, maxY = 400   &lt;- 即 maxContext
     *   dim 3 (formwork) : minY =   0, maxY = 320
     * </pre>
     * 于是生成 formwork 时缓冲从 -64 起、长度 464，比该维度实际基准（0）<b>低了 64 格</b>，
     * 超平坦地形被写到世界之外 —— 现象正是"服务端该维度完全虚空、客户端却正常"
     * （客户端无 Sponge，不经过 {@code ChunkPrimerBuffer}）。主世界恰好等于 maxContext，
     * 所以自身一直正常，掩盖了这个 bug。
     *
     * <p>{@code provideChunk} 是所有平台区块生成的必经入口，且持有 {@code this.world}，
     * 因此在这里压栈最可靠；RETURN 处出栈。
     */
    @Inject(
            method = "provideChunk(II)Lnet/minecraft/world/chunk/Chunk;",
            at = @At("HEAD")
    )
    private void depthsupdate$enterChunkGeneration(int chunkX, int chunkZ, CallbackInfoReturnable<Chunk> cir) {
        ChunkGenContext.push(this.world);
    }

    /**
     * 在 ChunkProviderServer.provideChunk(int,int)Chunk 返回时注入，
     * 对刚生成好的 Chunk 做深度后处理（重填深度、基岩 backstop、河流/洞穴/含水层、天空光）。
     *
     * 说明：原实现用 @Redirect 重定向 generateChunk 调用，但其 @At(INVOKE) 指向的
     * IChunkGenerator.generateChunk（接口调用点）在本 Cleanroom 运行时无法命中，导致
     * "Scanned 0 target(s)" 崩溃。改为 @Inject @At("RETURN") 只依赖方法本身（refmap
     * 已验证能映射 provideChunk→func_186025_d），不依赖任何调用点，更稳健。
     */
    @Inject(
            method = "provideChunk(II)Lnet/minecraft/world/chunk/Chunk;",
            at = @At("RETURN")
    )
    private void depthsupdate$onProvideChunk(int chunkX, int chunkZ, CallbackInfoReturnable<Chunk> cir) {
        // 生成阶段结束，先出栈（下面的后处理不再属于"该维度的生成期"）。
        ChunkGenContext.pop();

        Chunk chunk = cir.getReturnValue();
        if (chunk == null) {
            return;
        }

        // 检查是否是原版主世界生成器
        IChunkGenerator generator = this.chunkGenerator;
        boolean isVanillaOverworld = generator instanceof ChunkGeneratorOverworld;

        // 检查是否是扁平化或调试世界生成器
        boolean isFlatOrDebug = generator instanceof ChunkGeneratorFlat || generator instanceof ChunkGeneratorDebug;

        // 检查是否是扩展高度的世界
        boolean isDeepWorld = !isFlatOrDebug &&
                HeightManager.isExtended(this.world) &&
                HeightManager.get(this.world).minY() < 0;

        // 如果是扩展高度世界且不是自定义生成器，则填充深度
        boolean shouldFillCustom = isDeepWorld && !isVanillaOverworld &&
                DepthsUpdateConfig.heightExtension.extendCustomWorldTypes;

        // fork 语义：扩展高度下的原版主世界和自定义世界都要重填深度
        boolean processChunk = isDeepWorld && (isVanillaOverworld || shouldFillCustom);

        if (!processChunk) {
            return;
        }

        // provideChunk() 返回的既可能是刚 generateChunk 出来的新 chunk，也可能是从磁盘
        // loadChunkFromFile 读回的旧 chunk。后者的 deep fill + carve 已经在它被读入时
        // （MixinAnvilChunkLoader.depthsupdate$convertOldWorld 会还原并置位 carve 标记）
        // 完成，或在此前某次会话生成时完成。若这里对它再跑一遍"重填 + 重新雕刻"，会把
        // 玩家在 y<0 放置的每一个方块都覆盖回石头/深板岩、并把洞穴路径里的建筑挖掉 ——
        // 这正是"每次进存档 -Y 区块被重置"的根因。carve 标记在这里起与加载路径同样的
        // 守卫作用：已定稿的区块直接跳过。
        //
        // ★★★ 但是："跳过重填/重雕" 不等于 "跳过光照" ★★★
        //
        // 实测（2026-10-15，运行时计数）：
        //   entered=10523717 | NOT_TARGET=0 | SKIP_CARVED=10523717
        //   SKIP_DEDUP=0 | COMPLETED=0
        //   扫描范围内 121 个区块【全部】走 SKIP_CARVED，COMPLETED 恒为 0。
        //
        // 即：深层光照播种【从来没有执行过】。根因是 isCarved 承担了两个互相
        // 冲突的职责：
        //
        //   职责 A（雕刻守卫）：MixinAnvilChunkLoader 读盘时【必须立即】置位，
        //                       否则重新雕刻会删掉玩家沿洞穴放的方块；
        //   职责 B（光照门禁）：  光照播种恰恰需要在读盘后【仍然执行】。
        //
        // 读盘路径无条件置位 A（见 MixinAnvilChunkLoader:240，注释说明"无论是否
        // 真的挖到东西都算定稿"），于是 B 被一并挡掉 —— 这就是"深层岩浆整块不亮、
        // 且按 LastUpdate 批次成片出现"的根因（播种是按整区块 256 列做的，
        // 跳过即整块跳过；孤立单格岩浆因无邻居代为传播，症状最刺眼）。
        //
        // 修法：把光照播种从 isCarved 守卫下【移出来】。
        //
        // ⚠️ 注意：不要以为"播种自身幂等"就可以随便重复调用 ——
        // extendedLightSeeded 标志【只在真的播种到光源时才置位】，
        // 对深层没有发光方块的区块它永远不会置位。因此必须在调用侧
        // 自己保证"每区块只播种一次"（见下面的 lightSeededChunks）。
        if (((ChunkCarveMarker) (Object) chunk).depthsupdate$isCarved()) {
            // ★ 已定稿的区块：不重填、不重雕，但【仍然需要播种深层光照】。
            //
            // 注意顺序：必须先让发光段候选缓存失效。读盘路径是直接写
            // ExtendedBlockStorage 的（不经过 setBlockState），因此不会触发
            // MixinChunk#setBlockState 里的缓存失效；不显式失效的话，
            // 这次播种会复用旧的候选列表，孤立光源依旧扫不到。
            //
            // ★★ 必须在这里做【调用侧去重】★★
            //
            // provideChunk 在生成期会被极高频调用（实测本路径命中 1052 万次）。
            // 而 seedExtendedLight 的幂等标志 extendedLightSeeded 只在
            // totalSeeded > 0 时才置位 —— 对"深层没有发光方块的区块"
            // （绝大多数区块！）该标志【永远不会】置位，
            // 于是每次调用都会把 256 列完整扫一遍，TPS 会直接崩掉。
            //
            // 因此这里用一个按会话维护的集合把"每区块只播种一次"，
            // 与下方 processedChunks 的思路一致，但独立开来：
            // 因为 isCarved 的区块走的是上面这条 early-return 路径，
            // 根本到不了 processedChunks 的去重。
            long seedKey = ((long) chunkX << 32) | (chunkZ & 0xffffffffL);
            boolean firstSeed;

            synchronized (this.depthsupdate$lightSeededChunks) {
                if (this.depthsupdate$lightSeededChunks.size() >= DEPTHSUPDATE_MAX_PROCESSED_CHUNKS) {
                    this.depthsupdate$lightSeededChunks.clear();
                }

                firstSeed = this.depthsupdate$lightSeededChunks.add(seedKey);
            }

            if (firstSeed) {
                sayys.depthsupdate.core.ExtendedLightSeeder seeder =
                        (sayys.depthsupdate.core.ExtendedLightSeeder) (Object) chunk;

                seeder.depthsupdate$invalidateEmitterCache();
                seeder.depthsupdate$seedExtendedLight();
            }

            return;
        }

        // 每 chunk 只处理一次：缓存命中（populate 期间反复调用 provideChunk）直接返回，
        // 避免对同一 chunk 重复重填 + 重复刷 WARN 日志把 Server thread 卡在控制台锁上。
        long key = ((long) chunkX << 32) | (chunkZ & 0xffffffffL);

        if (this.depthsupdate$processedChunks.size() >= DEPTHSUPDATE_MAX_PROCESSED_CHUNKS) {
            this.depthsupdate$processedChunks.clear();
        }

        if (!this.depthsupdate$processedChunks.add(key)) {
            return;
        }

        HeightContext ctx = HeightManager.get(this.world);
        int minY = ctx.minY();

        // Initialize fillRandom if not already done
        if (this.depthsupdate$fillRandom == null) {
            this.depthsupdate$fillRandom = new Random();
        }
        this.depthsupdate$fillRandom.setSeed((long) chunkX * 341873128712L + (long) chunkZ * 132897987541L);

        IBlockState stone = Blocks.STONE.getDefaultState();
        IBlockState deepslate = BlockUtils.getDeepslateBlockState();
        IBlockState bedrock = Blocks.BEDROCK.getDefaultState();

        int deepslateMaxY = DepthsUpdateConfig.deepslateMaxY;
        int fillMaxY = Math.max(4, deepslateMaxY);

        ExtendedBlockStorage[] storageArrays = chunk.getBlockStorageArray();
        boolean hasSkyLight = this.world.provider.hasSkyLight();

        for (int bx = 0; bx < 16; bx++) {
            for (int bz = 0; bz < 16; bz++) {
                for (int by = minY; by <= fillMaxY; by++) {
                    // 上游 backstop：把直接写入 storage（绕过 primer）的 y=0..4 基岩换回石头
                    if (by >= 0 && by <= 4) {
                        int storageIdx = ctx.toStorageIndex(by);

                        if (storageIdx >= 0 && storageIdx < storageArrays.length) {
                            ExtendedBlockStorage section = storageArrays[storageIdx];

                            if (section != Chunk.NULL_BLOCK_STORAGE
                                    && section.get(bx, by & 15, bz).getBlock() == Blocks.BEDROCK) {
                                section.set(bx, by & 15, bz, stone);
                            }
                        }
                    }

                    // 上游：统一分带填充（基岩底、深板岩带、过渡带、y<0 石头）
                    IBlockState state = DeepFill.bandAt(by, minY, this.depthsupdate$fillRandom, bedrock, deepslate, stone);

                    if (state == null) {
                        continue;
                    }

                    int storageIdx = ctx.toStorageIndex(by);

                    if (storageIdx < 0 || storageIdx >= storageArrays.length) {
                        continue;
                    }

                    ExtendedBlockStorage section = storageArrays[storageIdx];

                    if (section == Chunk.NULL_BLOCK_STORAGE) {
                        section = new ExtendedBlockStorage(by >> 4 << 4, hasSkyLight);
                        storageArrays[storageIdx] = section;
                    }

                    // 上游：0 以上只重染石头，不覆盖已有地形与洞穴
                    if (by >= 0 && section.get(bx, by & 15, bz).getBlock() != Blocks.STONE) {
                        continue;
                    }

                    section.set(bx, by & 15, bz, state);
                }
            }
        }

        ChunkPrimerAdapter adapter = new ChunkPrimerAdapter(chunk, ctx);

        if (DepthsUpdateConfig.heightExtension.carveOldStyleDeepCaves) {
            OldStyleDeepCaveCarver.carve(this.world, chunkX, chunkZ, adapter, ctx, 6);
        }

        // Record that this chunk has had its deep shape finalised, so that the
        // old-world conversion path in MixinAnvilChunkLoader does not run the carver
        // over it (and delete player blocks along cave paths) the first time this
        // freshly generated chunk is read back from disk.
        ((ChunkCarveMarker) (Object) chunk).depthsupdate$setCarved(true);

        if (DepthsUpdateConfig.generateUndergroundRivers) {
            if (this.depthsupdate$riverGenerator == null) {
                this.depthsupdate$riverGenerator = new UndergroundRiverGenerator(this.world);
            }
            this.depthsupdate$riverGenerator.generate(chunkX, chunkZ, adapter);
        }

        if (DepthsUpdateConfig.REGISTRY.enable118Caves) {
            if (this.depthsupdate$noiseCaveGenerator == null) {
                this.depthsupdate$noiseCaveGenerator = new CaveNoiseGenerator(this.world);
            }
            this.depthsupdate$noiseCaveGenerator.generate(chunkX, chunkZ, adapter);
        }

        if (DepthsUpdateConfig.aquifers.enableAquifers) {
            if (this.depthsupdate$aquiferGenerator == null) {
                this.depthsupdate$aquiferGenerator = new AquiferGenerator(this.world);
            }
            this.depthsupdate$aquiferGenerator.generate(chunkX, chunkZ, adapter);
        }

        chunk.generateSkylightMap();

        // ★ 深层填充刚刚【直接写】了 ExtendedBlockStorage（石头/深板岩/岩浆等），
        // 而这条路径不经过 Chunk.setBlockState，因此不会触发那条路径上的
        // "发光段候选缓存"失效。若不显式失效，填充之前算出的空候选列表会被继续复用，
        // 孤立光源（例如单格岩浆）就永远不会被播种 —— 连它自身都不发光。
        ((sayys.depthsupdate.core.ExtendedLightSeeder) (Object) chunk)
                .depthsupdate$invalidateEmitterCache();

        // ★ 深层填充到此完成 —— 必须在这里补一次「整区块深层光照播种」。
        //
        // 反汇编 ChunkProviderServer.provideChunk 的顺序是：
        //     3: loadChunk            （磁盘命中则直接返回）
        //    24: generateChunk        （新生成：只有浅层地形）
        //   132: chunk.onLoad()       ★ onLoad 在这里
        // 而本模组的深层填充挂在 provideChunk 的 RETURN 上 —— 即 onLoad【之后】。
        //
        // 于是对新生成的区块：onLoad 里的播种先跑，此时深层段还不存在，
        // extendedEmitterSections() 返回空，什么也没播；随后深层填充才创建出
        // 含岩浆的深层段，但已经没人再播种了 —— 新探索区域的深层岩浆旁边
        // 因此永远是黑的（实测 /depthsupdate scan 报出 920 处 dark air）。
        //
        // 玩家以前去过、从磁盘加载的区块反而正常，因为那条路径上深层段
        // 在 onLoad 之前就已从 NBT 恢复。
        //
        // 这里的调用是幂等的：已播过种、或深层段仍为空时会立即返回。
        ((sayys.depthsupdate.core.ExtendedLightSeeder) (Object) chunk)
                .depthsupdate$seedExtendedLight();
    }

}

