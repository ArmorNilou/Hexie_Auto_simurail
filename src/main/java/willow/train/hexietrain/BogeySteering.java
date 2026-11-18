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
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

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
 * <h2>为什么判据照抄他们的</h2>
 * {@code PhysicsBogeyAxle.steer()}（源码 1024-1096 行）里是这么分的：
 * <pre>
 *   trackLat = trackFrame.lateral;                       // 行进右
 *   turn = curvature(edge.getTurn(), 0) · trackLat;
 *   edge.isTurn() == false                → 直行
 *   turn &lt; 0                             → 左分支
 *   turn &gt; 0                             → 右分支
 * </pre>
 * 我们算同一个量，所以注入的值和他们解读的语义完全一致，不存在"我算的和它想的不一样"。
 *
 * <h2>为什么遥控能绕开车体朝向</h2>
 * 本地红石那条路要先用 {@code getFacing()} 决定哪两个面算"左右"（车体相关）；
 * 而 {@code getRemoteSteerValue()} 只是 {@code clamp((right − left)/15)}，完全不看朝向。
 *
 * <h2>安全不变量</h2>
 * 本类只允许在 {@code level.hasChunkAt(pos)} 通过的区块上调 {@code getBlockEntity}，
 * 绝不触发区块加载（同 {@link BogeyBinding} 的死锁教训）。
 */
public final class BogeySteering {

   /** 提前多少米开始给信号。 */
   private static final double ARM_M = 4.0;
   /** 跨过节点之后再保持几 tick（防止在节点上抖动）。 */
   private static final int HOLD_TICKS = 3;

   private final Map<BlockPos, State> states = new HashMap<>();

   private static final class State {
      /** −1 左 / +1 右 / 0 不给 */
      int side = 0;
      /** 已经真正写进转向架的值，避免每 tick 重复写同一个数 */
      int written = 0;
      int hold = 0;
      TrackEdge lastEdge = null;
      /** 上一次判定的目标边摘要，只给日志用 */
      String note = "-";
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
         release(st, level, anchor, pos, bogey);
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
         release(st, level, anchor, pos, bogey);
         return;
      }

      double remain = route.remainingToLegEnd(tp.edge, tp.position);
      if (!Double.isFinite(remain) || remain > ARM_M) {
         release(st, level, anchor, pos, bogey);
         return;
      }

      st.side = sideOf(next, travelDirOf(tp));
      st.note = (next.isTurn() ? "弯" : "直") + " " + String.format("%.1fm", next.getLength());
      write(level, anchor, pos, st);
   }

   private void release(State st, ServerLevel level, BlockPos anchor, BlockPos pos, PhysicsBogeyBlockEntity bogey) {
      st.side = 0;
      st.hold = 0;
      write(level, anchor, pos, st);
   }

   /**
    * 锚点用调度器自己的位置，一个调度器一个来源。
    *
    * <p><b>非 0 必须每 tick 重写一次。</b> Simurail 的
    * {@code getRemoteSteerValue()}（源码 978-1003 行）里写着：
    * <pre>
    *   if(currentTime - pair.leftLong() &gt; 2) { i.remove(); }
    * </pre>
    * 也就是说遥控值写进去之后<b>只活 3 tick</b>，没人续期就自动消失。
    * 只在"变化时"写一次的话，第 4 tick 起 steerValue 回到 0，
    * trackJoint 立刻把轴拽回直行段 —— 现象就是"过岔时抽一下然后弹回直行"。
    *
    * <p>值为 0 时不能这么干：{@code setRemoteLeftSteerOverride(pos, 0)} 是<b>删条目</b>
    * （源码 489-505 行），重复写等于每 tick 清一次别人的条目，没意义也不安全，
    * 所以 0 只在从非 0 退回来时写一次。
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
         if (changed) {
            HexieTrain.LOGGER.info("[steer] {} {} 目标边={}", pos.toShortString(),
               st.side == 0 ? "放直行" : (st.side > 0 ? "打【行进右】" : "打【行进左】"), st.note);
         }
      } catch (Throwable t) {
         HexieTrain.LOGGER.warn("[steer] {} 写遥控失败 {}", pos.toShortString(), t.toString());
      }
   }

   // ================================================================ 判据

   /**
    * 目标边往哪边弯。<b>照抄 Simurail 的 {@code steer()}：</b>
    * 直线边 = 直行；曲率点乘"行进右" &gt; 0 = 往右，&lt; 0 = 往左。
    *
    * @return −1 左 / +1 右 / 0 直行
    */
   private static int sideOf(TrackEdge want, Vec3 travel) {
      if (want == null || travel == null || !want.isTurn()) {
         return 0;
      }
      Vec3 right = travel.cross(new Vec3(0.0, 1.0, 0.0));
      if (right.lengthSqr() < 1.0E-8) {
         return 0;
      }
      Vector3d curv = new Vector3d();
      SimurailMath.cachedControlPoints(want.getTurn()).curvature(0.0, curv);
      double turn = curv.x * right.x + curv.y * right.y + curv.z * right.z;
      if (Math.abs(turn) < 1.0E-6) {
         return 0;
      }
      return turn > 0.0 ? 1 : -1;
   }

   /** 这根轴当前的行进方向（沿边切向，取远端）。 */
   private static Vec3 travelDirOf(TravellingPoint p) {
      if (p == null || p.edge == null) {
         return null;
      }
      try {
         boolean alongEdge = (p.node1 == p.edge.node1);
         Vec3 d = alongEdge ? p.edge.getDirection(false) : p.edge.getDirection(true).scale(-1.0);
         return d == null || d.lengthSqr() < 1.0E-8 ? null : d;
      } catch (Throwable t) {
         return null;
      }
   }
}
