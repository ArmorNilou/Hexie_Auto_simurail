package willow.train.hexietrain;

import java.util.List;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.EventBusSubscriber.Bus;
import net.neoforged.neoforge.event.tick.ServerTickEvent.Post;

@EventBusSubscriber(
   modid = "hexie_auto_simuroute",
   bus = Bus.GAME
)
public final class DispatcherTicker {
   private DispatcherTicker() {
   }

   @SubscribeEvent
   public static void onServerTick(Post event) {
      for (TrainDispatcherBlockEntity be : List.copyOf(TrainDispatcherBlockEntity.activeDispatchers())) {
         try {
            be.serverTick();
         } catch (Throwable var4) {
            HexieTrain.LOGGER.error("[HexieTrain] 调度台 tick 异常 @{}", be.getBlockPos(), var4);
         }
      }
   }
}
