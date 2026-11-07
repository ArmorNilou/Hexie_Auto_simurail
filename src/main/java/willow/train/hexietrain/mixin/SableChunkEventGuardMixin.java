package willow.train.hexietrain.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.IEventBus;
import willow.train.hexietrain.HexieTrain;

/**
 * 崩溃护栏（不是功能）：Sable 在 plot 区块加载时 post ChunkEvent.Load，
 * 若某监听器（实测 DistantHorizons 的 Chunky 兼容层在未装 Chunky 时）抛异常，
 * 会直接打崩服务端 tick 循环 / 世界加载。这里只包一层 try/catch，记录后继续。
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
            HexieTrain.LOGGER.warn("[HexieTrain][guard] 区块加载监听器异常（已隔离）: {}", t.toString());
            return event;
        }
    }
}