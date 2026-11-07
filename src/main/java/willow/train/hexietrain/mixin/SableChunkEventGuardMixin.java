package willow.train.hexietrain.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.IEventBus;
import willow.train.hexietrain.HexieTrain;

/**
 * 保护 Sable 的子结构区块加载事件广播。
 *
 * <p>背景：Sable 在 plot 区块加载时会 post 一个 ChunkEvent.Load，
 * 若某个监听器（实测是 DistantHorizons 的 Chunky 兼容层在没装 Chunky 时）
 * 抛异常，整个服务端 tick 循环会被打崩。
 *
 * <p>这里只包一层 try/catch：异常只记录，不再向上传。
 */
@Mixin(targets = "dev.ryanhcode.sable.neoforge.platform.SableChunkEventPlatformImpl")
public abstract class SableChunkEventGuardMixin {

    @Redirect(
        method = "onPlotChunkLoaded",
        at = @At(value = "INVOKE",
                 target = "Lnet/neoforged/bus/api/IEventBus;post(Lnet/neoforged/bus/api/Event;)Lnet/neoforged/bus/api/Event;"),
        require = 0
    )
    private Event hexie$guardPlotChunkLoad(IEventBus bus, Event event) {
        try {
            return bus.post(event);
        } catch (Throwable t) {
            HexieTrain.LOGGER.warn("[HexieTrain][guard] 子结构区块加载事件监听器异常（已隔离）: {}", t.toString());
            return event;
        }
    }
}