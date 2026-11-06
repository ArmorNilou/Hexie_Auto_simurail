package willow.train.hexietrain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 调度台全局驱动器。
 *
 * <p>去重有明确优先级（低→高）：
 * <ol>
 *   <li>已移除 / 没有 level / 客户端实例 —— 直接剔除</li>
 *   <li>活着但没有状态（无时刻表、无绑定、无列车）</li>
 *   <li>活着且持有状态（有时刻表/持久列车 UUID/已绑定列车）</li>
 * </ol>
 * 同坐标只保留优先级最高的那个，并且**一定要 tick 保留下来的那个**。
 */
@EventBusSubscriber(modid = HexieTrain.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class DispatcherTicker {

    private DispatcherTicker() {}

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        var active = TrainDispatcherBlockEntity.activeDispatchers();
        List<TrainDispatcherBlockEntity> snapshot = new ArrayList<>(active);

        // ① 先剔除无效实例
        for (var be : snapshot) {
            if (be.isRemoved() || be.getLevel() == null || be.getLevel().isClientSide)
                active.remove(be);
        }

        // ② 按坐标分组，保留优先级最高的
        Map<Long, TrainDispatcherBlockEntity> keeper = new HashMap<>();
        for (var be : snapshot) {
            if (!active.contains(be)) continue;
            long key = ((long) be.getLevel().dimension().location().hashCode() << 32)
                    ^ be.getBlockPos().asLong();
            var prev = keeper.get(key);
            if (prev == null) {
                keeper.put(key, be);
            } else if (priority(be) > priority(prev)) {
                active.remove(prev);
                keeper.put(key, be);
            } else {
                active.remove(be);
            }
        }

        // ③ 保留下来的实例必须 tick
        for (var be : keeper.values()) {
            try {
                be.serverTick();
            } catch (Throwable t) {
                HexieTrain.LOGGER.error("[HexieTrain] 调度台 tick 异常 @{}", be.getBlockPos(), t);
            }
        }
    }

    /** 越大越优先保留。 */
    private static int priority(TrainDispatcherBlockEntity be) {
        int p = 0;
        if (!be.scheduleSlot().isEmpty()) p += 4;          // 有时刻表
        if (be.boundTrain() != null) p += 2;               // 已绑定列车
        if (be.persistentTrainId() != null) p += 1;        // 已持久化 UUID
        return p;
    }
}