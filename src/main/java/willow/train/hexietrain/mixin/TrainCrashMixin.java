package willow.train.hexietrain.mixin;

import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.Train;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.createmod.catnip.data.Pair;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import willow.train.hexietrain.HexieTrain;
import willow.train.hexietrain.TrainBinder;

/**
 * 我们的车不许走 Create 的「撞车」流程 —— 它会把服务器搞崩。
 *
 * <h2>崩溃链（2026-11-21 23:53:43 实测，crash-2026-11-21_23.53.43-server.txt）</h2>
 * <pre>
 * Train.tick → Train.collideWithOtherTrains → Train.crash → TrainStatus.crash
 *   → java.util.NoSuchElementException: No value present
 *     at java.util.Optional.get(Optional.java:143)
 *     at TrainStatus.lambda$crash$2(TrainStatus.java:128)
 * </pre>
 * {@code TrainStatus.crash} 要给我们车每一节的"所在维度 + 坐标"拼一条撞车播报，
 * 它对 {@code Carriage.getPositionInDimension} 的返回值直接 {@code .get()}，不判空。
 *
 * <p>而我们的车<b>没有 1.1.1 那套 contraption anchor</b>（{@code updateContraptionAnchors} 被
 * {@link CarriageAnchorMixin} 取消了，否则实体车会被甩到两千万格开外），所以那个 Optional 只能是空：
 * 早先是 {@code BlockPos.containing(null)} 的 NPE，堵掉之后变成这里的 {@code NoSuchElementException}。
 * <b>同一根死路，换了张脸。所以别在 getPositionInDimension 上继续打补丁，直接不让它走进撞车流程。</b>
 *
 * <h2>为什么可以整个取消</h2>
 * 撞车播报 / 速度归零 / 脱轨这些副作用，对一辆"用物理转向架+红石驱动"的车没有意义：
 * 真撞上了是 Sable 的物理在算，Create 这套虚拟车只是我们用来挂时刻表的影子。
 * 检测（{@code findCollidingTrain}）不受影响，仍然是 Create 自己在跑；我们只是不接它的处置。
 * 真要接管处置（减速/停车/上报），在这个 HEAD 里做，而不是让它继续往 {@code TrainStatus.crash} 走。
 */
@Mixin({Train.class})
public abstract class TrainCrashMixin {

   @Inject(
      method = {"crash"},
      at = {@At("HEAD")},
      cancellable = true,
      require = 0
   )
   private void hexie$skipCrashForOurTrains(CallbackInfo ci) {
      try {
         Train self = (Train)(Object)this;
         if (self != null && TrainBinder.OUR_TRAINS.contains(self.id)) {
            HexieTrain.LOGGER.warn("[crash] 拦下 Create 的撞车处置（车={}）——虚拟车没有 anchor，走进去必崩", self.id);
            ci.cancel();
         }
      } catch (Throwable ignored) {
      }
   }

   /** 撞车"检测"的调用次数。只为限流日志用，不是状态。 */
   private static final Map<UUID, Integer> collideSeen = new ConcurrentHashMap<>();

   /**
    * 连<b>检测</b>一起拦：{@code Train.tick → collideWithOtherTrains} 直接跳过。
    *
    * <p>1.1.26 实测两辆车各刷了 794 次（共 1588 次）—— 检测本身不算错，错的是"检测到的重叠"
    * 是虚拟车几何的产物：两辆虚拟车的端点都是从物理轴抄来的，物理上本来就挨得很近，
    * 而 Create 拿它们当两辆独立的车去比间距，必然判成撞。再往下走就是 {@code crash}。
    *
    * <p><b>不是为了把日志刷干净才拦的。</b>这条路径的语义是"你的车撞了，我替你停下/播报/归零"，
    * 而停下来这件事必须由物理和我们的调度决定，不能让一对虚构的点决定（否则就是
    * "虚拟车决定运动"，正是明令禁止的那条）。<b>但诊断价值要留住</b>：
    * 每辆车前 3 次和之后每 200 次仍然打一行，让我们知道虚拟几何还有没有重叠。
    */
   @Inject(
      method = {"collideWithOtherTrains"},
      at = {@At("HEAD")},
      cancellable = true,
      require = 0
   )
   private void hexie$skipCollisionDetection(Level level, Carriage carriage, CallbackInfo ci) {
      try {
         Train self = (Train)(Object)this;
         if (self == null || !TrainBinder.OUR_TRAINS.contains(self.id)) {
            return;
         }
         int n = collideSeen.merge(self.id, 1, Integer::sum);
         if (n <= 3 || n % 200 == 0) {
            HexieTrain.LOGGER.warn("[crash] 跳过 Create 的撞车检测（车={} 第 {} 次）", self.id, n);
         }
         ci.cancel();
      } catch (Throwable ignored) {
      }
   }

   /** 只用来限流"我们的车被别人看见了"的日志。 */
   private static volatile int hiddenFromOthers = 0;

   /**
    * <b>双向过滤的另一半：让别人的车撞不到我们的影子。</b>
    *
    * <p>上面那个注入只解决了"我们去撞别人"。反过来的那条链一直没堵：
    * <pre>
    *   别人的车 Train.tick → collideWithOtherTrains
    *     → findCollidingTrain 返回 (我们的虚拟车, 位置)
    *     → 那辆车 Train.crash(...) ⇒ 速度归零 + 判定脱轨 + 车上玩家扣血
    * </pre>
    * 我们的虚拟车只是物理车的影子，Create 里那套"撞车处置"对它没有意义；
    * 让影子把别人的车判脱轨，就是<b>影子在决定别人车的运动</b>，违反"镜像归镜像"。
    *
    * <p><b>不是崩服问题</b>（对过字节码：{@code TrainStatus.crash} 只遍历<b>它自己</b>的车厢，
    * 不会碰到我们没有 anchor 的车厢），纯粹是"我们不该让别人脱轨"。
    *
    * <p>做法是在 {@code findCollidingTrain} 的 RETURN 处把结果里的我们滤掉。
    * 别人的车拿去判碰撞的列表里从此没有我们，等于我们对 Create 的撞车检测<b>透明</b>；
    * 真实的碰撞由 Sable 的物理去算，那才是唯一说了算的地方。
    */
   @Inject(
      method = {"findCollidingTrain"},
      at = {@At("RETURN")},
      cancellable = true,
      require = 0
   )
   private void hexie$hideOurTrainsFromCollision(Level level, Vec3 leading, Vec3 trailing,
                                                 ResourceKey<Level> dimension,
                                                 CallbackInfoReturnable<Pair<Train, Vec3>> cir) {
      try {
         Pair<Train, Vec3> found = cir.getReturnValue();
         if (found == null) {
            return;
         }
         Train other = found.getFirst();
         if (other != null && other.id != null && TrainBinder.OUR_TRAINS.contains(other.id)) {
            // 前 3 次 + 之后每 200 次留证：说明"有别人的车差点撞上我们的影子"
            if (++hiddenFromOthers <= 3 || hiddenFromOthers % 200 == 0) {
               HexieTrain.LOGGER.info("[crash] 把虚拟车从别人的撞车检测里滤掉（我们的车={} 第 {} 次）", other.id, hiddenFromOthers);
            }
            cir.setReturnValue(null);
         }
      } catch (Throwable ignored) {
      }
   }
}
