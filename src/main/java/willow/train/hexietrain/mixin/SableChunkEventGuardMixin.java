package willow.train.hexietrain.mixin;

import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.IEventBus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import willow.train.hexietrain.HexieTrain;

@Mixin(
   targets = {"dev.ryanhcode.sable.neoforge.platform.SableChunkEventPlatformImpl"}
)
public abstract class SableChunkEventGuardMixin {
   @Redirect(
      method = {"onPlotChunkLoaded"},
      at = @At(
         value = "INVOKE",
         target = "Lnet/neoforged/bus/api/IEventBus;post(Lnet/neoforged/bus/api/Event;)Lnet/neoforged/bus/api/Event;"
      ),
      require = 0
   )
   private Event hexie$guardPlotChunkLoad(IEventBus bus, Event event) {
      try {
         return bus.post(event);
      } catch (Throwable var4) {
         HexieTrain.LOGGER.warn("[HexieTrain][guard] 区块加载监听器异常（已隔离）: {}", var4.toString());
         return event;
      }
   }
}
