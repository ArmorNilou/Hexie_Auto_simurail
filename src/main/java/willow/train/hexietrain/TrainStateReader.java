package willow.train.hexietrain;

import com.simibubi.create.content.trains.entity.Navigation;
import com.simibubi.create.content.trains.entity.Train;

public final class TrainStateReader {
   private TrainStateReader() {
   }

   /**
    * 读一辆虚拟车的对外状态。
    *
    * <p><b>速度绝不能再读 {@code train.speed}</b>：从 1.1.29 起虚拟车是"每 tick 拍一张物理快照"，
    * 它的 {@code speed} 被 {@code TrainSpeedInjectMixin} 恒置 0（{@code Carriage.travel} 也被拦了），
    * 读出来永远 0.000，会把"车在正常跑"谎报成"车停了"。
    * 真实速度只有一处：{@link TrainBinder#motionOf} 用物理转向架中心的逐 tick 位移算出来的。
    */
   public static TrainStateReader.State read(Train train) {
      if (train == null) {
         return new TrainStateReader.State(0.0, 0.0, false, false);
      } else {
         double speedBps = TrainBinder.motionOf(train.id).speed();
         Navigation nav = train.navigation;
         if (nav == null) {
            return new TrainStateReader.State(speedBps, 0.0, false, false);
         } else if (nav.waitingForSignal != null) {
            return new TrainStateReader.State(speedBps, nav.distanceToSignal, true, true);
         } else {
            return nav.destination != null
               ? new TrainStateReader.State(speedBps, nav.distanceToDestination, true, false)
               : new TrainStateReader.State(speedBps, 0.0, false, false);
         }
      }
   }

   public static record State(double speedBlocksPerSecond, double distance, boolean hasTarget, boolean targetIsSignal) {
   }
}
