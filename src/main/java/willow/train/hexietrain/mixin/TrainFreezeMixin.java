package willow.train.hexietrain.mixin;

import com.simibubi.create.content.trains.entity.Train;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import willow.train.hexietrain.TrainDispatcherBlockEntity;

@Mixin({Train.class})
public abstract class TrainFreezeMixin {
   @Inject(
      remap = false,
      method = {"tick"},
      at = {@At("TAIL")}
   )
   private void hexie$freezeMotion(Level level, CallbackInfo ci) {
      try {
         Train self = (Train)(Object)this;
         if (TrainDispatcherBlockEntity.BY_TRAIN.get(self.id) == null) {
            return;
         }

         self.speed = 0.0;
         self.targetSpeed = 0.0;
      } catch (Throwable var4) {
      }
   }
}
