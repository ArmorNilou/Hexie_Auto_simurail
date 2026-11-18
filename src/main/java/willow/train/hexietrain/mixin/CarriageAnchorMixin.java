package willow.train.hexietrain.mixin;

import com.simibubi.create.content.trains.entity.Carriage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import willow.train.hexietrain.TrainBinder;

@Mixin({Carriage.class})
public abstract class CarriageAnchorMixin {
   @Inject(
      method = {"updateContraptionAnchors"},
      at = {@At("HEAD")},
      cancellable = true,
      require = 0
   )
   private void hexie$skipAnchorUpdateForPhysicsCarriage(CallbackInfo ci) {
      try {
         Carriage self = (Carriage)(Object)this;
         if (TrainBinder.isOurCarriage(self)) {
            ci.cancel();
         }
      } catch (Throwable var3) {
      }
   }
}
