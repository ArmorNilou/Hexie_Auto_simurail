package willow.train.hexietrain;

/**
 * 两个**输出**通道（都是 Create Redstone Link 无线频率，见 TrainDispatcherBlockEntity.setOutput）。
 *
 * <p><b>道岔转向已经不在这里了。</b> 逐转向架转向由 {@link BogeySteering} 通过 Simurail 的遥控接口
 * 直接写给转向架自己（setRemoteLeftSteerOverride / setRemoteRightSteerOverride），
 * 不经过任何红石、也不经过本类的任何通道。
 *
 * <p>原来还有 LEFT / RIGHT 两个"道岔红石输出"通道，和遥控路径并存 = 两套实现抢同一个转向架；
 * 而且 Simurail 的 getSteerValue() 里"本地红石非 0 就短路"，红石一旦通电就永远读不到遥控值。
 * 已经整套删掉（0.62.0）。
 */
public enum SignalChannel {
   BRAKE(0),
   THROTTLE(1);

   public static final SignalChannel[] VALUES = values();
   private final int index;
   public static final double BRAKE_HORIZON = 10.0;

   private SignalChannel(int index) {
      this.index = index;
   }

   public int index() {
      return this.index;
   }

   public static int encodeBrake(double strength, boolean linearize) {
      double s = clamp01(strength);
      double v = linearize ? Math.sqrt(s) : s;
      return (int)Math.round(v * 15.0);
   }

   private static double clamp01(double v) {
      return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v);
   }

   public static int brakeStrength(double distance, double speed, boolean linearize) {
      if (distance <= 0.0) {
         return 15;
      } else {
         double s = 1.0 - clamp01(distance / 10.0);
         if (speed <= 1.0E-4 && distance >= 1.0) {
            return 0;
         } else {
            double v = linearize ? Math.sqrt(s) : s;
            return (int)Math.round(v * 15.0);
         }
      }
   }

   public static int reverseStage(double distance, double speed, int brakeStrength) {
      if (!(speed <= 0.02) && !(distance <= 0.01)) {
         double needed = speed * speed / (2.0 * distance);
         double frictionCap = 0.004 * (double)Math.max(1, brakeStrength);
         if (needed <= frictionCap) {
            return 0;
         } else {
            return needed <= frictionCap * 2.0 ? 1 : 2;
         }
      } else {
         return 0;
      }
   }
}