package willow.train.hexietrain;

import com.crystaelix.simurail.api.math.SimurailMath;
import com.crystaelix.simurail.content.bogey.PhysicsBogeyAxle;
import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.graph.TrackEdge;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * 逐转向架的道岔子系统。
 *
 * <p>导航状态下，每个<b>已绑定</b>的转向架每 tick 自己算一次"我要不要转向、往哪边"，
 * 然后通过 Simurail 的<b>遥控接口</b>把结果写给自己那一个转向架：
 *
 * <pre>
 *   setRemoteLeftSteerOverride (anchor, 15)  ⇒ steerValue = −1 ⇒ 走【行进左】
 *   setRemoteRightSteerOverride(anchor, 15)  ⇒ steerValue = +1 ⇒ 走【行进右】
 *   两个都 0                                  ⇒ steerValue =  0 ⇒ 直行
 * </pre>
 *
 * <h2>判据就是他们自己的那一行代码（0.1.3 / 0.1.4）</h2>
 * {@code PhysicsBogeyAxle.steer()}（当前上游源码 1032 行起）：
 * <pre>
 *   Vector3dc trackLat = trackFrame.lateral;
 *   turn = cachedControlPoints(edge.getTurn()).curvature(0, turnCurvature).dot(trackLat);
 *   edge.isTurn() == false                → straightTarget
 *   |turn| &lt; SimurailMath.EPSILON         → straightTarget
 *   turn &lt; 0                            → leftTargets
 *   turn &gt; 0                            → rightTargets
 *
 *   steerValue = bogey.getGroupSteerValue();
 *   straightTarget != null &amp;&amp; (steerValue == 0
 *       || steerValue &lt; 0 &amp;&amp; leftTargets.isEmpty()
 *       || steerValue &gt; 0 &amp;&amp; rightTargets.isEmpty())   → 直行
 *   !leftTargets.isEmpty() &amp;&amp; steerValue &lt;= 0            → 左组
 *   否则                                                  → 右组
 * </pre>
 *
 * <p><b>0.1.2 起（提交 2cb847b「Change turn control to be based on track curvature」）
 * 判据换成了上面这套"按曲率分组"的离散判据</b>；0.1.0 是
 * {@code wish = 行进方向 + steerValue × 横向} 再去和每条候选边的弦求点积。
 * 两套的左右语义一致，但离散判据里 <b>0 与 ±1 是三件不同的事</b>，中间值毫无意义，
 * 而且分组符号完全由 {@code trackFrame.lateral} 决定。
 *
 * <p>所以本类算 {@code turn} 时<b>必须用转向架自己的 {@code getTrackFrame().lateral()}，
 * 不许自己拿"行进方向 × 上"反推</b>：那个向量是他们用 {@code trackAxleFrame.direction}
 * 翻正过的（源码 413-419 行），跟自己拼出来的世界系右手向量不保证同向。
 * 一旦反了，现象就是"该进岔时走直、该直行时进岔"。同一行代码、同一个向量，
 * 就不存在"我算的和它想的不一样"。
 *
 * <h2>什么时候写</h2>
 * 值只在<b>节点</b>上被读一次，所以整条腿（leg）都挂着不会有害：本车路线里凡是出现
 * 多个候选边的节点，必然就是某条腿的终点，也就是我们本来就要决定方向的岔口。
 * 早挂的价值是彻底去掉"到岔口前 N 米才上值"的竞态 —— 物理车很快，晚一拍就是整列车走错股道。
 *
 * <h2>安全不变量</h2>
 * 本类只允许在 {@code level.hasChunkAt(pos)} 通过的区块上调 {@code getBlockEntity}，
 * 绝不触发区块加载（同 {@link BogeyBinding} 的死锁教训）。
 */
public final class BogeySteering {

   /** 跨过节点之后再保持几 tick（防止在节点上抖动）。 */
   private static final int HOLD_TICKS = 3;

   private final Map<BlockPos, State> states = new HashMap<>();

   /** 曲线判据读不到 lateral 时只警告一次，别刷屏。 */
   private boolean warnedNoFrame = false;

   private static final class State {
      /** −1 左 / +1 右 / 0 不给 */
      int side = 0;
      /** 已经真正写进转向架的值，避免每 tick 重复写同一个数 */
      int written = 0;
      int hold = 0;
      TrackEdge lastEdge = null;
      /** 上一次判定的目标边摘要，只给日志用 */
      String note = "-";
      /** "写了却没生效"只警告一次（下一次生效时复位） */
      boolean warnedNotApplied = false;
   }

   // ================================================================ 对外

   /** 导航中：每个绑定转向架各自判定。 */
   public void tick(ServerLevel level, BlockPos anchor, BogeyRoster roster, RailCache.Route route) {
      for (BlockPos pos : roster.positions()) {
         try {
            tickOne(level, anchor, pos, route);
         } catch (Throwable t) {
            HexieTrain.LOGGER.warn("[steer] {} 判定异常 {}", pos.toShortString(), t.toString());
         }
      }
   }

   /** 不在导航 / 手动模式：全部放回直行。 */
   public void releaseAll(ServerLevel level, BlockPos anchor, BogeyRoster roster) {
      for (BlockPos pos : roster.positions()) {
         State st = this.states.computeIfAbsent(pos, k -> new State());
         st.side = 0;
         st.hold = 0;
         write(level, anchor, pos, st);
      }
   }

   // ================================================================ 单个转向架

   private void tickOne(ServerLevel level, BlockPos anchor, BlockPos pos, RailCache.Route route) {
      State st = this.states.computeIfAbsent(pos, k -> new State());
      if (!level.hasChunkAt(pos)) {
         return;   // 区块没加载：保持现状，不猜
      }

      BlockEntity be = level.getBlockEntity(pos);
      if (!(be instanceof PhysicsBogeyBlockEntity bogey) || bogey.isRemoved()) {
         st.side = 0;
         st.hold = 0;
         this.states.remove(pos);
         return;
      }

      PhysicsBogeyAxle axle = bogey.getAxle(true);
      TravellingPoint tp = axle == null ? null : axle.getTrackPoint();
      if (tp == null || tp.edge == null || route == null) {
         release(st, level, anchor, pos);
         return;
      }

      // 跨过节点 = 这个轴的 edge 变了。变了就进入"保持"窗口
      if (st.lastEdge != null && !RailCache.sameLink(st.lastEdge, tp.edge)) {
         st.hold = st.side == 0 ? 0 : HOLD_TICKS;
      }
      st.lastEdge = tp.edge;

      if (st.hold > 0) {
         st.hold--;
         write(level, anchor, pos, st);
         return;
      }

      TrackEdge next = route.nextEdgeAfter(tp.edge);
      if (next == null) {
         release(st, level, anchor, pos);
         return;
      }

      // 曲线判据要的是转向架自己的 lateral；拿不到就放直行（不猜），并把原因打出来
      Vector3dc lateral = lateralOf(axle, pos);
      if (lateral == null) {
         release(st, level, anchor, pos);
         return;
      }

      st.side = sideOf(next, lateral, pos);
      st.note = (next.isTurn() ? "弯" : "直") + String.format(" %.2fm", next.getLength());
      write(level, anchor, pos, st);
   }

   private void release(State st, ServerLevel level, BlockPos anchor, BlockPos pos) {
      st.side = 0;
      st.hold = 0;
      write(level, anchor, pos, st);
   }

   /**
    * 锚点用调度器自己的位置，一个调度器一个来源。
    *
    * <p><b>非 0 必须每 tick 重写一次。</b> Simurail 的
    * {@code getRemoteSteerValue()} 里写着：
    * <pre>
    *   if(currentTime - pair.leftLong() &gt; 2) { i.remove(); }
    * </pre>
    * 也就是说遥控值写进去之后<b>只活 3 tick</b>，没人续期就自动消失。
    * 只在"变化时"写一次的话，第 4 tick 起 steerValue 回到 0，
    * trackJoint 立刻把轴拽回直行段 —— 现象就是"过岔时抽一下然后弹回直行"。
    *
    * <p>值为 0 时不能这么干：{@code setRemoteLeftSteerOverride(pos, 0)} 是<b>删条目</b>，
    * 重复写等于每 tick 清一次别人的条目，没意义也不安全，
    * 所以 0 只在从非 0 退回来时写一次。
    *
    * <p>写完立刻读回 {@code getGroupSteerValue()}：Simurail 的 {@code getSteerValue()} 里
    * "本地红石非 0 就短路、根本不看遥控值"，所以只要转向架左右两面还接着旧版本的红石线，
    * 我们写什么都白写 —— 读回来对不上就点名报出来。
    */
   private void write(ServerLevel level, BlockPos anchor, BlockPos pos, State st) {
      if (st.side == 0 && st.written == 0) {
         return;   // 本来就直行、也已经清过了：不用再碰转向架
      }
      boolean changed = st.written != st.side;
      st.written = st.side;
      if (!level.hasChunkAt(pos)) {
         return;
      }
      if (!(level.getBlockEntity(pos) instanceof PhysicsBogeyBlockEntity bogey)) {
         return;
      }
      try {
         bogey.setRemoteLeftSteerOverride(anchor, st.side < 0 ? 15 : 0);
         bogey.setRemoteRightSteerOverride(anchor, st.side > 0 ? 15 : 0);
         if (!changed) {
            return;
         }
         float got = bogey.getGroupSteerValue();
         boolean applied = Math.abs(got - (float)st.side) < 0.5F;
         if (!applied && st.side != 0 && !st.warnedNotApplied) {
            st.warnedNotApplied = true;
            HexieTrain.LOGGER.warn(
               "[steer] {} 写了遥控={} 但转向架读到={} —— Simurail 里本地红石非 0 就直接短路遥控值，"
                  + "多半是转向架左右两面还压着旧版本的红石线/接收器，请拔掉",
               pos.toShortString(), st.side, got
            );
         } else if (applied) {
            st.warnedNotApplied = false;
         }
         HexieTrain.LOGGER.info(
            "[steer] {} {} 目标边={} 读到={}",
            pos.toShortString(),
            st.side == 0 ? "放直行" : (st.side > 0 ? "打【行进右】" : "打【行进左】"),
            st.note,
            got
         );
      } catch (Throwable t) {
         HexieTrain.LOGGER.warn("[steer] {} 写遥控失败 {}", pos.toShortString(), t.toString());
      }
   }

   // ================================================================ 判据

   /** 转向架自己的"行进右"向量。拿不到就返回 null（调用方放直行，不猜）。 */
   private Vector3dc lateralOf(PhysicsBogeyAxle axle, BlockPos pos) {
      try {
         var frame = axle.getTrackFrame();
         if (frame == null) {
            return null;
         }
         Vector3dc lateral = frame.lateral();
         if (lateral == null || lateral.lengthSquared() < 1.0E-8) {
            return null;
         }
         return lateral;
      } catch (Throwable t) {
         if (!this.warnedNoFrame) {
            this.warnedNoFrame = true;
            HexieTrain.LOGGER.warn("[steer] {} 读不到转向架 trackFrame.lateral：{}", pos.toShortString(), t.toString());
         }
         return null;
      }
   }

   /**
    * 目标边往哪边弯。<b>照抄 Simurail 的 {@code steer()}：同一个公式、同一条向量。</b>
    * 直线边 = 直行；{@code curvature(0) · lateral} 的符号定左右。
    *
    * @return −1 左 / +1 右 / 0 直行
    */
   private static int sideOf(TrackEdge want, Vector3dc lateral, BlockPos pos) {
      if (want == null || !want.isTurn()) {
         return 0;
      }
      double turn;
      try {
         Vector3d curv = new Vector3d();
         SimurailMath.cachedControlPoints(want.getTurn()).curvature(0.0, curv);
         turn = curv.dot(lateral);
      } catch (Throwable t) {
         HexieTrain.LOGGER.warn("[steer] {} 曲线判据异常 {}", pos.toShortString(), t.toString());
         return 0;
      }
      if (Math.abs(turn) < SimurailMath.EPSILON) {
         return 0;   // 他们同样把"几乎不弯的弯"当直行
      }
      return turn > 0.0 ? 1 : -1;
   }
}
