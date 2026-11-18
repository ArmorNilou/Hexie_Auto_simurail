package willow.train.hexietrain.mixin;

import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import willow.train.hexietrain.TrainBinder;

@Mixin(
   targets = {"com.simibubi.create.content.trains.entity.Carriage$DimensionalCarriageEntity"}
)
public abstract class DimensionalCarriageAlignMixin {
   @Shadow
   @Final
   Carriage this$0;

   @Inject(
      method = {"alignEntity"},
      at = {@At("HEAD")},
      cancellable = true,
      require = 0
   )
   private void hexie$skipAlign(CarriageContraptionEntity entity, CallbackInfo ci) {
      try {
         if (TrainBinder.isOurCarriage(this.this$0)) {
            ci.cancel();
         }
      } catch (Throwable var4) {
      }
   }
}
