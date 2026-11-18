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
   /**
    * 以<b>整列车的两端</b>为起点搜索，并且把选中的那一端当作唯一参照系带回去。
    *
    * <p><b>范式</b>（和 Create 的车厢链一致）：终端只有两个 —— 绑定表排序后的第一个和最后一个转向架。
    * 每次都只从<b>其中一端</b>起搜，不许再引入第三个点（以前那个 CreateApi.leadPoint 就是第三点，
    * 它既不是首端也不是尾端，方向判定一对就错）。多少根转向架都只从这两端里挑一个。
    *
    * <p>怎么挑：先从 A 端起搜，再看<b>另一端 B 落不落在这次算出来的路线上</b>。
    * 落在上面说明 A 是上游端、整列车都被覆盖；否则换 B 端起搜。
    * 两端都覆盖（环线 / 短车）取总长较短的那条；都不覆盖就把 A 的结果原样上报。<b>不补边、不猜。</b>
    *
    * <p>方向旗 {@code towardNode2} 一律在<b>被选中的那一端</b>的边上判定 —— 它和 {@code Route.legs[0]}
    * 是同一条边，也就和上层面标定用的是同一个参照系。
    *
    * @param endA 绑定表排序后的第一个转向架（首端）
    * @param endB 绑定表排序后的最后一个转向架（尾端）；只有一个时与 endA 相同
    */
   public static Result search(TrackGraph g, Train train, GlobalStation station, String expectName,
                               double maxDistance, TravellingPoint endA, TravellingPoint endB) {
      if (train == null) return Result.fail("参数为空");
      if (endA == null && endB == null) {
         // 绑定表是空的：只能退回 Create 自己的车头点（唯一来源），而且它会是唯一参照系
         return withStart(searchFrom(g, CreateApi.leadPoint(train), station, expectName, maxDistance),
            CreateApi.leadPoint(train));
      }

      Result rA = endA == null ? null : searchFrom(g, endA, station, expectName, maxDistance);
      Result rB = endB == null ? null : searchFrom(g, endB, station, expectName, maxDistance);

      boolean aCovers = covers(rA, endB);
      boolean bCovers = covers(rB, endA);

      if (aCovers && bCovers) {
         return rA.route.total <= rB.route.total ? withStart(rA, endA) : withStart(rB, endB);
      }

      if (aCovers) return withStart(rA, endA);
      if (bCovers) return withStart(rB, endB);
      if (rA != null && rA.ok) return withStart(rA, endA);
      if (rB != null && rB.ok) return withStart(rB, endB);
      return rA != null ? rA : (rB != null ? rB : Result.fail("两端都搜不到目标站"));
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
   /** 这次搜出来的路线，是否覆盖到另一端的边（= 起点确实在那端的上游）。 */
   /**
    * 整列车串链后从<b>车尾</b>起搜（0.67.0）。
    *
    * <p>步骤：
    * <pre>
    *   ① 任取一根转向架起搜 → 得到一条到站路线 r0
    *   ② 用 r0 的弧长 s 给所有转向架排序：最小 s = 车尾，最大 s = 车头
    *   ③ 从车尾起搜 → legs[0] 落在车尾，整列车每一根转向架脚下的边都在路线里
    *   ④ 校验车头也在这条路线上，否则拒绝出发（不猜）
    * </pre>
    *
    * <p>为什么必须从车尾：{@code Route.legs[0]} 就是起点那条边，车头起搜的话车尾那几根
    * 转向架脚下的边不在路线里，BogeySteering 的 {@code nextEdgeAfter} 会返回 null → 按直行过岔。
    */
   public static Result searchTrainWide(TrackGraph g, Train train, GlobalStation station, String expectName,
                                        double maxDistance, List<TravellingPoint> pts) {
      if (train == null) return Result.fail("参数为空");
      if (pts == null || pts.isEmpty()) {
         return withStart(searchFrom(g, CreateApi.leadPoint(train), station, expectName, maxDistance),
            CreateApi.leadPoint(train));
      }

      // ① 任取一根起搜（只用来建立 s 轴，不用它的方向）
      Result r0 = searchFrom(g, pts.get(0), station, expectName, maxDistance);
      if (!r0.ok || r0.route == null) return r0;

      // ② 按弧长串链：最小 = 车尾，最大 = 车头
      TravellingPoint tail = null; TravellingPoint head = null;
      double sMin = Double.POSITIVE_INFINITY; double sMax = Double.NEGATIVE_INFINITY;
      for (TravellingPoint p : pts) {
         if (p == null || p.edge == null) return Result.fail("有转向架没有边，不能定前后端");
         double s = r0.route.sOf(p.edge, p.position);
         if (!Double.isFinite(s)) return Result.fail("有转向架不在这条路线上，不能定前后端");
         if (s < sMin) { sMin = s; tail = p; }
         if (s > sMax) { sMax = s; head = p; }
      }
      if (tail == null) return Result.fail("串链失败：一根都没排上");

      // ③ 已经是从车尾起搜的 ⇒ 直接复用 r0
      if (tail == pts.get(0) || sMax - sMin < 0.01) return withStart(r0, tail);

      Result r1 = searchFrom(g, tail, station, expectName, maxDistance);
      if (!r1.ok || r1.route == null) return r1;

      // ④ 校验车头也覆盖到
      double sHead = r1.route.sOf(head.edge, head.position);
      if (!Double.isFinite(sHead)) {
         return Result.fail("从车尾起搜后覆盖不到车头，拒绝出发（不猜）");
      }
      return withStart(r1, tail);
   }

   private static boolean covers(Result r, TravellingPoint otherEnd) {
      if (r == null || !r.ok || r.route == null) return false;
      if (otherEnd == null || otherEnd.edge == null) return false;
      return r.route.legOf(otherEnd.edge) != null;
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
         return Result.fail("搜不到驶入站点的边（上限 " + String.format("%.0f", max) + "，扩张 " + scanned
            + " 次，可达状态 " + dist.size() + " 个，几何弹出 " + prunedSmooth + " 条 " + pruned + "）");
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