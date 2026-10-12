package sayys.depthsupdate.mixin.mod.spongeforge;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import sayys.depthsupdate.core.HeightContext;
import sayys.depthsupdate.core.HeightManager;

/**
 * Fix SpongeForge relocating extended-Y coordinates inside the light accessors.
 *
 * VERIFIED DISASSEMBLY (Sponge's WorldServerMixin, members inherited onto World):
 *   public int func_175699_k(BlockPos pos):          // == World.getLight(BlockPos)
 *       if (pos.getY() < 0) return 0;                // y<0 short-circuits to 0
 *       if (pos.getY() >= 256)
 *           pos = new BlockPos(pos.getX(), 255, pos.getZ());   // y>=256 REWRITTEN to 255
 *       ...
 *   public int func_175642_b(EnumSkyBlock t, BlockPos pos):  // == World.getLightFor
 *       if (pos.getY() < 0)
 *           pos = new BlockPos(pos.getX(), 0, pos.getZ());     // y<0 REWRITTEN to 0
 *       if (!bridge$isValidPosition()) return t.defaultLightValue;
 *       ...
 *
 * Both are not passive rejections: the query is silently MOVED to a different height.
 * Asking for the light at y=260 returns the light at y=255, and asking at y=-30 returns
 * the light at y=0. That is why a light source placed in the extended range appears to
 * work at first and then the light is gone / wrong after a relog: the light data written
 * for the real height is never read back, because reads are redirected elsewhere.
 *
 * HOW THE METHODS WERE IDENTIFIED:
 * "func_175699_k" does not exist on World in the compile classpath, so the name alone is
 * unresolvable. It was matched by descriptor (BlockPos)I in the srg mapping and then
 * confirmed by comparing instruction-for-instruction against the Sponge mixin body: the
 * first instructions of World.getLight(BlockPos) are exactly
 * "pos.getY(); ifge; iconst_0; ireturn", matching Sponge's y<0 short-circuit.
 *
 * Only the extended range is handled; 0..255 is left entirely to Sponge.
 *
 * CRITICAL - WHY priority = 1100 IS REQUIRED:
 * CleanMix applies mixins in ascending priority order. With the default priority (1000)
 * this mixin was applied BEFORE Sponge's mixins.common.core.json:world.WorldMixin, and the
 * log proved it:
 *     Mixing spongeforge.MixinSpongeWorldLightHeight ...   <- mine first
 *     Mixing world.WorldMixin from mixins.common.core.json <- Sponge after
 * Sponge's WorldMixin declares getLightFor / setLightFor with FULL CONCRETE BODIES
 * (a name-match overwrite), which replaces the method wholesale and DISCARDS any @Inject
 * already placed in it. Verified against the class file:
 *     WorldMixin.func_175699_k (getLight)    -> abstract     (shadow only, @Inject survives)
 *     WorldMixin.func_175642_b (getLightFor) -> concrete body (overwrite, @Inject discarded)
 *     WorldMixin.func_175653_a (setLightFor) -> concrete body (overwrite, @Inject discarded)
 * Raising priority to 1100 makes this mixin apply AFTER Sponge's, so the injections land
 * in the final body. This is the same failure mode already documented in MixinWorld for
 * isOutsideBuildHeight; the earlier version of this file simply lacked the priority.
 */
@Mixin(value = World.class, priority = 1100)
public abstract class MixinSpongeWorldLightHeight {

    /** World.getLight(BlockPos) — Sponge's func_175699_k. */
    @Inject(method = "getLight(Lnet/minecraft/util/math/BlockPos;)I",
            at = @At("HEAD"), cancellable = true)
    private void depthsupdate$getLightExtended(BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        World self = (World) (Object) this;
        HeightContext ctx = HeightManager.get(self);

        if (!ctx.isExtended()) {
            return;
        }

        int y = pos.getY();

        // Vanilla range: let Sponge answer.
        if (y >= 0 && y < 256) {
            return;
        }

        if (y < ctx.minY() || y >= ctx.maxY()) {
            cir.setReturnValue(0);

            return;
        }

        // Same as vanilla World.getLight: max of block light and sky light, with the
        // post-sunset sky adjustment handled by getLightFor. Read via getLightFor so the
        // extended sections are consulted instead of a coordinate rewritten to 255 / 0.
        int block = self.getLightFor(EnumSkyBlock.BLOCK, pos);
        int sky = self.getLightFor(EnumSkyBlock.SKY, pos);

        cir.setReturnValue(Math.max(block, sky));
    }

    /** World.getLightFor(EnumSkyBlock, BlockPos) — Sponge's func_175642_b. */
    @Inject(method = "getLightFor", at = @At("HEAD"), cancellable = true)
    private void depthsupdate$getLightForExtended(EnumSkyBlock type, BlockPos pos,
            CallbackInfoReturnable<Integer> cir) {
        World self = (World) (Object) this;
        HeightContext ctx = HeightManager.get(self);

        if (!ctx.isExtended()) {
            return;
        }

        int y = pos.getY();

        if (y >= 0 && y < 256) {
            return;
        }

        if (y < ctx.minY() || y >= ctx.maxY()) {
            cir.setReturnValue(type == null ? 0 : type.defaultLightValue);

            return;
        }

        net.minecraft.world.chunk.Chunk chunk = self.getChunk(pos);

        if (chunk == null) {
            cir.setReturnValue(type == null ? 0 : type.defaultLightValue);

            return;
        }

        // Chunk.getLightFor already maps the world Y into the extended storage sections.
        cir.setReturnValue(chunk.getLightFor(type, pos));
    }

    /**
     * World.setLightFor(EnumSkyBlock, BlockPos, int) — Sponge's func_175653_a.
     *
     * <p>Sponge's WorldMixin declares this with a concrete body that first checks
     * {@code bridge$isValidPosition(pos)} and silently returns when it is false; for the
     * extended range that check used to fail, so <b>light writes were dropped entirely</b>.
     * That is the write-side half of "the light is there at first, then gone after a relog":
     * nothing was ever stored for the extended height.
     *
     * <p>Patching the bridge (see {@code MixinSpongeVec3iValidPosition}) already unblocks it;
     * this injection is the direct route that does not depend on the bridge at all, and it
     * also bypasses the coordinate relocation Sponge performs before the validity check.
     */
    @Inject(method = "setLightFor", at = @At("HEAD"), cancellable = true)
    private void depthsupdate$setLightForExtended(EnumSkyBlock type, BlockPos pos, int lightValue,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        World self = (World) (Object) this;
        HeightContext ctx = HeightManager.get(self);

        if (!ctx.isExtended()) {
            return;
        }

        int y = pos.getY();

        if (y >= 0 && y < 256) {
            return;
        }

        // Out of range: nothing to do, but still cancel so Sponge cannot relocate the write.
        if (y < ctx.minY() || y >= ctx.maxY()) {
            ci.cancel();

            return;
        }

        net.minecraft.world.chunk.Chunk chunk = self.getChunk(pos);

        if (chunk == null) {
            ci.cancel();

            return;
        }

        // Chunk.setLightFor maps the world Y into the extended storage sections.
        chunk.setLightFor(type, pos, lightValue);
        self.markBlockRangeForRenderUpdate(pos, pos);
        ci.cancel();
    }
}
