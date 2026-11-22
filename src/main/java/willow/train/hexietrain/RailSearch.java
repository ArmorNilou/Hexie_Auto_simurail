package willow.train.hexietrain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.station.GlobalStation;

import net.minecraft.world.phys.Vec3;

/**
 * 单方向有向搜索：车头 / 车尾各搜一次，取近的那条。
 *
 * 形状是从 Create 的 Navigation.findPathTo 读出来的：
 *   Create 的"折返"只有一种含义 —— 这一趟从车头开，还是从车尾开。
 *   它 for (forward : trueAndFalse) 从 leadingPoint / trailingPoint 各搜一次，
 *   用 hasForwardConductor / hasBackwardConductor / doubleEnded 门控，最后取近的。
 *   搜索里没有任何"中途换向"，DiscoveredPath.distance 带符号（forward ? +dist : -dist）。
 *
 *   所以人字形折返在 Create 的模型里 = 折返点放一个站，到站停车推进 entry，下一段重搜。
 *   段内永远单方向。这就是本类不做换向状态机的原因。
 *
 * 图是"有向"的（从 connectNodes 字节码确认）：
 *   TrackGraph.connectNodes 给每一段轨道建两个 TrackEdge ——
 *     edgeA(nodeA→nodeB) 挂 connectionsByNode[nodeA]
 *     edgeB(nodeB→nodeA) 挂 connectionsByNode[nodeB]
 *   而 getConnectionsFrom(n) 只返回 node1 == n 的边（"从 n 出去"）。
 *   "进入 n 的边"在这张表里根本不存在，所以也不做 F∩T 交汇。
 *
 * 七条约定：
 *   1. 有界          只扩到 maxDistance
 *   2. 单方向        沿有向边往外，不回头
 *   3. 键(边,入口)   身份用「无序节点对 + 入口节点」，不用 TrackEdge 对象（正反是两个对象）
 *   4. 只做角度      dot > 0.875（约 29°），不掺信号 / 占用 / 转弯惩罚
 *   5. 纯函数        不写任何字段
 *   6. 终点焊死      必须走到「驶入站点的那条有向边」，方向由边的方向本身保证
 *   7. 弧长原点在车  s(车)=0，于是 remaining 的正负就是"站还在前 / 已经开过站"
 */
public final class RailSearch {

   /** 剪枝日志限流：每张图最多记 8 条，避免每秒刷屏 */
   private static int pruneLogs = 0;

   private RailSearch() {}

   /** 与 TrackEdge.canTravelTo 相同的阈值，cos(θ) > 0.875 ⇔ θ < 约 29° */
   public static final double ANGLE_COS = 0.875;

   private static final double EPS = 1e-6;
   /** 端点容差：转向架允许落在搜索起点"后方"多少米。超过 = 路线没覆盖整个车体。 */
   private static final double TAIL_EPS = 0.05;
   /** 车站必须比领先端再远这么多米，否则不算"往前开到站"。 */
   private static final double LEAD_EPS = 0.02;

   // ================================================================ 结构

   /**
    * 搜索状态：(边, 入口节点)。
    *
    * 不能用 TrackEdge 对象做身份 —— 同一段轨道正反是两个不同对象
    * （connectNodes 里 new TrackEdge(a,b,..) 与 new TrackEdge(b,a,..)），
    * 用对象比较会让"同一条轨"在两次搜索里对不上。
    */
   private static final class Key {
      final TrackEdge edge;
      final TrackNode enter;
      final TrackNode a;
      final TrackNode b;

      Key(TrackEdge edge, TrackNode enter) {
         this.edge = edge;
         this.enter = enter;
         this.a = edge == null ? null : edge.node1;
         this.b = edge == null ? null : edge.node2;
      }

      @Override
      public boolean equals(Object o) {
         if (this == o) return true;
         if (!(o instanceof Key k)) return false;
         if (k.enter != this.enter) return false;
         return (k.a == this.a && k.b == this.b) || (k.a == this.b && k.b == this.a);
      }

      @Override
      public int hashCode() {
         int pair = System.identityHashCode(this.a) + System.identityHashCode(this.b);
         return pair * 31 + System.identityHashCode(this.enter);
      }
   }

   /** 搜索结果：路线 + 从哪头开 + 沿轨距离。 */
   public static final class Result {
      public final boolean ok;
      /**
       * 本段路线沿哪个方向展开：
       * true = 朝车头所在边的 node2；false = 朝 node1。
       * <b>不是"选出来的"，是面标定测出来的</b>：标定告诉我们给某个面加力后车往哪头滚，
       * 路线就必须朝同一头展开，否则 s 轴与车的实际运动反向、距站会越走越大。
       */
      public final boolean towardNode2;
      public final RailCache.Route route;
      /** 车 → 站点 沿轨长度；= route.stationS（弧长原点在车上） */
      public final double distance;
      public final int edges;
      public final String note;
      /** 这次真正被选作寻路起点的那一端（就是传进来的 endA 或 endB 之一）。null = 没绑定/失败。 */
      public final TravellingPoint startPoint;

      private Result(boolean ok, boolean towardNode2, RailCache.Route route, double distance, int edges,
                     String note, TravellingPoint startPoint) {
         this.ok = ok;
         this.towardNode2 = towardNode2;
         this.route = route;
         this.distance = distance;
         this.edges = edges;
         this.note = note;
         this.startPoint = startPoint;
      }

      static Result fail(String note) {
         return new Result(false, true, null, Double.NaN, 0, note, null);
      }

      public String describe() {
         return (this.ok ? "ok" : "失败")
            + " 方向=" + (this.towardNode2 ? "朝node2" : "朝node1")
            + " 距站=" + String.format("%.1f", this.distance)
            + " 边数=" + this.edges
            + (this.ok ? "" : (" 原因=" + this.note));
      }
   }

   // ================================================================ 入口

   public static Result search(TrackGraph g, Train train, GlobalStation station, String expectName,
                              double maxDistance) {
      if (train == null) return Result.fail("参数为空");
      return searchFrom(g, CreateApi.leadPoint(train), station, expectName, maxDistance);
   }

   /** 记下"这次用了哪一端"，并把方向旗改成在该端判定。 */
   private static Result withStart(Result r, TravellingPoint start) {
      if (r == null || !r.ok) return r == null ? Result.fail("没有结果") : r;
      if (start == null || start.edge == null || r.route == null) {
         return new Result(true, r.towardNode2, r.route, r.distance, r.edges, r.note, start);
      }
      RailCache.Leg leg = r.route.legOf(start.edge);
      if (leg == null) {
         // 选中的起点不在这条路线上：不猜方向。上层 sOf 会返回 NaN 并抱闸重规划。
         HexieTrain.LOGGER.warn("[nav] 方向判定：选中的起点不在路线里，方向旗保持不变（不猜）");
         return new Result(true, r.towardNode2, r.route, r.distance, r.edges, r.note, start);
      }
      // 沿路线前进时，该端那条边上的位置是不是从 node1 往 node2 走：
      // sOf 翻一次、arcOf 再翻一次，所以符号 = 两者异或。
      boolean node1To2 = (start.edge.node1 == leg.edge.node1) != leg.reversed;
      return new Result(true, node1To2, r.route, r.distance, r.edges, r.note, start);
   }
   /**
    * 整列车两端共同起作用，然后决定用哪一端。<b>测量用全车，导航只认两端。</b>
    *
    * <h2>一条路线要同时满足三件事，缺一件车就散</h2>
    * <ol>
    *   <li><b>全部转向架都在这条路线上。</b>逐转向架过岔的前提 —— 少一根它就没有
    *       {@code nextEdgeAfter}，会直行，在道岔上被拉断。</li>
    *   <li><b>没有任何转向架落在搜索起点"后方"</b>（s &lt; 0）。否则路线只覆盖了车体一半。</li>
    *   <li><b>车站必须在领先端之外</b>（stationS &gt; s 最大）。否则就是"背着车站开"。</li>
    * </ol>
    * 这三条<b>对其中一个端必然不成立</b>（把领先端当起点，车体就全落在起点后方），
    * 所以两端各验一次，合格的那个端就是<b>后端</b>。
    *
    * <h2>为什么先要"枚举种子找一条盖住整车的路线"</h2>
    * 两端是<b>用 s 轴量出来的</b>，可 s 轴只能来自某次寻路。而某次寻路<b>未必覆盖整车</b>：
    * 从列车中段的转向架起搜，落在它后面的那些必然算不出 s。
    *
    * <p>以前（0.99）就是拿 {@code pts.get(0)} 起搜一次、把算不出 s 的跳过、直接认最小/最大为两端 ——
    * 那是拿一条<b>残缺路线</b>去定两端。2026-11-18 23:30 的日志就是这个：
    * <pre>
    *   23:29:57 A端合格：转向架4根全在路线上 s∈[0.00,8.00]     ← 探路正好从后端起搜，成功
    *   23:30:23 失败 两端都不合格 ｜ 都是"有转向架不在这条路线上"  ← 探路从中间起搜，两端都错
    * </pre>
    * 所以必须先找到一个<b>覆盖全车</b>的种子，再拿它的 s 轴定两端。
    *
    * <h2>方向不是算出来的，是被结果确定的</h2>
    * 选中的那次搜索的 s 轴方向，<b>就是</b>车将要行进的方向：
    * <pre>
    *   后端   = s 最小 = 搜索起点 = route 的 s=0
    *   领先端 = s 最大 = 距站唯一判据
    *   距站   = stationS − s(领先端)
    * </pre>
    * 这两条对正着开和倒着开同样成立，不需要任何符号翻转。
    *
    * @param preferred 上一段用的起点端。两端都合格（环线）时优先保持它，避免来回换向抽搐。
    */
   public static Result searchTrainWide(TrackGraph g, Train train, GlobalStation station, String expectName,
                                        double maxDistance, List<TravellingPoint> pts,
                                        TravellingPoint preferred) {
      if (train == null) return Result.fail("参数为空");
      if (pts == null || pts.isEmpty()) {
         TravellingPoint lead = CreateApi.leadPoint(train);
         return withStart(searchFrom(g, lead, station, expectName, maxDistance), lead);
      }
      if (pts.size() == 1) {
         return withStart(searchFrom(g, pts.get(0), station, expectName, maxDistance), pts.get(0));
      }

      // ===== 阶段一：枚举种子，直到有一条路线能覆盖整车（只有这样 s 轴才量得出真正的两端）=====
      // 顺序：上一段的起点 → 绑定表顺序。正常情况下第一个就成，最坏也只是把每根试一遍。
      Result probe = null; TravellingPoint probeSeed = null;
      String why = "-";
      List<TravellingPoint> seeds = new ArrayList<>(pts.size() + 1);
      if (preferred != null && preferred.edge != null) seeds.add(preferred);
      for (TravellingPoint p : pts) if (p != null && p.edge != null) seeds.add(p);
      for (TravellingPoint seed : seeds) {
         Result r = searchFrom(g, seed, station, expectName, maxDistance);
         if (r == null || !r.ok || r.route == null) {
            why = "寻路失败：" + (r == null ? "无结果" : r.note);
            continue;
         }
         int miss = firstUncovered(r, pts);
         if (miss >= 0) { why = "从这端起搜覆盖不到全车（第 " + (miss + 1) + " 根算不出弧长）"; continue; }
         probe = r; probeSeed = seed;
         break;
      }
      if (probe == null) {
         // 整车没有任何一条路线能覆盖 ⇒ 不出发。这不是"再猜一个起点"，是真的没路。
         return Result.fail("没有任何一端能搜出覆盖整车的路线：" + why);
      }

      // ===== 阶段二：用这条完整路线的 s 轴量出真正的两端 =====
      TravellingPoint endMin = null; TravellingPoint endMax = null;
      double sMin0 = Double.POSITIVE_INFINITY; double sMax0 = Double.NEGATIVE_INFINITY;
      for (TravellingPoint p : pts) {
         if (p == null || p.edge == null) continue;
         double s = probe.route.sOf(p.edge, p.position);
         if (!Double.isFinite(s)) continue;                 // 阶段一已保证不会发生
         if (s < sMin0) { sMin0 = s; endMin = p; }
         if (s > sMax0) { sMax0 = s; endMax = p; }
      }
      if (endMin == null) return Result.fail("一根转向架都排不上，无法定两端");

      // ===== 阶段三：两端各按①②③验一次，决定哪端当后端 =====
      // 探路结果如果起点正是最小端，直接复用，省一次寻路
      Result a = samePoint(probeSeed, endMin)
         ? validate(probe, endMin, pts, "A")
         : tryEnd(g, endMin, station, expectName, maxDistance, pts, "A");
      Result b = samePoint(endMax, endMin)
         ? null : tryEnd(g, endMax, station, expectName, maxDistance, pts, "B");

      boolean okA = a != null && a.ok;
      boolean okB = b != null && b.ok;
      if (okA && okB) {
         // 两端都合格（环线，站两边都能到）：优先保持上一段那一端，其次取更短的
         if (preferred != null) {
            if (samePoint(a.startPoint, preferred)) return a;
            if (samePoint(b.startPoint, preferred)) return b;
         }
         Result pick = a.distance <= b.distance ? a : b;
         HexieTrain.LOGGER.info("[nav] 两端都合格（环线）⇒ 取{}端 距站 {} vs {}", new Object[]{
            pick == a ? "A" : "B", String.format("%.1f", a.distance), String.format("%.1f", b.distance)});
         return pick;
      }
      if (okA) return a;
      if (okB) return b;
      // 两端都不合格 ⇒ 停手，把两边各自的原因都写清楚。绝不"那就照旧往前开"
      return Result.fail("两端都不合格 ｜ A端：" + (a == null ? "-" : a.note)
         + " ｜ B端：" + (b == null ? "-" : b.note));
   }

   /** 两端都试的旧签名（保留给不需要偏好端的调用）。 */
   public static Result searchTrainWide(TrackGraph g, Train train, GlobalStation station, String expectName,
                                        double maxDistance, List<TravellingPoint> pts) {
      return searchTrainWide(g, train, station, expectName, maxDistance, pts, null);
   }

   /** 第一根算不出弧长的转向架下标；-1 = 全车都在路线上。 */
   private static int firstUncovered(Result r, List<TravellingPoint> pts) {
      if (r == null || !r.ok || r.route == null) return 0;
      for (int i = 0; i < pts.size(); i++) {
         TravellingPoint p = pts.get(i);
         if (p == null || p.edge == null) continue;
         if (!Double.isFinite(r.route.sOf(p.edge, p.position))) return i;
      }
      return -1;
   }

   private static boolean samePoint(TravellingPoint a, TravellingPoint b) {
      if (a == null || b == null || a.edge == null || b.edge == null) return false;
      return a.edge == b.edge && Math.abs(a.position - b.position) < 0.05;
   }

   /** 以 {@code start} 当<b>后端</b>搜一次，再验①②③。 */
   private static Result tryEnd(TrackGraph g, TravellingPoint start, GlobalStation station,
                                String expectName, double maxDistance, List<TravellingPoint> pts, String tag) {
      Result r = searchFrom(g, start, station, expectName, maxDistance);
      if (r == null || !r.ok || r.route == null) {
         return Result.fail("寻路失败：" + (r == null ? "无结果" : r.note));
      }
      return validate(r, start, pts, tag);
   }

   /**
    * 三条硬条件。<b>这是唯一的判定处，不许有第二条路。</b>
    * 合格：ok=true，distance 改成从<b>领先端</b>量的距站。
    * 不合格：ok=false，note 只说原因（哪一端由上层拼）。
    */
   private static Result validate(Result r, TravellingPoint start, List<TravellingPoint> pts, String tag) {
      double sMin = Double.POSITIVE_INFINITY; double sMax = Double.NEGATIVE_INFINITY;
      int measured = 0; int badIdx = -1;
      for (int i = 0; i < pts.size(); i++) {
         TravellingPoint p = pts.get(i);
         if (p == null || p.edge == null) continue;
         double s = r.route.sOf(p.edge, p.position);
         if (!Double.isFinite(s)) { badIdx = i; break; }      // 条件①
         measured++;
         if (s < sMin) sMin = s;
         if (s > sMax) sMax = s;
      }
      if (badIdx >= 0) {
         TravellingPoint p = pts.get(badIdx);
         return Result.fail(String.format("第%d根转向架不在这条路线上（边[%s -> %s] pos=%.2f）",
            badIdx + 1, nodeStr(p.edge.node1), nodeStr(p.edge.node2), p.position));
      }
      if (measured == 0) return Result.fail("一根转向架都排不上");
      if (sMin < -TAIL_EPS) {                                  // 条件②
         return Result.fail(String.format("起点后方还有转向架（s最小=%.2f，车体会被路线截断）", sMin));
      }
      if (!Double.isFinite(r.route.stationS)) return Result.fail("车站没落在路线上");
      if (r.route.stationS <= sMax + LEAD_EPS) {                // 条件③
         return Result.fail(String.format("车站不在领先端之外（站s=%.2f 领先端s=%.2f）",
            r.route.stationS, sMax));
      }
      Result tagged = withStart(r, start);
      double lead = r.route.stationS - sMax;
      HexieTrain.LOGGER.info("[nav] {}端合格：转向架{}根全在路线上 s∈[{},{}] 站s={} ⇒ 领先端距站 {}", new Object[]{
         tag, measured, String.format("%.2f", sMin), String.format("%.2f", sMax),
         String.format("%.1f", r.route.stationS), String.format("%.1f", lead)});
      return new Result(true, tagged.towardNode2, r.route, lead, r.edges, tag + "端", start);
   }

   /**
    * 以**给定起点**搜。起点可以是临时拷贝（CreateApi.leadPoint 本身就返回拷贝），
    * 所以恢复流程可以拿它试探，不会动到车上的活对象。
    */
   public static Result searchFrom(TrackGraph g, TravellingPoint start, GlobalStation station, String expectName,
                              double maxDistance) {
      if (g == null || start == null || station == null) return Result.fail("参数为空");
      double max = maxDistance > 0.0 ? maxDistance : 4096.0;

      // ===== 校验一：目标车站身份 =====
      // 必须就是时刻表当前 entry 指定的那个站。断头路、别站的边点、最近站，一律不许当目标。
      if (expectName == null || expectName.isBlank()) return Result.fail("时刻表当前 entry 没有目标站名");
      if (station.name == null || !station.name.equalsIgnoreCase(expectName)) {
         return Result.fail("目标站名字不符：时刻表=" + expectName + " 站点=" + station.name);
      }

      // ===== 校验二：进入方向与可用轨道一致 =====
      // canApproachFrom(N) 的真身是 isPrimary(N) = edgeLocation.getSecond().equals(N.getLocation())，
      // 所以唯一合法的驶入方向是 edgeLocation.first → edgeLocation.second，反之不成立。
      if (station.edgeLocation == null) return Result.fail("站点没有 edgeLocation");
      TrackNode a = CreateApi.node(g, station.edgeLocation.getFirst());
      TrackNode b = CreateApi.node(g, station.edgeLocation.getSecond());
      if (a == null || b == null) return Result.fail("站点端点定位失败");
      Map<TrackNode, TrackEdge> outA = CreateApi.connections(g, a);
      TrackEdge arrival = outA == null ? null : outA.get(b);      // 严格 a→b，不做反向兜底
      if (arrival == null) {
         return Result.fail("站点准入侧没有可用驶入轨道：期望 " + a + "→" + b);
      }
      if (arrival.node1 != a || arrival.node2 != b) return Result.fail("驶入边朝向与准入端不一致");
      double posOnArrival = CreateApi.positionOn(station, arrival);
      if (!Double.isFinite(posOnArrival)) return Result.fail("站点位置不在驶入边上");

      // ===== 两道校验都过了，才按面标定给出的方向出发 =====
      // 单端起点：只从本车出发；两个初始方向由 run() 一起入队，岔口/支线随便走。
      // 只搜这一个方向：搜不到上面那条准入边就报失败，绝不许停在别的边点上。
      if (start.edge == null) return Result.fail("起点没有边");

      return run(g, station, start, arrival, posOnArrival, max);
   }


   // ================================================================ 单方向搜索

   private static Result run(TrackGraph g, GlobalStation station, TravellingPoint start,
                             TrackEdge arrival, double posOnArrival, double max) {
      TrackEdge e0 = start.edge;
      double len0 = CreateApi.length(e0);
      double p0 = Math.max(0.0, Math.min(len0, start.position));

      // 单端起点：从本车出发，**两个初始方向同时入队**。
      // 车压在一条轨上，朝 node1 和朝 node2 都是它可能走的路，不存在"只能一个方向"。
      // dist 语义：dist[Key] = 车到该 Key 的【入口节点】的沿轨距离（种子的入口节点就是车所在边的那端，故为 0）。
      Key kToNode2 = new Key(e0, e0.node1);   // 从 node1 侧进入 ⇒ 朝 node2 走
      Key kToNode1 = new Key(e0, e0.node2);   // 从 node2 侧进入 ⇒ 朝 node1 走

      // 车已经在这条驶入边上、且走向就是驶入方向 ⇒ 起点即终点
      if (e0 == arrival) {
         double stationS = posOnArrival - p0;
         ArrayList<Key> only = new ArrayList<>(1);
         only.add(kToNode2);
         return assemble(g, station, only, kToNode2, len0, p0, stationS, 1);
      }

      Map<Key, Double> dist = new HashMap<>();
      Map<Key, Key> parent = new HashMap<>();
      PriorityQueue<Object[]> pq = new PriorityQueue<>(Comparator.comparingDouble(o -> (double) o[0]));
      dist.put(kToNode2, 0.0);
      dist.put(kToNode1, 0.0);
      pq.add(new Object[]{0.0, kToNode2});
      pq.add(new Object[]{0.0, kToNode1});

      Key goal = null;
      int scanned = 0;
      int prunedSmooth = 0;   // 被几何门槛弹出的接续条数
      int staleFixes = 0;   // 过期节点重解析次数（>0 说明车的行走点指着旧图对象）
      List<String> pruned = new ArrayList<>();   // 被弹掉的样例（dot 值），失败时进日志

      while (!pq.isEmpty() && scanned < 200000) {
         Object[] top = pq.poll();
         scanned++;
         double d = (double) top[0];
         Key k = (Key) top[1];
         Double known = dist.get(k);
         if (known == null || d > known + EPS) continue;

         if (k.edge == arrival) {
            // 进入方向必须与准入端一致（必须是 a→b 那条有向边），否则不算到站
            if (k.enter == arrival.node1) {
               goal = k;
               break;
            }
         }

         double leave = (k == kToNode2) ? (len0 - p0) : ((k == kToNode1) ? p0 : CreateApi.length(k.edge));
         double nd = d + leave;
         if (nd > max) continue;

         TrackNode exit = CreateApi.opposite(k.edge, k.enter);
         if (exit != null) {
            TrackNode live = relive(g, exit);
            if (live != null && live != exit) {
               // 抓到过期节点：车的行走点还指着旧对象，新图同一位置是另一个对象。
               // 不重新解析就会"岔口只剩一条回头边"，被当成尽头。
               staleFixes++;
               if (staleFixes <= 3) {
                  HexieTrain.LOGGER.warn("[search] 节点过期已重解析 {} 第 {} 次",
                     new Object[]{String.format("(%.0f,%.0f,%.0f)", exit.getLocation().getLocation().x,
                        exit.getLocation().getLocation().y, exit.getLocation().getLocation().z), staleFixes});
               }
               exit = live;
            }
         }
         if (exit == null) continue;
         boolean revFrom = (k.enter == k.edge.node2);

         Map<TrackNode, TrackEdge> conns = CreateApi.connections(g, exit);
         if (conns == null) continue;
         for (Map.Entry<TrackNode, TrackEdge> en : conns.entrySet()) {
            TrackEdge f = en.getValue();
            if (f == null || f == k.edge) continue;
            boolean revTo = (exit == f.node2);
            if (!smooth(k.edge, revFrom, f, revTo)) {
               if (pruneLogs++ < 8) {
                  // 决定性诊断：exit 到底是"来的那头"还是"去的那头"？
                  // 岔口就在前面却弹出全部 dot=-1，只有两种可能：exit 取反，或节点对象过期。
                  HexieTrain.LOGGER.warn("[search] 弹出 dot={} 来边[{} -> {}] revFrom={} ｜ exit={} ｜ 去边[{} -> {}] revTo={} exit==来边.node1?{} exit==来边.node2?{}",
                     new Object[]{String.format("%+.3f", dotOfTravel(k.edge, revFrom, f, revTo)),
                        nodeStr(k.edge.node1), nodeStr(k.edge.node2), revFrom, nodeStr(exit),
                        nodeStr(f.node1), nodeStr(f.node2), revTo,
                        exit == k.edge.node1, exit == k.edge.node2});
               }
                prunedSmooth++;
                if (pruned.size() < 5) pruned.add(String.format("%.3f[%.0f->%.0f]",
                   dotOfTravel(k.edge, revFrom, f, revTo), k.edge.getLength(), f.getLength()));
                continue;
             }
            Key nk = new Key(f, exit);
            Double old = dist.get(nk);
            if (old == null || nd < old - EPS) {
               dist.put(nk, nd);
               parent.put(nk, k);
               pq.add(new Object[]{nd, nk});
            }
         }
      }

      if (goal == null) {
         // 失败时把**可达集合**也打出来：只有"被弹掉的样例"看不出是"剪错"还是"这段真不通"。
         // 有了这张表就能直接对比站点准入边在不在里面、断在哪个节点。
         StringBuilder reach = new StringBuilder();
         int shown = 0;
         for (Key k : dist.keySet()) {
            if (shown++ >= 12) { reach.append(" …共 ").append(dist.size()).append(" 条"); break; }
            if (reach.length() > 0) reach.append(" | ");
            reach.append(String.format("%.0fm@%s", k.edge == null ? -1 : k.edge.getLength(), nodeStr(k.enter)));
         }
         return Result.fail("搜不到驶入站点的边（上限 " + String.format("%.0f", max) + "，扩张 " + scanned
            + " 次，可达状态 " + dist.size() + " 个，几何弹出 " + prunedSmooth + " 条 " + pruned
            + "，准入边 " + nodeStr(arrival.node1) + "→" + nodeStr(arrival.node2)
            + "，可达=[" + reach + "]）");
      }
      ArrayList<Key> path = new ArrayList<>();
      Key w = goal;
      int guard = 0;
      while (w != null && guard++ < 200000) {
         path.add(0, w);
         if (!parent.containsKey(w)) break;      // 没有父 = 种子（车所在的边）
         w = parent.get(w);
      }
      if (path.isEmpty()) return Result.fail("父指针链断裂");
      Key k0 = path.get(0);
      double stationS = dist.get(goal) + posOnArrival;
      return assemble(g, station, path, k0, len0, p0, stationS, path.size());
   }

   // ================================================================ 组装

   private static Result assemble(TrackGraph g, GlobalStation station, List<Key> path, Key k0,
                            double len0, double p0, double stationS, int edgeCount) {
      // 方向是**搜出来的事实**：种子从哪一侧进入车所在边，就朝另一侧走
      boolean towardNode2 = (k0.enter == k0.edge.node1);
      List<RailCache.Leg> legs = new ArrayList<>();
      boolean rev0 = (k0.enter == k0.edge.node2);

      // 弧长原点放在车上：第一条边的 startS 取负，使 s(车) = 0
      double acc = rev0 ? -(len0 - p0) : -p0;
      for (Key k : path) {
         if (k.edge == null) continue;
         boolean rev = (k.enter == k.edge.node2);
         legs.add(new RailCache.Leg(k.edge, acc, rev));
         acc += CreateApi.length(k.edge);
      }
      if (legs.isEmpty()) return Result.fail("路径为空");

      RailCache.Route route = RailCache.assemble(g, station, legs);
      route.stationS = stationS;
      return new Result(true, towardNode2, route, stationS, edgeCount, "", null);
   }

   // ================================================================ 几何

   /**
    * 接续的顺滑度必须按**行进方向**算：两段各取**共享节点那一端**的切线。
    *
    * <pre>
    *   到达朝向 = 沿 from 走到它远端（共享节点）时的行进方向
    *   出发朝向 = 从共享节点沿 to 走时的行进方向
    *   smooth = 到达朝向 · 出发朝向 > ANGLE_COS
    * </pre>
    *
    * <p><b>2026-11-17 02:08 日志铁证</b>：剪枝样本里出现 `-1.000[16->15]` —— 车沿 16 米边
    * <b>倒着</b>跑时，真正接续的那条 15 米边被算成了 dot=-1 而被剪掉。原因是我在 0.56.3 把这里
    * 改成照抄 {@code TrackEdge.canTravelTo}（{@code from.getDirection(false)·to.getDirection(true)}），
    * 而那个式子<b>不看行进方向</b>：倒着走时 from 的取样端正好取反，接续就变成"掉头"。
    *
    * <p>所以这里不能照抄 canTravelTo：正向时本式与它等价（同样取 node2 端与 node1 端），
    * 反向时才是对的。
    */
   private static Vec3 arriveDir(TrackEdge e, boolean arriveAtNode2) {
      if (e == null) return Vec3.ZERO;
      try {
         Vec3 d = arriveAtNode2 ? e.getDirection(false) : e.getDirection(true).scale(-1.0);
         return d == null ? Vec3.ZERO : d;
      } catch (Throwable t) {
         return Vec3.ZERO;
      }
   }

   /** 从共享节点出发、沿这条边走的朝向。 */
   private static Vec3 departDir(TrackEdge e, boolean departFromNode1) {
      if (e == null) return Vec3.ZERO;
      try {
         Vec3 d = departFromNode1 ? e.getDirection(true) : e.getDirection(false).scale(-1.0);
         return d == null ? Vec3.ZERO : d;
      } catch (Throwable t) {
         return Vec3.ZERO;
      }
   }

   /**
    * 按行进方向算点积。
   /** 节点坐标一行摘要，诊断用 */
   private static String nodeStr(TrackNode n) {
      try {
         if (n == null) return "null";
         net.minecraft.world.phys.Vec3 v = n.getLocation().getLocation();
         return v == null ? "?" : String.format("(%.1f,%.1f,%.1f)", v.x, v.y, v.z);
      } catch (Throwable t) {
         return "?";
      }
   }

   /**
    * 行进方向点乘。
    * @param fromReversed 从 from 的 node2 端进入
    * @param toReversed   在 to 的 node2 端进入
    */
   private static double dotOfTravel(TrackEdge from, boolean fromReversed, TrackEdge to, boolean toReversed) {
      if (from == null || to == null) return -2.0;
      Vec3 out = arriveDir(from, !fromReversed);
      Vec3 in = departDir(to, !toReversed);
      if (out.lengthSqr() < 1.0E-8 || in.lengthSqr() < 1.0E-8) return -2.0;
      return out.dot(in);
   }

   private static boolean smooth(TrackEdge from, boolean fromReversed, TrackEdge to, boolean toReversed) {
      return dotOfTravel(from, fromReversed, to, toReversed) > ANGLE_COS;
   }

   /**
    * 按位置在当前图里重新解析节点。
    *
    * <p>轨道重铺 / 图重建之后，车的 TravellingPoint 和 Key 里可能还留着**旧的 TrackNode 对象**；
    * 新图上同一位置已经是另一个对象，于是 getConnectionsFrom(旧节点) 只会返回回头的那一条边。
    *
    * <p>解析不到（位置不在当前图里）就退回原对象，不猜。
    */
   private static TrackNode relive(TrackGraph g, TrackNode n) {
      if (g == null || n == null) return n;
      try {
         TrackNode live = g.locateNode(n.getLocation());
         return live == null ? n : live;
      } catch (Throwable t) {
         return n;
      }
   }
}
