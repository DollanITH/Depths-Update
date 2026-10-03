package sayys.depthsupdate;

import java.util.List;
import java.util.Set;

import net.minecraft.launchwrapper.Launch;
import net.minecraftforge.fml.common.Loader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * 合并版 ModMixinConfigPlugin（fork 门控集合 + 上游检测机制）
 * Minecraft版本: 1.12.2
 * 模组: Depths Update
 * <p>
 * 门控集合（fork）：
 * - optifine / nothirium / celeritas / minihud：按类路径探测
 * - worldedit / extrautils2 / journeymap：按 Forge 模组加载状态（MOD 阶段，modlist 已构建）
 * <p>
 * 检测机制（上游）：
 * - 改用 Launch.classLoader.isClassExist(...)，避免 Class.forName 触发类的静态初始化，
 *   也不依赖类加载时机（上游针对 Nothirium 检测 OptiFine 环境问题所作的修复）。
 */
public class ModMixinConfigPlugin implements IMixinConfigPlugin {
    @Override
    public void onLoad(String s) {}

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    /**
     * An example of mod mixin
     * The {@link org.spongepowered.asm.mixin.MixinEnvironment.Phase#MOD} allow the
     * mixins being processed after modlist building
     * Which allow calling {@link Loader#isModLoaded(String)}
     *
     * @param targetClassName Not important unless you are writing multi-target
     *                        mixin
     * @param mixinClassName  The full mixin class name. Filtering with group name
     *                        is the easiest solution here.
     * @return If the mixin should apply
     */
    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith(".MixinRenderGlobalChunkOffset")) {
            return !Launch.classLoader.isClassExist("optifine.OptiFineForgeTweaker");
        }

        if (mixinClassName.contains(".optifine.")) {
            return Launch.classLoader.isClassExist("optifine.OptiFineForgeTweaker");
        }

        if (mixinClassName.contains(".mod.nothirium.")) {
            return Launch.classLoader.isClassExist("meldexun.nothirium.mc.Nothirium");
        }

        if (mixinClassName.contains(".mod.celeritas.")) {
            return Launch.classLoader.isClassExist("org.taumc.celeritas.CeleritasVintage");
        }

        if (mixinClassName.contains(".mod.rltweaker.")) {
            return Launch.classLoader.isClassExist("com.charles445.rltweaker.RLTweaker");
        }

        if (mixinClassName.contains(".mod.minihud.")) {
            return Launch.classLoader.isClassExist("fi.dy.masa.minihud.MiniHud");
        }

        if (mixinClassName.contains(".mod.spongeforge.")) {
            // 只用类存在性判断：MixinSpongeWorldApi target 的 net.minecraft.world.World 是核心类，
            // 可能在 FML mod 列表构建完成前就被 mixin 转换（此时 Loader.isModLoaded 内部
            // Loader.namedMods 为 null，直接调用会 NPE 导致启动崩溃）。spongeforge 作为
            // coremod 早期即在 classpath，其 ChunkPrimerBuffer 类存在即表示已安装。
            return Launch.classLoader.isClassExist("org.spongepowered.common.util.gen.ChunkPrimerBuffer");
        }

        if (mixinClassName.contains(".mod.worldedit.")) {
            return Loader.isModLoaded("worldedit");
        }

        if (mixinClassName.contains(".mod.extrautils2.")) {
            return Loader.isModLoaded("extrautils2");
        }

        if (mixinClassName.contains(".mod.journeymap.")) {
            return Loader.isModLoaded("journeymap");
        }

        return true;
    }

    @Override
    public void acceptTargets(Set<String> set, Set<String> set1) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String s, ClassNode classNode, String s1, IMixinInfo iMixinInfo) {}

    @Override
    public void postApply(String s, ClassNode classNode, String s1, IMixinInfo iMixinInfo) {}
}
