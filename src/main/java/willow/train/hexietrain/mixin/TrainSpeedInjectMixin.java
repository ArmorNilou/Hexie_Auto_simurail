package willow.train.hexietrain.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.simibubi.create.content.trains.entity.Train;

import net.minecraft.world.level.Level;
import willow.train.hexietrain.TrainDispatcherBlockEntity;

/**
 * 把物理车的真实位移注入 Create 的速度字段。
 *
 * <p>核心思路（照 CRH-HeXie 的路径）：物理位置是输入，Create 是执行者。
 * 每 tick 在 Train.tick 之前把 speed 设成"物理沿轨位移（格/tick，带符号）"，
 * 之后 Create 自己的 carriage.travel / TravellingPoint 推进 / 边点监听 / 到站判定
 * / 信号占用 全部原生运行，我们不再手搓 arriveAt。
 */
@Mixin(Train.class)
public abstract class TrainSpeedInjectMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void hexie$injectPhysicalSpeed(Level level, CallbackInfo ci) {
        try {
            Train self = (Train) (Object) this;
            TrainDispatcherBlockEntity be = TrainDispatcherBlockEntity.BY_TRAIN.get(self.id);
            if (be == null || !be.shouldInjectSpeed()) return;   // 进站解耦期间不注入，虚拟车自行驱动
            double v = be.physicalSpeedPerTick();      // 格/tick，带符号
            if (Math.abs(v) < 1.0E-4) { self.speed = 0; return; }
            self.speed = v;
            self.manualTick = true;                    // 阻止 tickPassiveSlowdown 把它吃掉
        } catch (Throwable ignored) {
        }
    }
}