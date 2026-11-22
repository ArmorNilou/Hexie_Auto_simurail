package willow.train.hexietrain.mixin;

import com.simibubi.create.content.trains.entity.Train;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import willow.train.hexietrain.TrainDispatcherBlockEntity;

/**
 * 只做两件事：清 Create 遗留的 destination、把速度恒置 0（位置由快照写，不靠积分）。
 *
 * <p><b>绝不再写 train.manualSteer。</b> 转向是物理的：调度器的 LEFT / RIGHT 两个红石输出
 * 接到转向架的左右，通电就往那一侧的道岔开，不通电默认直行。用代码去写 manualSteer 等于
 * 绕过物理机构再让 Create 自己选一次边，两边一冲突，Create 的行走点和物理转向架的去向就脱节。
 */
/**
 * 虚拟车的速度<b>恒为 0</b>，而且是这里<b>唯一</b>写它的地方（1.1.30 把原 TrainFreezeMixin 合并进来）。
 *
 * <p><b>为什么速度必须是 0 而不是"和物理车相等"</b>：位置由
 * {@link willow.train.hexietrain.TrainBinder#reanchor} 每 tick 从物理轴抄快照，
 * 位移根本不经过 {@code speed} 积分；而 {@code Carriage.travel} 已被
 * {@link CarriageAnchorMixin#hexie$blockCarriageTravel} 整个拦掉。所以这里的 0
 * 是为了让 Create 自己的状态机（失速判定、应力、被动减速）也认为"这车不在自己跑"，
 * 免得它二次插一手。<b>不是</b>一个"近似值"，它不参与任何几何。
 *
 * <p><b>1.1.9~1.1.28 的错误路线</b>：曾经把物理速度注入 {@code self.speed}，让 Create 去积分位置。
 * 那就必须保证"虚拟速度与物理速度绝对相等"——而这不可能：快了虚拟车跑到物理车前面
 * （占区超前，锁住物理车自己），慢了落在后面（该占的区间提前放掉，后车一头撞进来）。
 * 用户明确指出这是严重问题，于是 1.1.29 起改为"快照"，误差<b>结构上恒为 0</b>。
 *
 * <p><b>绝不再写 train.manualSteer。</b> 转向是物理的：调度器的 LEFT / RIGHT 两个红石输出
 * 接到转向架的左右，通电就往那一侧的道岔开，不通电默认直行。用代码去写 manualSteer 等于
 * 绕过物理机构再让 Create 自己选一次边，两边一冲突，Create 的行走点和物理转向架的去向就脱节。
 */
@Mixin({Train.class})
public abstract class TrainSpeedInjectMixin {

   /** ① 进 tick 之前：清掉 Create 自己设回来的 destination，并把速度压回 0。 */
   @Inject(
      method = {"tick"},
      at = {@At("HEAD")}
   )
   private void hexie$beforeTick(Level level, CallbackInfo ci) {
      try {
         Train self = (Train)(Object)this;
         TrainDispatcherBlockEntity be = TrainDispatcherBlockEntity.BY_TRAIN.get(self.id);
         if (be == null || !be.shouldInjectSpeed()) {
            return;
         }

         // Create 会自己把 navigation.destination 设回来（它的 ScheduleRuntime 在跑）。
         // 留着它，Train.tick 里 "if (destination != null) return;" 会让等 N 秒这类条件
         // 几乎不涨（实测 5 秒变 24 秒）。这里每 tick 清，driveTick 里那份是补漏。
         if (self.navigation != null && self.navigation.destination != null) {
            self.navigation.destination = null;
            self.navigation.destinationBehindTrain = false;
            be.noteNavCleared();
         }

         self.speed = 0.0;
         self.manualTick = true;
      } catch (Throwable var7) {
      }
   }

   /**
    * ② 出 tick 之后：再压一次。tick 中途 {@code Navigation.tick} / {@code ScheduleRuntime.tick}
    * 会把 speed 写成非 0，若不压回去，下一 tick 的 {@code Train.tick} 会拿这个值去过
    * 失速/脱轨分支 —— 位置虽然改不动，但状态机会乱，日志会骗人。
    */
   @Inject(
      method = {"tick"},
      at = {@At("TAIL")}
   )
   private void hexie$afterTick(Level level, CallbackInfo ci) {
      try {
         Train self = (Train)(Object)this;
         if (TrainDispatcherBlockEntity.BY_TRAIN.get(self.id) == null) {
            return;
         }
         self.speed = 0.0;
         self.targetSpeed = 0.0;
      } catch (Throwable var4) {
      }
   }
}