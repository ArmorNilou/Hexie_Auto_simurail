package willow.train.hexietrain;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 调度台全局驱动器。
 *
 * <p>只做一件事：遍历已登记的调度台并调用 serverTick。
 * 唯一化由 TrainDispatcherBlockEntity 的 TRAIN_OWNER（按列车 UUID）负责，
 * 这里不再有按坐标的去重逻辑——那套在 Sable 子结构里坐标不可靠，会互相删除实例。
 */
@EventBusSubscriber(modid = HexieTrain.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class DispatcherTicker {

    private DispatcherTicker() {}

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        for (var be : java.util.List.copyOf(TrainDispatcherBlockEntity.activeDispatchers())) {
            try {
                be.serverTick();
            } catch (Throwable t) {
                HexieTrain.LOGGER.error("[HexieTrain] 调度台 tick 异常 @{}", be.getBlockPos(), t);
            }
        }
    }
}