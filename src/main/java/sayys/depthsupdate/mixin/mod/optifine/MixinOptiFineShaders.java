package sayys.depthsupdate.mixin.mod.optifine;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.client.Minecraft;
import net.minecraft.world.World;
import sayys.depthsupdate.core.HeightContext;
import sayys.depthsupdate.core.HeightManager;

/**
 * Fixes the "void sky" that OptiFine shaders render when the camera is at negative Y.
 *
 * <p>Root cause: vanilla (and OptiFine) decide "camera is below the world -> draw void" by
 * comparing the camera Y against the hardcoded overworld horizon (Y=0). This mod extends the
 * overworld down to {@code minY} (e.g. -64), so the camera legitimately sits at negative Y and
 * the shaderpack's GLSL reads a negative {@code cameraPosition.y} uniform, concludes the camera
 * has dropped out of the world, and paints the void instead of the normal sky dome.
 *
 * <p>The vanilla fixed-function sky is already patched in {@code MixinRenderGlobal} /
 * {@code MixinEntityRenderer}, but those patches do not reach the shader path: OptiFine writes
 * the {@code cameraPositionX/Y/Z} doubles from its own code every frame. This mixin intercepts
 * the write to {@code cameraPositionY} and lifts it by {@code -minY}, so the shader always sees
 * a non-negative world-space Y and never trips its "below world bottom" branch.
 *
 * <p>Verified against the actual G5 SRG jar ({@code net.optifine.shaders.Shaders}): the
 * per-frame camera update writes {@code cameraPositionY = interpY} (X/Z additionally subtract
 * a 1000-block {@code cameraOffset}; Y does not). That write happens in two places -
 * {@code setCamera(float)} for the main pass and {@code setCameraShadow(float)} for the shadow
 * pass - so both are targeted. Geometry is untouched (the OpenGL modelview is still driven by
 * the real EntityRenderer transform); only the GLSL-visible world-space Y is remapped, like
 * vanilla 1.18+'s min-Y offset.
 *
 * <p>{@code require = 0}: if a future OptiFine build renames this method, the mixin no-ops
 * instead of crashing; the void sky returns and can be re-diagnosed by running
 * {@code javap -p -c net/optifine/shaders/Shaders.class | grep cameraPositionY}.
 */
@Mixin(targets = "net.optifine.shaders.Shaders", remap = false)
public class MixinOptiFineShaders {

    @WrapOperation(
        method = {"setCamera", "setCameraShadow"},
        at = @At(
            value = "FIELD",
            target = "Lnet/optifine/shaders/Shaders;cameraPositionY:D",
            opcode = Opcodes.PUTSTATIC
        ),
        require = 0
    )
    private static void depthsupdate$wrapCameraPositionY(double value, Operation<Void> original) {
        World world = Minecraft.getMinecraft().world;
        if (world != null) {
            HeightContext ctx = HeightManager.get(world);
            if (ctx.isExtended()) {
                // Lift the world so its bottom sits at Y=0 from the shader's point of view.
                value -= ctx.minY();
            }
        }
        original.call(value);
    }
}
