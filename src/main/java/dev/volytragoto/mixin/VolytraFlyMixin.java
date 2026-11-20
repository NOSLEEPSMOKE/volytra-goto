package dev.volytragoto.mixin;

import com.volytrafly.modules.movement.volytrafly.VolytraFly;
import dev.volytragoto.modules.VolytraGoto;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * VolytraFly releases every movement key each flying tick unless the key is physically held (its own autopilot
 * off = "the player is steering"). While Volytra Goto is driving, report our forward key as held so VolytraFly
 * keeps it pressed and runs its normal speed ramp; Volytra Goto then aims that speed along the path.
 */
@Mixin(value = VolytraFly.class, remap = false)
public abstract class VolytraFlyMixin {
    @Inject(method = "isKeyPhysicallyPressed", at = @At("HEAD"), cancellable = true)
    private void volytragoto$heldByGoto(KeyMapping key, CallbackInfoReturnable<Boolean> cir) {
        if (VolytraGoto.isHoldingKey(key)) cir.setReturnValue(true);
    }
}
