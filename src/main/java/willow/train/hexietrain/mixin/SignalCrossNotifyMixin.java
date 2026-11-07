package willow.train.hexietrain.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.entity.TravellingPoint;

import willow.train.hexietrain.TrainDispatcherBlockEntity;

/**
 * 事件化的"过信号机/过边点"通知。
 *
 * <p>Create 的 IEdgePointListener 不是字段，而是每次 travel() 由
 * frontSignalListener() 现生成并传入。这里在返回处包一层：
 * 只有当列车真的跨过一个边点（含信号机）时，才回调调度器一次，
 * 替代"每 tick 轮询 distanceToSignal"。
 */
@Mixin(Train.class)
public abstract class SignalCrossNotifyMixin {

    @Inject(method = "frontSignalListener", at = @At("RETURN"), cancellable = true, require = 0)
    private void hexie$wrapEdgeListener(CallbackInfoReturnable<TravellingPoint.IEdgePointListener> cir) {
        TravellingPoint.IEdgePointListener original = cir.getReturnValue();
        if (original == null) return;
        Train self = (Train) (Object) this;
        TravellingPoint.IEdgePointListener wrapped = (distance, edgePoint) -> {
            boolean handled = original.test(distance, edgePoint);
            try {
                TrainDispatcherBlockEntity be = TrainDispatcherBlockEntity.BY_TRAIN.get(self.id);
                if (be != null) be.onEdgePointCrossed();
            } catch (Throwable ignored) {}
            return handled;
        };
        cir.setReturnValue(wrapped);
    }
}