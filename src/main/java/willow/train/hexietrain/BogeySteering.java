package willow.train.hexietrain;

import com.crystaelix.simurail.api.math.SimurailMath;
import com.crystaelix.simurail.content.bogey.PhysicsBogeyAxle;
import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;
import com.crystaelix.simurail.content.bogey.PhysicsBogeyGroup;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.createmod.catnip.data.Couple;
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

   /** 每个转向架位置只打一次岔道点普查 */
   private final Set<BlockPos> switchDumped = new HashSet<>();

   /** 让权日志：每个位置只打一次 */
   private final Set<BlockPos> yieldLogged = new HashSet<>();

   /** currentPath 的 dest 上一次打出去的内容：只有 dest 变了才打，避免每拍刷屏 */
   private final Map<BlockPos, String> pathDestLogged = new HashMap<>();

   /** "目标边和当前边不接"每个位置只报一次 */
   private final Set<BlockPos> pathBrokenLogged = new HashSet<>();

   /** "清空 currentPath"每个位置只报一次（抓静默清路） */
   private final Set<BlockPos> pathClearedLogged = new HashSet<>();

   /** currentPath 写入失败只报一次 */
   private boolean warnedPath = false;

   /**
    * PhysicsBogeyAxle.currentPath（protected），只反射一次。
    *
    * <p>为什么必须读它：2026-11-23 19:23:06 那次连续岔道走错已经证明
    * <ul>
    *   <li>四根轴的 steer 值全程 0（日志里最后一条值变更是 19:22:23.749）；</li>
    *   <li>steer() 在 steerValue==0 时只可能返回直边，不可能返回那条 34.63m 的弯。</li>
    * </ul>
    * 所以那一次是 navigate() 决定的，而它只有在"喂进去的节点 == 即将到达的岔口节点"
    * 时才会退化成"取候选第一条"。path 停在哪一腿，只能读出来看。读不到不影响主流程。
    */
   private static final Field CURRENT_PATH;

   static {
      Field cf = null;
      try {
         cf = PhysicsBogeyAxle.class.getDeclaredField("currentPath");
         cf.setAccessible(true);
      } catch (Throwable ignored) {
      }
      CURRENT_PATH = cf;
   }

   /** 每根轴的横向翻转校准：+1 原样、−1 取反。靠过节点命中/失手闭环学出来，不靠猜。 */
   private final Map<BlockPos, Integer> frameFlip = new HashMap<>();

   /** 撕裂日志：每个位置只报一次 */
   private final Set<BlockPos> tearLogged = new HashSet<>();

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
      /** 过岔锁：>0 表示判定已经锁定，这拍只续写不重算（防最后几拍符号翻掉） */
      int locked = 0;
      TrackEdge lastEdge = null;
      /** 上一次判定想要的下一段边（跨节点时用来核对到底走了哪股道） */
      TrackEdge wanted = null;
      /** 已经因为"判定不出来"续过一次值，只留一条日志 */
      boolean kept = false;
      /** 上一次判定的目标边摘要，只给日志用 */
      String note = "-";
      /** 判定那一刻 Create 真正交给选择器的候选（过了 canTravelTo 之后），只给日志用 */
      String candNote = "-";
      /** 判定那一刻复刻 Simurail steer() 算出来的选择，只给日志用 */
      String candWhy = "-";
      /** 候选里有没有弯边：自校准能不能把这次失手归咎于符号，就看它 */
      boolean hadCurve = false;
      /** 判定那一刻抓到的真候选（Simurail 真正会看到的那份），留给"分支"判定用 */
      List<Map.Entry<TrackNode, TrackEdge>> realCands = null;
      /** 最后一拍的分支拍照：谁在带头、谁在跟随、follow 会选谁 */
      String branchNote = "-";
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
      try {
      try {
         detectTear(level, roster, route);
      } catch (Throwable t) {
         HexieTrain.LOGGER.warn("[tear] 撕裂检测异常 {}", t.toString());
      }
         oneWriterPerGroup(level, anchor, roster, route);
      } catch (Throwable t) {
         HexieTrain.LOGGER.warn("[steer] 组内让权异常 {}", t.toString());
      }
   }

   /**
    * <b>一车一组，只留一个"写值者"。</b>
    *
    * <p>{@code PhysicsBogeyGroup}（0.1.4 源码 55 行起）本来就是干这个的：
    * <pre>
    *   float steer = target.getSteerValue();
    *   if(steer != 0) return steer;                    // 自己有位值就直接用
    *   ... 找前后邻居，按 reverseStates 把邻居的值<b>翻正</b>后抄过来 ...
    * </pre>
    * 也就是说：<b>只要组里只有一个转向架带非 0 值，其余全部由它按各自的物理朝向翻正后继承</b>。
    * 反过来，如果我们给组里每个转向架各写一个"自己算出来的"值，就等于绕开了它那套
    * reverseStates 校正 —— 组内部一旦两个值语义相反，选股道时就是各走各的，车当场撕裂
    * （2026-11-22 21:10 日志：20481031 打 +1 上了弯、20481034 打 -1 上了直，同一节车）。
    *
    * <p>所以这里把"写值权"收给组内**沿路线最靠前**（{@link RailCache.Route#sOf} 最大）的那一个转向架，
    * 同组其他转向架一律清零，让它去继承。
    */
   /**
    * 撕裂检测：哪根绑定轴的边**不在这条路线上**，说明车已经跨在两股道上（岔口被劈开）。
    *
    * <p>2026-11-22 22:20 日志：78 次过节点失手 1 次之后，``连续 40 拍绑定 4 根、只解析出 3 根``，
    * 之后 48 次规划全是 ``第 1 根算不出弧长`` —— 车劈开以后，任何一条路线都不可能覆盖整车，
    * 调度器按"不猜"的规矩永远拒绝出发。所以这一行日志是"车劈了"的唯一直接证据，必须有。
    */
   private void detectTear(ServerLevel level, BogeyRoster roster, RailCache.Route route) {
      for (BlockPos pos : roster.positions()) {
         if (!level.hasChunkAt(pos)) {
            continue;
         }
         if (!(level.getBlockEntity(pos) instanceof PhysicsBogeyBlockEntity b)) {
            continue;
         }
         TravellingPoint tp;
         try {
            tp = b.getAxle(true).getTrackPoint();
         } catch (Throwable t) {
            continue;
         }
         if (tp == null || tp.edge == null || route.legOf(tp.edge) != null) {
            this.tearLogged.remove(pos);
            continue;
         }
         if (this.tearLogged.add(pos)) {
            HexieTrain.LOGGER.warn("[tear] {} 这根轴不在路线上（edgeLen={} ）⇒ 车跨在两股道上，规划必然失败",
               pos.toShortString(), String.format("%.2f", tp.edge.getLength()));
         }
      }
   }

   /**
    * 把"下一步走哪条边"喂给转向架的 `currentPath` —— 这才是 Simurail 的**导航接口**。
    *
    * <p>`PhysicsBogeyAxle.control()` 里：
    * ```
    *   if(nextPoint == null || nextPoint.edge == null) {
    *       if(!currentPath.isEmpty()) return navigate(point, currentPath).apply(graph, pair);
    *       else                       return steer(point).apply(graph, pair);
    *   }
    * ```
    * 有 currentPath 时优先 navigate()，**三态开关根本不参与**。
    * 最前面那根轴永远没有"前一根轴"可跟，所以它一定走这条分支 —— 正好是要选股道的那一根。
    *
    * <p>navigate() 的匹配条件是：
    * ```
    *   if(candEdge.node1 != target.getFirst() && candEdge.node2 != target.getSecond()) continue;
    * ```
    * 是 `&&` 不是 `||`。return 的条件是"node1 相等 **或** node2 相等"。
    * 同一节点出发的候选边**以下游节点为键**（`getConnectionsFrom` 的 map key），所以
    * 只要 `second` = 目标边的下游节点，就能精确挑出那一条；
    * 而 `first` 也必须填成同一个下游节点，才能让**别的**候选被跳过
    * （别的候选的 node1 都是上游节点：若 first 填上游节点，则 node1 相等 ⇒ 条件为假 ⇒ 会误命中）。
    * 所以传 `Couple(dest, dest)`。
    */
   private void feedPath(PhysicsBogeyBlockEntity bogey, TrackEdge next, TrackNode junction, TrackEdge cur, BlockPos pos) {
      try {
         if (bogey == null || next == null || junction == null) {
            return;
         }
         // 目标边必须和当前边真的共用那个岔口节点。不共用 ⇒ junction/dest 都是从 next.node2 硬凑的假值，
         // 喂进去只会让 navigate() 去匹配一个跟这个岔口无关的节点。宁可不喂。
         if (cur != null && next.node1 != cur.node1 && next.node1 != cur.node2
               && next.node2 != cur.node1 && next.node2 != cur.node2) {
            if (this.pathBrokenLogged.add(pos)) {
               HexieTrain.LOGGER.warn("[path] {} 目标边 {}m 和当前边 {}m 不共用节点 ⇒ 不喂 currentPath",
                  pos.toShortString(), String.format("%.2f", next.getLength()),
                  String.format("%.2f", cur.getLength()));
            }
            return;
         }
         TrackNode dest = (next.node1 == junction) ? next.node2 : next.node1;
         if (dest == null) {
            return;
         }
         List<Couple<TrackNode>> path = List.of(Couple.create(dest, dest));
         String destTxt = String.valueOf(dest.getLocation().getLocation());
         String lastDest = this.pathDestLogged.put(pos, destTxt);
         if (!destTxt.equals(lastDest)) {
            HexieTrain.LOGGER.info("[path] {} 喂 currentPath：dest={} 目标边={} {}m（导航优先，steer 只兜底）",
               pos.toShortString(), destTxt, next.isTurn() ? "弯" : "直",
               String.format("%.2f", next.getLength()));
         }
         PhysicsBogeyAxle front = bogey.getAxle(true);
         PhysicsBogeyAxle back = bogey.getAxle(false);
         if (front != null) {
            front.setCurrentPath(path);
         }
         if (back != null) {
            back.setCurrentPath(path);
         }
      } catch (Throwable t) {
         if (!this.warnedPath) {
            this.warnedPath = true;
            HexieTrain.LOGGER.warn("[steer] 写 currentPath 失败 {}", t.toString());
         }
      }
   }

   /** 不在导航里就把 currentPath 清空，别让旧路径继续指挥。 */
   private void clearPath(ServerLevel level, BlockPos pos, String why) {
      try {
         if (!level.hasChunkAt(pos)) {
            return;
         }
         if (!(level.getBlockEntity(pos) instanceof PhysicsBogeyBlockEntity bogey)) {
            return;
         }
         // 取证：只有真的清掉了一条非空路径才报（抓"静默清路"导致 navigate 失明）
         if (!this.pathClearedLogged.contains(pos)) {
            PhysicsBogeyAxle fa = bogey.getAxle(true);
            String oldPath = readPath(fa);
            if (oldPath != null && !oldPath.startsWith("空")) {
               this.pathClearedLogged.add(pos);
               HexieTrain.LOGGER.info("[path] {} 清空 currentPath（{}）原内容=[{}]", pos.toShortString(), why, oldPath);
            }
         }
         List<Couple<TrackNode>> empty = List.of();
         PhysicsBogeyAxle front = bogey.getAxle(true);
         PhysicsBogeyAxle back = bogey.getAxle(false);
         if (front != null) {
            front.setCurrentPath(empty);
         }
         if (back != null) {
            back.setCurrentPath(empty);
         }
      } catch (Throwable ignored) {
      }
   }

   private void oneWriterPerGroup(ServerLevel level, BlockPos anchor, BogeyRoster roster, RailCache.Route route) {
      Map<PhysicsBogeyGroup, List<BlockPos>> byGroup = new HashMap<>();
      Map<BlockPos, Double> sOf = new HashMap<>();
      for (BlockPos pos : roster.positions()) {
         if (!level.hasChunkAt(pos)) {
            continue;
         }
         if (!(level.getBlockEntity(pos) instanceof PhysicsBogeyBlockEntity b)) {
            continue;
         }
         PhysicsBogeyGroup g;
         try {
            g = b.getGroup();
         } catch (Throwable t) {
            continue;
         }
         if (g == null) {
            continue;
         }
         byGroup.computeIfAbsent(g, k -> new ArrayList<>()).add(pos);
         double s = -1.0;
         try {
            TravellingPoint tp = b.getAxle(true).getTrackPoint();
            if (tp != null && tp.edge != null) {
               s = route.sOf(tp.edge, tp.position);
            }
         } catch (Throwable ignored) {
         }
         sOf.put(pos, s);
      }
      for (List<BlockPos> list : byGroup.values()) {
         if (list.size() <= 1) {
            continue;
         }
         BlockPos lead = list.get(0);
         for (BlockPos p : list) {
            if (sOf.getOrDefault(p, -1.0) > sOf.getOrDefault(lead, -1.0)) {
               lead = p;
            }
         }
         for (BlockPos p : list) {
            if (p.equals(lead)) {
               continue;
            }
            State st = this.states.get(p);
            if (st == null || st.side == 0) {
               continue;
            }
            st.side = 0;
            st.hold = 0;
            write(level, anchor, p, st);
            if (this.yieldLogged.add(p)) {
               HexieTrain.LOGGER.info("[steer] {} 让权给组内领先轴 {}（自己清零，靠组继承）",
                  p.toShortString(), lead.toShortString());
            }
         }
      }
   }

   /** 不在导航 / 手动模式：全部放回直行。 */
   public void releaseAll(ServerLevel level, BlockPos anchor, BogeyRoster roster) {
      for (BlockPos pos : roster.positions()) {
         State st = this.states.computeIfAbsent(pos, k -> new State());
         clearPath(level, pos, "退出导航/手操");
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
      boolean edgeChanged = false;
      if (st.lastEdge != null && !RailCache.sameLink(st.lastEdge, tp.edge)) {
         edgeChanged = true;
         if (st.wanted != null) {
            boolean hit = RailCache.sameLink(st.wanted, tp.edge);
            if (!hit) {
               // 闭环校准：这根轴这次算的号跟 Simurail 选的不一致 ⇒ 记住"这根轴的横向要取反"。
               // 【2026-11-23 加前提】只有岔口上真的存在弯候选，符号才有资格被怀疑。
               // 候选全是直边的话 steer() 根本不看 steerValue，翻它等于把一次"路径失败"
               // 记成"符号错"，之后这根轴的极性被永久污染。
               if (st.side != 0 && st.hadCurve) {
                  int cur = this.frameFlip.getOrDefault(pos, 1);
                  this.frameFlip.put(pos, -cur);
                  HexieTrain.LOGGER.warn("[steer] {} 过岔失手（舵={} 且岔口有弯候选）⇒ 该轴横向校准翻成 {}（下次按这个打）",
                     pos.toShortString(), st.side, -cur);
               } else {
                  HexieTrain.LOGGER.warn("[steer] {} 过岔失手但舵={} / 弯候选={} ⇒ steer 不看这个值，不校准（不是符号问题）",
                     pos.toShortString(), st.side, st.hadCurve);
               }
            }
            HexieTrain.LOGGER.info(
               "[steer] {} 过节点 实际上了={} {} ｜ 想要={} {} ⇒ {} ｜ 当时舵={} ｜ 真候选[{}] ｜ steer会选[{}] ｜ 分支[{}]",
               pos.toShortString(),
               tp.edge.isTurn() ? "弯" : "直", String.format("%.2fm", tp.edge.getLength()),
               st.wanted.isTurn() ? "弯" : "直", String.format("%.2fm", st.wanted.getLength()),
               hit ? "命中" : "❌ 走错股道",
               st.side, st.candNote, st.candWhy, st.branchNote
            );
            logCurrentPath(axle, pos, "过节点时");
            dumpCandidates(axle, st.lastEdge, tp.edge, pos);
            st.wanted = null;
         }
         st.locked = 0;
         st.kept = false;
         st.hold = st.side == 0 ? 0 : HOLD_TICKS;
      }
      st.lastEdge = tp.edge;

      // ===== 2026-11-23 定案：喂 currentPath 必须每拍都做，不能排在 hold/locked 后面 =====
      // 只要 navigate() 拿到的 dest 正好等于"即将到达的那个岔口节点"，它内部那句
      //   candEdge.node1 != first && candEdge.node2 != second
      // 对【全部】候选恒为 false（因为从该节点出去的每条边 node1 都等于它），
      // 于是直接 return 候选[0] —— 一条跟路线完全无关的固定分支。
      //
      // 19:55:41 / 19:55:42 两次走错就是它：当时舵=0，真候选[弯38.56m(右) ｜ 直14.00m]，
      // 复刻 steer() 会选【直14.00m】，实走却是未过滤候选表的第一条 弯48.45m。
      // 而 hold(3 拍)/locked(10 拍) 的早退把短腿整段盖住（2m 那条要 6 拍），
      // path 就停在上一腿的 dest 上一动没动。
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

      feedPath(bogey, next, junction, tp.edge, pos);   // ← 每拍都喂，让导航决定股道

      // 每拍拍一张"谁在做决定"的照片：过岔时打出来的就是最后一拍（真正决策那一刻）
      try {
         st.branchNote = describeControlBranch(bogey, bogey.getAxle(true), st.realCands)
            + " ‖ " + describeControlBranch(bogey, bogey.getAxle(false), st.realCands);
      } catch (Throwable ignored) {
      }

      Vector3dc lateral = lateralOf(axle, pos);
      if (lateral == null) {
         keepOrRelease(st, level, anchor, pos, "读不到 trackFrame.lateral");
         return;
      }

      if (st.hold > 0) {
         st.hold--;
         write(level, anchor, pos, st);
         return;
      }

      // ===== 过岔锁：进入 leg 末端 3 米就**只判一次**，之后锁住只续写 =====
      // 2026-11-22 22:20 日志：78 次过节点里 1 次失手，就是最后几拍 trackFrame.lateral
      // 被 trackSegment.reverse() 翻了一次，我们重算出来的号跟 Simurail 读的那一刻不一致。
      // 岔口前 3 米的一次判定，比"每拍重算"更接近它读到的状态。
      // 注意：锁只管【舵值】，不管 path —— path 已经在上面每拍喂过了。
      if (st.locked > 0) {
         st.locked--;
         write(level, anchor, pos, st);
         return;
      }
      double remain = Double.NaN;
      try {
         remain = route.remainingToLegEnd(tp.edge, tp.position);
      } catch (Throwable ignored) {
      }

      st.side = sideOf(next, lateral, pos) * this.frameFlip.getOrDefault(pos, 1);
      if (Double.isFinite(remain) && remain <= 3.0) {
         st.locked = 10;   // 3 米内锁 10 拍：足够跨过节点
      }
      st.wanted = next;
      st.note = (next.isTurn() ? "弯" : "直") + String.format(" %.2fm", next.getLength());

      // ===== 取证：把 Create 真正交给选择器的候选抓下来，再复刻 Simurail 的 steer() 算它会挑哪条 =====
      // 只在换边那一拍做一次（岔口锁住之后不会重算，抓一次就够）
      if (edgeChanged) {
         List<Map.Entry<TrackNode, TrackEdge>> real = realCandidates(axle);
         st.hadCurve = hasCurve(real, lateral);
         st.realCands = real;
         st.candNote = describeCandidates(real, lateral);
         st.candWhy = describeSteerPick(real, lateral, st.side);
      }

      // ===== 真正的道岔：simurail:track_switch =====
      // steer 值只是"没有岔道方块时的默认选择器"。只要岔口上装了 TrackSwitch 且它 isActive()，
      // Simurail 的 TravellingPointMixin 会在 TravellingPoint.travel 里**直接把我们的选择器顶掉**：
      //   if(sw != null) { target = sw.selectTarget(pair.getSecond()); if(target != null) return target; }
      // 也就是说：有岔道方块时，写多少 steer 都没用，走哪股由那个方块的 state 决定。
      // 所以必须在过岔之前把方块扳到我们要的那一侧。

      throwSwitch(level, axle.getTrackGraph(), tp, next, pos);

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
   private void throwSwitch(ServerLevel level, TrackGraph graph, TravellingPoint tp, TrackEdge want, BlockPos pos) {
      try {
         TrackNode exitNode = (tp.node1 == tp.edge.node1) ? tp.edge.node2 : tp.edge.node1;
         if (want == null) {
            return;
         }
         if (exitNode == null) {
            return;
         }
         TrackSwitch sw = null;
         // 岔道点可能注册在本边，也可能注册在反相的那个边对象上，两边都扫
         for (TrackEdge e : new TrackEdge[]{tp.edge, reverseOf(graph, tp.edge)}) {
            if (e == null) {
               continue;
            }
            for (TrackEdgePoint p : e.getEdgeData().getPoints()) {
               if (p instanceof TrackSwitch s) {
                  if (this.switchDumped.add(pos)) {
                     HexieTrain.LOGGER.info("[switch] {} 边上有岔道点 {} 主侧={} 激活={}",
                        pos.toShortString(), s.getBlockEntityPos() == null ? "?" : s.getBlockEntityPos().toShortString(),
                        s.isPrimary(exitNode), s.isActive());
                  }
                  if (s.isActive() && s.isPrimary(exitNode)) {
                     sw = s;
                     break;
                  }
               }
            }
            if (sw != null) {
               break;
            }
         }
         if (sw == null) {
            if (this.switchDumped.add(pos)) {
               HexieTrain.LOGGER.info("[switch] {} 出口节点 {} 上没有激活的岔道方块 ⇒ 这条岔口只能靠 steer 值",
                  pos.toShortString(), exitNode.getLocation().getLocation());
            }
            return;
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

   /** 同一个图上反相的那个边对象（Create 里正反是两条不同的边对象）。 */
   private static TrackEdge reverseOf(TrackGraph g, TrackEdge e) {
      if (g == null || e == null || e.node1 == null || e.node2 == null) {
         return null;
      }
      try {
         Map<TrackNode, TrackEdge> back = g.getConnectionsFrom(e.node2);
         return back == null ? null : back.get(e.node1);
      } catch (Throwable t) {
         return null;
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
      clearPath(level, pos, "放直行");
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
    * 过节点那一刻，把**岔口上所有候选边**按 Simurail 的分类方式打出来。
    *
    * <p>这一步是为了分清两种完全不同的失败：
    * <ol>
    *   <li>那条弯在候选里、但被归进了另一组 ⇒ 是左右/取端的问题；</li>
    *   <li>那条弯**根本不在候选里**（或者两条都是"直"，straightTarget 只能装一条）⇒
    *       写什么 steer 都没用，得改用别的机制（例如 TrackSwitch 或 axle.setCurrentPath）。</li>
    * </ol>
    * turn 用的是**本轴自己的** {@code getTrackFrame().lateral()}，也就是 Simurail 在 steer() 里
    * 用的那一条，所以这一行就是它眼里的世界。
    */
   /**
    * <b>Create 真正交给选择器的候选</b>——不是我们自己去 getConnectionsFrom 拼的。
    *
    * <p>做法跟 Simurail updateTrack 里一模一样：把轴的 TravellingPoint 复制一份，放到边末尾 0.05m，
    * 再让 Create 自己 travel 0.1m。它跨到节点时会回调选择器，回调参数里那份 list 就是
    * <b>过了 canTravelTo 过滤之后</b>的候选——也就是 Simurail 的 steer() 真正看得到的那一份。
    *
    * <p>以前我们拿 getConnectionsFrom 直接当候选打日志，那份<b>没过滤</b>，里面混着
    * "刚走过的那条反向边"，所以 straightTarget 到底是谁一直算不准。
    */
   /**
    * <b>直接说出 control() 这一刻走的是哪条分支。</b>
    *
    * <p>PhysicsBogeyAxle.control() 只有三种结局：
    * <pre>
    *   nextPoint == null   → currentPath 非空 ? navigate() : steer()
    *   nextPoint != null   → follow(nextPoint) 成功就 return，失败再 steer()
    * </pre>
    * 而 nextPoint 取谁由 isCurrentFront() 决定，它是<b>按速度符号动态判的</b>：
    * <pre>
    *   return logicalFront &amp;&amp; speed &gt; 0 || !logicalFront &amp;&amp; speed &lt; 0;
    * </pre>
    * 所以同一个转向架的两根轴会随车前/后换角色：一根是"带头轴"（走 navigate/steer），
    * 另一根是"跟随轴"（走 follow(other)）。而 follow() 只认节点身份：
    * <pre>
    *   if(entry.getKey() == other.node1 || entry.getKey() == other.node2) return entry;
    * </pre>
    * 只要另一根轴先上了某条支线，这根轴就会被"顺"过去 —— 错一股会连着错两股。
    * 这一行就是为了把"到底是谁做的决定"钉死。
    */
   private static String describeControlBranch(PhysicsBogeyBlockEntity bogey, PhysicsBogeyAxle axle,
                                               List<Map.Entry<TrackNode, TrackEdge>> real) {
      if (axle == null) {
         return "轴?";
      }
      String tag = bogey != null && axle == bogey.getAxle(true) ? "前轴" : "后轴";
      try {
         boolean curFront = axle.isCurrentFront();
         PhysicsBogeyAxle partner = axle.other();
         StringBuilder sb = new StringBuilder();
         sb.append(tag).append("{isCur=").append(curFront ? 1 : 0);
         sb.append(" other=").append(edgeTxt(partner == null ? null : partner.getTrackPoint()));
         if (curFront) {
            PhysicsBogeyBlockEntity ahead = bogey.getConnected(true);
            sb.append(" 前连接=").append(ahead == null ? "无" : ahead.getBlockPos().toShortString());
            sb.append(" 分支=").append(ahead == null ? "navigate/steer" : "follow(前连接)");
         } else {
            sb.append(" 分支=follow(other)→").append(followWouldPick(real, partner));
         }
         sb.append(" 有效舵=").append(String.format("%.1f", bogey.getGroupSteerValue()));
         sb.append("}");
         return sb.toString();
      } catch (Throwable t) {
         return tag + "{异常:" + t.getMessage() + "}";
      }
   }

   /** 复刻 follow(other)：候选的键（下游节点）撞上另一根轴那条边的任一端，就选它。 */
   private static String followWouldPick(List<Map.Entry<TrackNode, TrackEdge>> real, PhysicsBogeyAxle partner) {
      if (partner == null) {
         return "无对轴";
      }
      TravellingPoint op = partner.getTrackPoint();
      if (op == null || op.edge == null) {
         return "对轴无轨→steer";
      }
      if (real == null || real.isEmpty()) {
         return "没候选→steer";
      }
      for (Map.Entry<TrackNode, TrackEdge> e : real) {
         TrackNode key = e.getKey();
         if (key != null && (key == op.node1 || key == op.node2)) {
            TrackEdge ed = e.getValue();
            return ed == null ? "?" : (ed.isTurn() ? "弯" : "直") + String.format("%.2fm", ed.getLength());
         }
      }
      return "没匹配上→steer";
   }

   private static String edgeTxt(TravellingPoint tp) {
      if (tp == null || tp.edge == null) {
         return "无";
      }
      String a = tp.node1 == null ? "?" : String.valueOf(tp.node1.getLocation().getLocation());
      String b = tp.node2 == null ? "?" : String.valueOf(tp.node2.getLocation().getLocation());
      return (tp.edge.isTurn() ? "弯" : "直") + String.format("%.2fm", tp.edge.getLength()) + "[" + a + "→" + b + "]";
   }

   private static List<Map.Entry<TrackNode, TrackEdge>> realCandidates(PhysicsBogeyAxle axle) {
      try {
         if (axle == null) {
            return null;
         }
         TrackGraph g = axle.getTrackGraph();
         TravellingPoint src = axle.getTrackPoint();
         if (g == null || src == null || src.edge == null) {
            return null;
         }
         double len = src.edge.getLength();
         if (!(len > 0.2)) {
            return null;
         }
         // 哪一端才是"前进的那一端"：只看几何，不猜 Create 的 node1/node2 约定。
         // 轴可能走在反相的那条边对象上（Create 每条轨正反是两个 TrackEdge），
         // 一律当成 len-0.05 往前推的话，探针会往【刚走过】的那个岔口走，
         // 抓回来的候选跟真正的岔口根本不是一个
         //（2026-11-23 首轮取证：62 次里只有 6 次的候选含"想要"那条）。
         boolean farIsNode2 = true;
         try {
            Vec3 here = CreateApi.worldPosition(g, src);
            Vec3 p1 = src.edge.node1 == null ? null : src.edge.node1.getLocation().getLocation();
            Vec3 p2 = src.edge.node2 == null ? null : src.edge.node2.getLocation().getLocation();
            if (here != null && p1 != null && p2 != null) {
               farIsNode2 = here.distanceTo(p2) > here.distanceTo(p1);
            }
         } catch (Throwable ignored) {
         }
         double startPos = farIsNode2 ? Math.max(0.0, len - 0.05) : 0.05;
         double dist = farIsNode2 ? 0.1 : -0.1;
         TravellingPoint probe = new TravellingPoint(src.node1, src.node2, src.edge, startPos, src.upsideDown);
         List<Map.Entry<TrackNode, TrackEdge>> cap = new ArrayList<>();
         TravellingPoint.ITrackSelector sel = (graph, pair) -> {
            List<Map.Entry<TrackNode, TrackEdge>> c = pair.getSecond();
            if (c != null && !c.isEmpty()) {
               cap.addAll(c);
            }
            return c == null || c.isEmpty() ? null : c.get(0);
         };
         probe.travel(g, dist, sel);
         return cap;
      } catch (Throwable t) {
         return null;
      }
   }

   /** 候选里有没有"真弯"：turn 值和本轴 lateral 点乘超过 EPSILON 才算。 */
   private static boolean hasCurve(List<Map.Entry<TrackNode, TrackEdge>> cands, Vector3dc lateral) {
      if (cands == null || lateral == null) {
         return false;
      }
      for (Map.Entry<TrackNode, TrackEdge> e : cands) {
         TrackEdge ed = e.getValue();
         if (ed == null || !ed.isTurn()) {
            continue;
         }
         try {
            Vector3d c = new Vector3d();
            SimurailMath.cachedControlPoints(ed.getTurn()).curvature(0.0, c);
            if (Math.abs(c.dot(lateral)) >= SimurailMath.EPSILON) {
               return true;
            }
         } catch (Throwable ignored) {
         }
      }
      return false;
   }

   /** 候选摘要：直/弯 + 长度，弯的再带左右和 turn 值。 */
   private static String describeCandidates(List<Map.Entry<TrackNode, TrackEdge>> cands, Vector3dc lateral) {
      if (cands == null) {
         return "抓不到";
      }
      if (cands.isEmpty()) {
         return "空";
      }
      if (lateral == null) {
         return "读不到lateral";
      }
      StringBuilder sb = new StringBuilder();
      for (Map.Entry<TrackNode, TrackEdge> e : cands) {
         TrackEdge ed = e.getValue();
         if (ed == null) {
            continue;
         }
         if (sb.length() > 0) {
            sb.append(" ｜ ");
         }
         if (!ed.isTurn()) {
            sb.append("直").append(String.format("%.2fm", ed.getLength()));
            continue;
         }
         double turn = 0.0;
         try {
            Vector3d c = new Vector3d();
            SimurailMath.cachedControlPoints(ed.getTurn()).curvature(0.0, c);
            turn = c.dot(lateral);
         } catch (Throwable ignored) {
         }
         sb.append("弯").append(String.format("%.2fm", ed.getLength()));
         if (Math.abs(turn) < SimurailMath.EPSILON) {
            sb.append("(平)");
         } else if (turn < 0) {
            sb.append(String.format("(左 %+.5f)", turn));
         } else {
            sb.append(String.format("(右 %+.5f)", turn));
         }
      }
      return sb.toString();
   }

   /**
    * <b>逐行复刻 Simurail PhysicsBogeyAxle.steer()</b>，只把 steerValue 换成我们当前的值，
    * 算出"它这一刻会挑哪一条"。用来分清两种完全不同的失败：
    * <ol>
    *   <li>它挑的就是那条弯（候选里没有直边 / 舵值把它推进 rightTargets）⇒ 是我们给错值；</li>
    *   <li>它挑的正是我们要的，实际却走了别的 ⇒ 根本没轮到 steer，是 navigate() 在决定。</li>
    * </ol>
    */
   private static String describeSteerPick(List<Map.Entry<TrackNode, TrackEdge>> cands, Vector3dc lateral, int side) {
      if (cands == null) {
         return "抓不到";
      }
      if (cands.isEmpty()) {
         return "空";
      }
      if (lateral == null) {
         return "读不到lateral";
      }
      try {
         int n = cands.size();
         double[] turns = new double[n];
         List<Integer> left = new ArrayList<>();
         List<Integer> right = new ArrayList<>();
         int straight = -1;
         double minTurn = Double.MAX_VALUE;
         double maxTurn = -Double.MAX_VALUE;
         for (int i = 0; i < n; i++) {
            TrackEdge ed = cands.get(i).getValue();
            double turn = 0.0;
            if (ed != null && ed.isTurn()) {
               Vector3d c = new Vector3d();
               SimurailMath.cachedControlPoints(ed.getTurn()).curvature(0.0, c);
               turn = c.dot(lateral);
               if (Math.abs(turn) < SimurailMath.EPSILON) {
                  if (straight < 0) {
                     straight = i;
                  }
               } else if (turn < 0) {
                  left.add(i);
               } else {
                  right.add(i);
               }
            } else {
               straight = i;
            }
            turns[i] = turn;
            minTurn = Math.min(minTurn, turn);
            maxTurn = Math.max(maxTurn, turn);
         }
         int best = -1;
         if (straight >= 0 && (side == 0 || side < 0 && left.isEmpty() || side > 0 && right.isEmpty())) {
            best = straight;
         } else if (!left.isEmpty() && (side <= 0 || right.isEmpty())) {
            best = nearestTurn(left, turns, side, minTurn, maxTurn);
         } else {
            best = nearestTurn(right, turns, side, minTurn, maxTurn);
         }
         if (best < 0) {
            return "targets.get(0)";
         }
         TrackEdge ed = cands.get(best).getValue();
         return (ed.isTurn() ? "弯" : "直") + String.format("%.2fm", ed.getLength());
      } catch (Throwable t) {
         return "复刻异常";
      }
   }

   private static int nearestTurn(List<Integer> group, double[] turns, int side, double minTurn, double maxTurn) {
      double a = Math.max(minTurn, 0.0);
      double target = a + (maxTurn - a) * side;
      int best = -1;
      double bestDiff = Double.MAX_VALUE;
      for (int i : group) {
         double d = Math.abs(target - turns[i]);
         if (d < bestDiff) {
            bestDiff = d;
            best = i;
         }
      }
      return best;
   }

   /** 反射读 currentPath，把每个节点的位置打出来；节点已不在当前图里就标 ⚠旧对象。 */
   private static String readPath(PhysicsBogeyAxle axle) {
      if (axle == null) {
         return null;
      }
      if (CURRENT_PATH == null) {
         return "读不到(反射不可用)";
      }
      try {
         Object v = CURRENT_PATH.get(axle);
         if (!(v instanceof List<?> list)) {
            return "非List";
         }
         if (list.isEmpty()) {
            return "空";
         }
         TrackGraph g = axle.getTrackGraph();
         StringBuilder sb = new StringBuilder();
         for (Object o : list) {
            if (!(o instanceof Couple<?> c)) {
               continue;
            }
            if (sb.length() > 0) {
               sb.append(" ｜ ");
            }
            Object a = c.getFirst();
            Object b = c.getSecond();
            sb.append(nodeTxt(g, a));
            if (b != a) {
               sb.append("→").append(nodeTxt(g, b));
            }
         }
         return sb.length() == 0 ? "空" : sb.toString();
      } catch (Throwable t) {
         return "读失败:" + t.getMessage();
      }
   }

   private static String nodeTxt(TrackGraph g, Object o) {
      if (!(o instanceof TrackNode nd)) {
         return "?";
      }
      String s = String.valueOf(nd.getLocation().getLocation());
      try {
         if (g != null && g.locateNode(nd.getLocation()) != nd) {
            s = s + "⚠旧对象";
         }
      } catch (Throwable ignored) {
      }
      return s;
   }

   private void logCurrentPath(PhysicsBogeyAxle axle, BlockPos pos, String tag) {
      try {
         HexieTrain.LOGGER.info("[path] {} {} currentPath=[{}]", pos.toShortString(), tag, readPath(axle));
      } catch (Throwable ignored) {
      }
   }
   private void dumpCandidates(PhysicsBogeyAxle axle, TrackEdge prev, TrackEdge taken, BlockPos pos) {
      try {
         TrackGraph g = axle == null ? null : axle.getTrackGraph();
         if (g == null || prev == null || taken == null) {
            return;
         }
         TrackNode j = (prev.node1 == taken.node1 || prev.node1 == taken.node2) ? prev.node1 : prev.node2;
         Map<TrackNode, TrackEdge> conns = CreateApi.connections(g, j);
         if (conns == null || conns.isEmpty()) {
            return;
         }
         Vector3dc lat = lateralOf(axle, pos);
         StringBuilder sb = new StringBuilder();
         for (Map.Entry<TrackNode, TrackEdge> en : conns.entrySet()) {
            TrackEdge e = en.getValue();
            if (e == null) {
               continue;
            }
            double t = 0.0;
            if (e.isTurn() && lat != null) {
               Vector3d c = new Vector3d();
               SimurailMath.cachedControlPoints(e.getTurn()).curvature(0.0, c);
               t = c.dot(lat);
            }
            if (sb.length() > 0) {
               sb.append(" ｜ ");
            }
            String farTxt = "?";
            try {
               TrackNode far = (e.node1 == j) ? e.node2 : e.node1;
               farTxt = far == null ? "?" : String.valueOf(far.getLocation().getLocation());
            } catch (Throwable ignored) {
            }
            sb.append(String.format("%s%.2fm turn%+.5f→%s", e.isTurn() ? "弯" : "直", e.getLength(), t, farTxt));
         }
         HexieTrain.LOGGER.info("[steer] {} 岔口候选（本轴 trackFrame 算 turn）：{}", pos.toShortString(), sb);
      } catch (Throwable t) {
         HexieTrain.LOGGER.warn("[steer] {} 候选边诊断异常 {}", pos.toShortString(), t.toString());
      }
   }

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