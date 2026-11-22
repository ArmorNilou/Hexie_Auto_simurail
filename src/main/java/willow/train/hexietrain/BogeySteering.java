package willow.train.hexietrain;

import com.crystaelix.simurail.api.math.SimurailMath;
import com.crystaelix.simurail.content.bogey.PhysicsBogeyAxle;
import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;
import com.crystaelix.simurail.content.track_switch.TrackSwitch;
import com.crystaelix.simurail.content.track_switch.TrackSwitchBlockEntity;
import com.crystaelix.simurail.content.track_switch.TrackSwitchState;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.signal.TrackEdgePoint;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
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

   /** TrackSwitch 的三个出口字段（protected）只反射一次 */
   private static final Map<String, Field> SWITCH_FIELDS = new ConcurrentHashMap<>();

   /** 曲线判据读不到 lateral 时只警告一次，别刷屏。 */
   private boolean warnedNoFrame = false;
   /** 岔口三个出口对不上 / 反射失败，各只警告一次 */
   private boolean warnedSwitchNoExit = false;
   private boolean warnedSwitchError = false;

   private static final class State {
      /** −1 左 / +1 右 / 0 不给 */
      int side = 0;
      /** 已经真正写进转向架的值，避免每 tick 重复写同一个数 */
      int written = 0;
      int hold = 0;
      TrackEdge lastEdge = null;
      /** 上一次判定想要的下一段边（跨节点时用来核对到底走了哪股道） */
      TrackEdge wanted = null;
      /** 已经因为"判定不出来"续过一次值，只留一条日志 */
      boolean kept = false;
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
         if (st.wanted != null) {
            boolean hit = RailCache.sameLink(st.wanted, tp.edge);
            HexieTrain.LOGGER.info(
               "[steer] {} 过节点 实际上了={} {} ｜ 想要={} {} ⇒ {}",
               pos.toShortString(),
               tp.edge.isTurn() ? "弯" : "直", String.format("%.2fm", tp.edge.getLength()),
               st.wanted.isTurn() ? "弯" : "直", String.format("%.2fm", st.wanted.getLength()),
               hit ? "命中" : "❌ 走错股道"
            );
            st.wanted = null;
         }
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
         // 这根轴已经不在路线上了 —— 但**不许单方面放直行**。
         // Simurail 的 PhysicsBogeyGroup.getSteerValue(target) 在自己为 0 时会去
         // 拿邻居的值来凑（front/back 单取一个，或者 (front+back)/2）。
         // 同一组里"一个 ±1、一个 0"正好会踩进那条路，而那条路里
         // `backReversed` 用的是 frontIndex（0.1.3/0.1.4 的复制粘贴笔误），
         // 结果可能被翻成反号，甚至 (1 + -1)/2 = 0 ⇒ 该进岔的时候进直行。
         // 所以：只要本车别的转向架还在给值，这里就把上一次的值续着，
         // 真正的清零交给 releaseAll（路线没了 / 手操 / 不在导航）。
         keepOrRelease(st, level, anchor, pos, "不在路线上");
         return;
      }

      // 岔口节点 = 当前边和目标边共用的那个节点（不看 node1/node2 的编号，只用几何身份）
      TrackNode junction = (next.node1 == tp.edge.node1 || next.node1 == tp.edge.node2)
         ? next.node1 : next.node2;

      // ===== 判据用的"行进右"必须**全车一致**，不能取每根转向架自己的 trackFrame.lateral =====
      // 2026-11-22 21:10/21:19 两次实机日志钉死了这件事：
      //   20481031 打(+1) → 过节点 实际上了=弯 38.56m ⇒ 命中
      //   20481034 打(−1) → 过节点 实际上了=直 14.00m ⇒ ❌
      // 同一条目标边、同一个岔口，两根轴要的却是同一个符号 ⇒ 它们算出相反符号是错的。
      // 根因：`trackFrame.lateral` 会被 trackAxleFrame.direction 翻正，那是**每根转向架自己的朝向**；
      // 而 1.2.6 试过的 `edge.getDirection(0/1)` 也不行 —— 同一条轨在 Create 里正反是两个边对象，
      // node1/node2 编号可以互相颠倒，`alongEdge` 判断一样会反。
      // 唯一没有歧义的写法：**从轴当前世界位置指向岔口节点**，那就是行进方向。
      Vector3dc lateral = travelRightOf(axle, tp, junction);
      if (lateral == null) {
         keepOrRelease(st, level, anchor, pos, "算不出行进方向");
         return;
      }

      st.side = sideOf(next, lateral, pos);
      st.wanted = next;
      st.note = (next.isTurn() ? "弯" : "直") + String.format(" %.2fm", next.getLength());

      // ===== 真正的道岔：simurail:track_switch =====
      // steer 值只是"没有岔道方块时的默认选择器"。只要岔口上装了 TrackSwitch 且它 isActive()，
      // Simurail 的 TravellingPointMixin 会在 TravellingPoint.travel 里**直接把我们的选择器顶掉**：
      //   if(sw != null) { target = sw.selectTarget(pair.getSecond()); if(target != null) return target; }
      // 也就是说：有岔道方块时，写多少 steer 都没用，走哪股由那个方块的 state 决定。
      // 所以必须在过岔之前把方块扳到我们要的那一侧。
      throwSwitch(level, tp, next, pos);

      st.kept = false;
      write(level, anchor, pos, st);
   }

   /**
    * 把岔口的 {@code simurail:track_switch} 扳到我们要走的那一侧。
    *
    * <p>岔口三个出口各对应一个 state：{@code STRAIGHT / LEFT / RIGHT}；
    * 我们要走的目标边在岔口那一端的"下游节点"就是出口节点，拿它跟三个出口比一下就知道了。
    * 出口位置是 {@code TrackSwitch} 的 protected 字段，只能反射读（同 {@code SignalEdgeGroup.bogeys} 的做法）。
    */
   private void throwSwitch(ServerLevel level, TravellingPoint tp, TrackEdge want, BlockPos pos) {
      try {
         TrackNode exitNode = (tp.node1 == tp.edge.node1) ? tp.edge.node2 : tp.edge.node1;
         if (exitNode == null || want == null) {
            return;
         }
         TrackSwitch sw = null;
         for (TrackEdgePoint p : tp.edge.getEdgeData().getPoints()) {
            if (p instanceof TrackSwitch s && s.isActive() && s.isPrimary(exitNode)) {
               sw = s;
               break;
            }
         }
         if (sw == null) {
            return;   // 这个节点上没有岔道方块：照旧走 steer
         }
         TrackNode wantFar = (want.node1 != exitNode) ? want.node1 : want.node2;
         if (wantFar == null) {
            return;
         }
         TrackNodeLocation wantLoc = wantFar.getLocation();
         TrackSwitchState need = stateFor(sw, wantLoc);
         if (need == null) {
            if (!this.warnedSwitchNoExit) {
               this.warnedSwitchNoExit = true;
               HexieTrain.LOGGER.warn("[switch] {} 岔道三个出口都对不上要走的边（{}），这条岔口扳不了", pos.toShortString(), wantLoc.getLocation());
            }
            return;
         }
         BlockPos sp = sw.getBlockEntityPos();
         if (sp == null) {
            return;
         }
         if (!level.hasChunkAt(sp)) {
            return;   // 区块没加载：不猜（同 BogeyBinding 的死锁教训）
         }
         if (!(level.getBlockEntity(sp) instanceof TrackSwitchBlockEntity be)) {
            return;
         }
         if (be.getState() != need) {
            be.trySetState(need);
            HexieTrain.LOGGER.info("[switch] {} 扳道岔 {} {} → {}", pos.toShortString(),
               sp.toShortString(), describe(be.getState()), describe(need));
         }
      } catch (Throwable t) {
         if (!this.warnedSwitchError) {
            this.warnedSwitchError = true;
            HexieTrain.LOGGER.warn("[switch] {} 扳岔异常 {}", pos.toShortString(), t.toString());
         }
      }
   }

   /** 目标出口节点对应哪个 state。读不到就返回 null（不猜）。 */
   private static TrackSwitchState stateFor(TrackSwitch sw, TrackNodeLocation wantLoc) {
      if (matchExit(sw, "straightExit", wantLoc)) return TrackSwitchState.STRAIGHT;
      if (matchExit(sw, "leftExit", wantLoc)) return TrackSwitchState.LEFT;
      if (matchExit(sw, "rightExit", wantLoc)) return TrackSwitchState.RIGHT;
      return null;
   }

   private static boolean matchExit(TrackSwitch sw, String field, TrackNodeLocation wantLoc) {
      try {
         Field f = switchField(field);
         Object v = f.get(sw);
         if (!(v instanceof TrackNodeLocation loc) || loc == null) {
            return false;
         }
         return loc.getLocation().equals(wantLoc.getLocation());
      } catch (Throwable t) {
         return false;
      }
   }

   private static Field switchField(String name) throws NoSuchFieldException {
      Field f = SWITCH_FIELDS.get(name);
      if (f == null) {
         f = TrackSwitch.class.getDeclaredField(name);
         f.setAccessible(true);
         SWITCH_FIELDS.put(name, f);
      }
      return f;
   }

   private static String describe(TrackSwitchState s) {
      return s == null ? "null" : s.name();
   }

   /**
    * 判定不出来时：已经给着非 0 值就**续着**（绝不单方面写 0，理由见调用处），
    * 本来就没给过值才真的清零。
    */
   private void keepOrRelease(State st, ServerLevel level, BlockPos anchor, BlockPos pos, String why) {
      if (st.side != 0) {
         st.hold = 0;
         if (!st.kept) {
            st.kept = true;
            HexieTrain.LOGGER.info("[steer] {} {} ⇒ 保住上次的 {}（不单方面放直行）",
               pos.toShortString(), why, st.side);
         }
         write(level, anchor, pos, st);
         return;
      }
      release(st, level, anchor, pos);
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

   /**
    * 行进右 = 归一化(岔口节点位置 − 轴当前位置) × 上。
    *
    * <p><b>为什么不用 edge.getDirection()</b>：Create 里同一条轨正反是**两个边对象**，
    * node1/node2 可以互相颠倒，`p.node1 == p.edge.node1` 这种判断照样会翻；
    * 而"指向即将通过的节点"没有任何歧义，全车四根转向架必然算出同一个向量。
    */
   private static Vector3dc travelRightOf(PhysicsBogeyAxle axle, TravellingPoint p, TrackNode junction) {
      if (axle == null || p == null || p.edge == null || junction == null) {
         return null;
      }
      try {
         TrackGraph g = axle.getTrackGraph();
         if (g == null) {
            return null;
         }
         Vec3 here = p.getPosition(g);
         Vec3 there = junction.getLocation().getLocation();
         if (here == null || there == null) {
            return null;
         }
         Vec3 d = there.subtract(here);
         if (d.lengthSqr() < 1.0E-8) {
            return null;
         }
         Vec3 r = d.normalize().cross(new Vec3(0.0, 1.0, 0.0));
         if (r.lengthSqr() < 1.0E-8) {
            return null;
         }
         return new Vector3d(r.x, r.y, r.z);
      } catch (Throwable t) {
         return null;
      }
   }

   /** 转向架自己的"行进右"向量。**只用于日志/诊断**，判据不许用它（见 tickOne 里的注释）。 */
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
      // ===== 符号翻过来（2026-11-22 20:44 实机日志证明的，不是猜）=====
      // 证据：20481034 对「弯 38.56m」打了 −1（== 行进左），结果过节点时上了「直 14.00m」。
      // 按 Simurail 的 steer()，steerValue != 0 时只有在候选里**那条弯不在对应组**才会退回
      // straightTarget —— 也就是那条弯在节点上被归进了 rightTargets，leftTargets 是空的。
      // ⇒ 想让这根轴走那条弯，该给的是 +1。同一节车厢的另一根轴算出 +1，两根轴最后都走了直股，
      // 说明**两根轴的符号都是反的**，所以这里整体取反。
      // 取反后与 Simurail 的 `curvature(0)·lateral` 严格反号：他们要正我们给负。
      return turn > 0.0 ? 1 : -1;
   }
}
