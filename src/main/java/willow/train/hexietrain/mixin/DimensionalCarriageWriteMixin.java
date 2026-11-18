package willow.train.hexietrain.mixin;

import com.simibubi.create.content.trains.entity.Carriage;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import willow.train.hexietrain.TrainBinder;

@Mixin(
   targets = {"com.simibubi.create.content.trains.entity.Carriage$DimensionalCarriageEntity"}
)
public abstract class DimensionalCarriageWriteMixin {
   @Shadow
   @Final
   Carriage this$0;

   @Inject(
      method = {"write"},
      at = {@At("HEAD")},
      cancellable = true,
      require = 0
   )
   private void hexie$skipStorageWrite(Provider registries, CallbackInfoReturnable<CompoundTag> cir) {
      try {
         if (TrainBinder.isOurCarriage(this.this$0)) {
            cir.setReturnValue(new CompoundTag());
         }
      } catch (Throwable var4) {
      }
   }
}
