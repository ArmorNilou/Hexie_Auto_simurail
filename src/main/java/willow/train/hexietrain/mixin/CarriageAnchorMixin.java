package willow.train.hexietrain.mixin;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.track.TrackMaterial;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import willow.train.hexietrain.HexieTrain;
import willow.train.hexietrain.TrainBinder;

/**
 * 三件事，都在 {@link Carriage} 上，门都是 {@link TrainBinder#isOurCarriage}。
 *
 * <h2>1. cancel {@code updateContraptionAnchors()} —— 这是 1.1.0 的行为，必须保留</h2>
 * 它会按 Create 自己的 TravellingPoint 去摆那节 contraption 实体；而我们的点是
 * {@link TrainBinder} 直接写进去的 plot 坐标（两千万量级）。两套坐标系一撞，实体被甩到远处，
 * <b>那一节车厢的区块就掉了</b> ⇒ {@code rosterBogeys()} 里那几根转向架全部解析不出来
 * ⇒ 4 根只剩 1 根 ⇒ {@code searchTrainWide} 走 {@code pts.size()==1} 短路、三条硬条件一条不验
 * ⇒ 跨度 0.00 m 却报 "ok" ⇒ 导航彻底乱掉、车开出轨。
 *
 * <p><b>1.1.1 曾把它改成"放行"，代价就是上面这一串。</b>教训：它不是"顺便算个 anchor"，它是
 * Create 摆实体车的入口，不许放行。
 *
 * <h2>2. 但 anchor 永远为 null 会让 Create 崩服</h2>
 * 全类里 {@code putfield positionAnchor} 只有一处，就在被 cancel 的那个方法里 ⇒ 永远 null。
 * 一旦有任何别的车与我们在图上重叠，Create 的碰撞报告路径会
 * {@code BlockPos.containing(null)} ⇒ NPE ⇒ Exception in server tick loop（2026-11-20 与 11-21 两次）。
 *
 * <p>堵法：直接让 {@code getPositionInDimension} 对我们自己的车返回 {@code Optional.empty()}。
 * 上层 {@code Train.getPositionInDimension} 是 {@code findFirst()}，拿到空就跳过，
 * {@code BlockPos.containing} 根本不会被调用。<b>不碰 anchor 的语义，也不放行 anchor 计算。</b>
 */
@Mixin({Carriage.class})
public abstract class CarriageAnchorMixin {

   /** ① 不许 Create 摆我们的实体车（= 1.1.0 行为）。 */
   @Inject(
      method = {"updateContraptionAnchors"},
      at = {@At("HEAD")},
      cancellable = true,
      require = 0
   )
   private void hexie$skipAnchorUpdateForPhysicsCarriage(CallbackInfo ci) {
      try {
         Carriage self = (Carriage)(Object)this;
         if (TrainBinder.isOurCarriage(self)) {
            ci.cancel();
         }
      } catch (Throwable ignored) {
      }
   }

   /** ② 我们的车没有有效 anchor，直接报"这个维度里没有位置"，别让上游去解引用 null。 */
   @Inject(
      method = {"getPositionInDimension"},
      at = {@At("HEAD")},
      cancellable = true,
      require = 0
   )
   private void hexie$noPositionForPhysicsCarriage(ResourceKey<Level> dimension,
                                                   CallbackInfoReturnable<Optional<BlockPos>> cir) {
      try {
         Carriage self = (Carriage)(Object)this;
         if (TrainBinder.isOurCarriage(self)) {
            cir.setReturnValue(Optional.empty());
         }
      } catch (Throwable ignored) {
      }
   }

   /**
    * ③ <b>虚拟车点位只允许有一个写者，就是 TrainBinder.reanchor。</b>所以这里把
    * {@code Carriage.travel(...)} 整个拦掉，返回 0。
    *
    * <p><b>为什么必须拦</b>（Train.tick 字节码）：它在速度之外还会给每一节车再喂一段
    * "耦合余量" {@code bogeySpacing - getAnchorDiff()} —— 我们的车没有 contraption 实体，
    * {@code getAnchorDiff()} 取不到东西，这段余量就等于整节车长（十几米）。于是即便
    * {@code speed == 0}，Create 每 tick 仍会沿着轨道把转向架的点<b>真的往前挪十几米</b>，
    * 而且在道岔上会用 {@code follow()} 自己挑一条边 —— 挑错了，那一 tick 的占区就落到别的股道上。
    *
    * <p>拦掉之后：位置 100% 由物理轴快照决定，Create 一点都改不动。这正是
    * "镜像归镜像" —— 影子车只负责占区与时刻表，不再参与任何位移计算。
    */
   @Inject(
      method = {"travel"},
      at = {@At("HEAD")},
      cancellable = true,
      require = 0
   )
   private void hexie$blockCarriageTravel(Level level, TrackGraph graph, double distance,
                                          TravellingPoint leading, TravellingPoint trailing,
                                          int flags, CallbackInfoReturnable<Double> cir) {
      try {
         Carriage self = (Carriage)(Object)this;
         if (TrainBinder.isOurCarriage(self)) {
            if (!blockedOnce) {
               blockedOnce = true;
               // 打一次就够：这条注入 require=0（Create 换签名时不想让世界起不来），
               // 所以必须有一条运行时证据证明它真的挂上了。没有这一行就说明注入没生效，
               // 那 Create 会继续按"耦合余量"挪我们的点，占区会朝错的方向走。
               willow.train.hexietrain.HexieTrain.LOGGER.info("[HexieTrain][travel] 已拦截 Carriage.travel（虚拟车点位只由 reanchor 写）");
            }
            cir.setReturnValue(0.0);
         }
      } catch (Throwable ignored) {
      }
   }

   /** 只用来保证上面那条"注入生效"的日志只打一次。 */
   private static volatile boolean blockedOnce = false;

   /** 已经报过的轨道类型（只给日志限流：每种材质只报一次）。 */
   private static final Set<String> reportedTrackTypes = ConcurrentHashMap.newKeySet();

   /**
    * ④ <b>别让 Create 用"轨道不兼容"来管我们的虚拟车厢。</b>
    *
    * <p>{@code Train.tick} 每一拍会把所有车厢过一遍，累计
    * <pre>
    *   anyBlocked |= carriage.blocked || carriage.isOnIncompatibleTrack();
    * </pre>
    * 只要这个标志为真，它就会 {@code speed = 0}、{@code navigation.cancelNavigation()}，
    * 然后 {@code status.endOfTrack()} —— 屏幕上就是那句
    * <b>"一节车厢已到达轨道末端"</b>（{@code create.train.status.end_of_track}）。
    *
    * <p>{@code carriage.blocked} 由 {@code Carriage.travel} 设置，而我们已经把那个方法拦了
    * （见 {@link #hexie$blockCarriageTravel}），所以唯一可能的来源就是下面这个：
    * <pre>
    *   AbstractBogeyBlock.isOnIncompatibleTrack(carriage, leading):
    *       edge.getTrackMaterial().trackType != this.getTrackType(bogey.getStyle())
    * </pre>
    * —— 它拿"虚拟车端点所在轨道边的材质类型"去比"我们在 {@code bind} 里编给它的那根
    * {@code AllBlocks.SMALL_BOGEY} + {@code STANDARD} 风格所期望的类型"。
    * 而那个转向架<b>本来就是我们为了让 Create 的数据结构成型而虚构的标签</b>
    * （仿真车的真实转向架是 Simurail 的物理转向架），它<b>完全不该拿来判这列物理车能不能走</b>。
    *
    * <p>所以这里对我们自己的车厢一律返回 false，并且把素材类型打出来 ——
    * 万一以后真有人在非标准材质轨道上跑，日志里能看到是哪一种。
    * 这是"镜像归镜像"的直接后果：虚拟车厢不得反过来决定物理车的行驶状态。
    */
   @Inject(
      method = {"isOnIncompatibleTrack"},
      at = {@At("RETURN")},
      cancellable = true,
      require = 0
   )
   private void hexie$ignoreIncompatibleTrack(CallbackInfoReturnable<Boolean> cir) {
      try {
         Carriage self = (Carriage)(Object)this;
         if (!TrainBinder.isOurCarriage(self)) {
            return;
         }
         // 只在 Create 自己真的判成"不兼容"的时候才动手 —— 否则标准轨道上也会白喊一句。
         if (!Boolean.TRUE.equals(cir.getReturnValue())) {
            return;
         }
         String actual = trackTypeOf(self);
         if (reportedTrackTypes.size() < 17 && reportedTrackTypes.add(actual)) {
            // 这条对你是**正常情况**，不是报警：用户的轨道一直是非标准材质，
            // 而 StandardBogeyBlock.getTrackType(...) 是写死返回 STANDARD 的（字节码：
            // `getstatic TrackMaterial$TrackType.STANDARD; areturn`，连 style 都不看）。
            // 所以只要轨道不是标准材质，"不兼容"对我们自己的车厢就恒为真。
            // 这里按 INFO 报一次、每种材质只报一次，只用来记账。
            HexieTrain.LOGGER.info(
               "[track] 端点轨道类型“{}”≠ 虚构标签期望的“{}” ⇒ Create 会判“车厢到达轨道末端”；"
                  + "已忽略（虚拟车厢不参与行驶状态）。车头={} 车尾={}",
               actual, expectedTrackType(), describePoint(self.getLeadingPoint()), describePoint(self.getTrailingPoint()));
         }
         cir.setReturnValue(false);
      } catch (Throwable ignored) {
      }
   }

   /** 这个车厢端点所在轨道边的材质类型 id（取不到给 "?"）。 */
   private static String trackTypeOf(Carriage carriage) {
      for (TravellingPoint p : new TravellingPoint[]{carriage.getLeadingPoint(), carriage.getTrailingPoint()}) {
         try {
            if (p == null || p.edge == null) {
               continue;
            }
            TrackMaterial material = p.edge.getTrackMaterial();
            if (material != null && material.trackType != null && material.trackType.id != null) {
               return material.trackType.id.toString();
            }
         } catch (Throwable ignored) {
         }
      }
      return "?";
   }

   /** 我们在 bind 里编给虚拟车厢的那根转向架所期望的轨道类型。 */
   private static String expectedTrackType() {
      try {
         return TrackMaterial.TrackType.STANDARD.id.toString();
      } catch (Throwable t) {
         return "standard";
      }
   }

   private static String describePoint(TravellingPoint p) {
      try {
         if (p == null || p.edge == null) {
            return "无轨";
         }
         TrackEdge edge = p.edge;
         return edge.node1.getLocation().getLocation() + "@" + String.format("%.1f/%.1f", p.position, edge.getLength());
      } catch (Throwable t) {
         return "?";
      }
   }
}
