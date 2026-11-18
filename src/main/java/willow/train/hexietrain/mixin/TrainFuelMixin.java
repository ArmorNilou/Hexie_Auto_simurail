package willow.train.hexietrain.mixin;

import com.simibubi.create.content.trains.entity.Train;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import willow.train.hexietrain.TrainBinder;

@Mixin({Train.class})
public abstract class TrainFuelMixin {
   @Inject(
      method = {"burnFuel"},
      at = {@At("HEAD")},
      cancellable = true,
      require = 0
   )
   private void hexie$skipFuelBurn(CallbackInfo ci) {
      try {
         Train self = (Train)(Object)this;
         if (TrainBinder.OUR_TRAINS.contains(self.id)) {
            ci.cancel();
         }
      } catch (Throwable var3) {
      }
   }
}
