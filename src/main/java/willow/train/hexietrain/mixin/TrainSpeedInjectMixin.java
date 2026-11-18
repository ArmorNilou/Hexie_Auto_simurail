package willow.train.hexietrain.mixin;

import com.simibubi.create.content.trains.entity.Train;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import willow.train.hexietrain.TrainDispatcherBlockEntity;

/**
 * 只做三件事：清 Create 遗留的 destination、把物理速度注入 Train、置 manualTick。
 *
 * <p><b>绝不再写 train.manualSteer。</b> 转向是物理的：调度器的 LEFT / RIGHT 两个红石输出
 * 接到转向架的左右，通电就往那一侧的道岔开，不通电默认直行。用代码去写 manualSteer 等于
 * 绕过物理机构再让 Create 自己选一次边，两边一冲突，Create 的行走点和物理转向架的去向就脱节。
 */
@Mixin({Train.class})
public abstract class TrainSpeedInjectMixin {
   @Inject(
      method = {"tick"},
      at = {@At("HEAD")}
   )
   private void hexie$injectPhysicalSpeed(Level level, CallbackInfo ci) {
      try {
         Train self = (Train)(Object)this;
         TrainDispatcherBlockEntity be = TrainDispatcherBlockEntity.BY_TRAIN.get(self.id);
         if (be == null || !be.shouldInjectSpeed()) {
            return;
         }

         // ① Create 会自己把 navigation.destination 设回来（它的 ScheduleRuntime 在跑）。
         //    留着它，Train.tick 里 "if (destination != null) return;" 会让等 N 秒这类条件
         //    几乎不涨（实测 5 秒变 24 秒）。这里每 tick 清，driveTick 里那份是补漏。
         if (self.navigation != null && self.navigation.destination != null) {
            self.navigation.destination = null;
            self.navigation.destinationBehindTrain = false;
            be.noteNavCleared();
         }

         // ② 物理速度注入：车辆的真实速度由 Simurail 的物理转向架决定，我们只做镜像
         double v = be.physicalSpeedPerTick();
         if (Math.abs(v) < 1.0E-4) {
            self.speed = 0.0;
            return;
         }

         self.speed = v;
         self.manualTick = true;
      } catch (Throwable var7) {
      }
   }
}