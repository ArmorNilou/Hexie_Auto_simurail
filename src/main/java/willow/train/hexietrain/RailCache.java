package willow.train.hexietrain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.signal.SignalBoundary;
import com.simibubi.create.content.trains.signal.TrackEdgePoint;
import com.simibubi.create.content.trains.station.GlobalStation;
import net.createmod.catnip.data.Couple;

import net.minecraft.core.BlockPos;

/**
 * 第 2 块：铁路缓存。
 *
 * 只干三件事，全部借机械动力自己的机制：
 *   ① 找站点边 —— 让 Create 的 TravellingPoint 沿轨走，捕捉 STATION 边点事件，
 *      事件给的 Couple&lt;TrackNode&gt; 就是那条边的权威身份（不用坐标去猜）。
 *   ② 找铁路边序列 —— 让 Create 的 TravellingPoint 走一遍，用 ITrackSelector 记录它选了哪些边。
 *   ③ 找沿途边点 —— 从 TrackGraph 的边点注册表里取 STATION / SIGNAL，
 *      用 TrackEdgePoint.getLocationOn(edge) 判断它在不在本段某条边上，再换算成弧长 s。
 *
 * 不做方向判定（那是步骤③）、不做驱动、不碰红石。写好了等调用。
 */
public final class RailCache {

   // ================================================================ 数据结构

   /** 站点边：由 Create 边点事件解析出来的权威身份。 */
   public static final class StationEdge {
      public final GlobalStation station;
      public final TrackEdge edge;
      public final TrackNode nodeA;
      public final TrackNode nodeB;
      /** 站点边点在 edge 上的位置（从 node1 量起），拿不到就是 NaN */
      public final double positionOnEdge;

      public StationEdge(GlobalStation station, TrackEdge edge, TrackNode nodeA, TrackNode nodeB, double positionOnEdge) {
         this.station = station;
         this.edge = edge;
         this.nodeA = nodeA;
         this.nodeB = nodeB;
         this.positionOnEdge = positionOnEdge;
      }

      public String describe() {
         return station == null ? "-" : station.name
            + " | edgeLen=" + String.format("%.1f", CreateApi.length(edge))
            + " pos=" + (Double.isFinite(positionOnEdge) ? String.format("%.1f", positionOnEdge) : "NaN");
      }
   }

   /** 路线的一段：某条边 + 它在本段里的起点弧长 + 是否反向走。 */
   public static final class Leg {
      public final TrackEdge edge;
      public final double startS;
      public final boolean reversed;

      public Leg(TrackEdge edge, double startS, boolean reversed) {
         this.edge = edge;
         this.startS = startS;
         this.reversed = reversed;
      }

      public double length() {
         return CreateApi.length(this.edge);
      }

      public double endS() {
         return this.startS + this.length();
      }

      /** 把边上位置（从 node1 量起）换算成本段的弧长 s。 */
      public double arcOf(double positionOnEdge) {
         return this.reversed ? this.startS + (this.length() - positionOnEdge) : this.startS + positionOnEdge;
      }
   }

   /** 沿途边点：弧长 s + 对象 + 所在边。 */
   public static final class Hit {
      public final double s;
      public final TrackEdgePoint point;
      public final TrackEdge edge;
      public final boolean station;

      public Hit(double s, TrackEdgePoint point, TrackEdge edge, boolean station) {
         this.s = s;
         this.point = point;
         this.edge = edge;
         this.station = station;
      }

      public String name() {
         return CreateApi.nameOf(this.point);
      }
   }

   /** 一整段路线：有向边序列 + 沿途边点 + 站点所在弧长。 */
   public static final class Route {
      public final GlobalStation target;
      public final List<Leg> legs = new ArrayList<>();
      public final List<Hit> points = new ArrayList<>();
      public double total = 0.0;
      /** 目标站在本段上的弧长；NaN = 没在这段路上找到它 */
      public double stationS = Double.NaN;

      public Route(GlobalStation target) {
         this.target = target;
      }

      public boolean ready() {
         return !this.legs.isEmpty() && Double.isFinite(this.stationS);
      }

      public Leg legOf(TrackEdge e) {
         for (Leg l : this.legs) {
            if (sameLink(l.edge, e)) return l;   // 不比较对象：正反是不同对象
         }
         return null;
      }

      /** 边上位置 → 本段弧长 s。车不在本段上返回 NaN。 */
      public double sOf(TrackEdge e, double positionOnEdge) {
         Leg l = legOf(e);
         if (l == null || e == null || !Double.isFinite(positionOnEdge)) return Double.NaN;
         // 车手上的边可能是这条轨的反向对象，position 是从它自己的 node1 量的，要翻过来
         double pos = (e.node1 == l.edge.node1) ? positionOnEdge : (l.length() - positionOnEdge);
         return l.arcOf(pos);
      }

      /**
       * 距目标站还有多远（沿本段前进方向的弧长差）。
       * 返回**带符号**：正 = 站还在前方；负 = 已经开过站了。
       * 绝不绕环 —— 过站必须能被上层看见。
       */
      public double remaining(TrackEdge e, double positionOnEdge) {
         double a = sOf(e, positionOnEdge);
         if (!Double.isFinite(a) || !Double.isFinite(this.stationS)) return Double.NaN;
         return this.stationS - a;
      }

      public boolean passed(TrackEdge e, double positionOnEdge) {
         double d = remaining(e, positionOnEdge);
         return Double.isFinite(d) && d < 0.0;
      }

      /**
       * 路线里 edge 的下一段：走到这条边尽头，该接着走哪条。
       * edge 不在本段上、或它已经是最后一段 → null（后者表示再往前就出路线了）。
       */
      public TrackEdge nextEdgeAfter(TrackEdge e) {
         for (int i = 0; i < this.legs.size(); i++) {
            if (sameLink(this.legs.get(i).edge, e)) {
               return i + 1 < this.legs.size() ? this.legs.get(i + 1).edge : null;
            }
         }
         return null;
      }

      /** 在这条 leg 里已经走了多少米（从 leg 起点算）。不在本段上 → NaN。 */
      public double posInLeg(TrackEdge e, double positionOnEdge) {
         Leg l = legOf(e);
         if (l == null) return Double.NaN;
         double s = sOf(e, positionOnEdge);
         return Double.isFinite(s) ? s - l.startS : Double.NaN;
      }

      /** 车头到这条 leg 尽头（= 下一个岔口/节点）还剩多少米。不算兜底，算不出来就是 NaN。 */
      public double remainingToLegEnd(TrackEdge e, double positionOnEdge) {
         Leg l = legOf(e);
         if (l == null) return Double.NaN;
         double used = posInLeg(e, positionOnEdge);
         return Double.isFinite(used) ? l.length() - used : Double.NaN;
      }

      /** s 之后最近的信号边点（步骤⑤用）。 */
      public Hit nextSignalAfter(double s) {
         Hit best = null;
         for (Hit h : this.points) {
            if (h.station || h.s < s) continue;
            if (best == null || h.s < best.s) best = h;
         }
         return best;
      }

      public Hit nextStationAfter(double s) {
         Hit best = null;
         for (Hit h : this.points) {
            if (!h.station || h.s < s) continue;
            if (best == null || h.s < best.s) best = h;
         }
         return best;
      }

      public String describe() {
         return (this.target == null ? "-" : this.target.name)
            + " 边数=" + this.legs.size()
            + " 总长=" + String.format("%.1f", this.total)
            + " 站点弧长=" + (Double.isFinite(this.stationS) ? String.format("%.1f", this.stationS) : "NaN")
            + " 边点=" + this.points.size();
      }
   }

   // ================================================================ 站点边缓存（图级，跨实例共享）

   private static final Map<String, StationEdge> STATION_EDGES = new ConcurrentHashMap<>();

   private static String keyOf(GlobalStation st) {
      if (st == null) return "?";
      BlockPos bp = CreateApi.blockPosOf(st);
      return st.name + "@" + (bp == null ? "?" : bp.toShortString());
   }

   public static void invalidateStation(GlobalStation st) {
      STATION_EDGES.remove(keyOf(st));
   }

   public static void invalidateAll() {
      STATION_EDGES.clear();
   }

   public static int cacheSize() {
      return STATION_EDGES.size();
   }

   /**
    * ① 找站点边：让 Create 沿轨走，等 STATION 边点事件。
    * dir &gt; 0 从车头方向探，dir &lt; 0 从车尾方向探。
    * 命中后把 Couple&lt;TrackNode&gt; 换成 TrackEdge 存起来，之后同站不再走。
    */
   public static StationEdge stationEdge(TrackGraph g, TravellingPoint from, int dir, GlobalStation st, double maxDist) {
      if (g == null || from == null || from.edge == null || st == null) return null;

      String key = keyOf(st);
      StationEdge cached = STATION_EDGES.get(key);
      if (cached != null && cached.edge != null) return cached;

      TravellingPoint p = CreateApi.copy(from);
      if (p == null) return null;

      StationEdge[] found = new StationEdge[1];
      TravellingPoint.ITrackSelector selector = CreateApi.noUturn(p);
      TravellingPoint.IEdgePointListener listener = (distance, pair) -> {
         if (pair.getFirst() instanceof GlobalStation hit && hit == st) {
            TrackNode a = pair.getSecond().getFirst();
            TrackNode b = pair.getSecond().getSecond();
            TrackEdge e = CreateApi.edgeBetween(g, a, b);
            if (e != null) {
               double pos = CreateApi.positionOn(st, e);
               found[0] = new StationEdge(st, e, a, b, pos);
            }
            return false; // 命中即停
         }
         return true;     // 继续走
      };

      double signed = Math.abs(maxDist <= 0.0 ? 6000.0 : maxDist) * (dir >= 0 ? 1.0 : -1.0);
      CreateApi.travel(p, g, signed, selector, listener);

      if (found[0] != null) {
         STATION_EDGES.put(key, found[0]);
         HexieTrain.LOGGER.info("[rail] 站点边命中 {} | {}", key, found[0].describe());
      } else {
         HexieTrain.LOGGER.warn("[rail] 站点边未命中 {} dir={} 走了={}", key, dir, String.format("%.1f", Math.abs(signed)));
      }
      return found[0];
   }
   public static Route buildRoute(TrackGraph g, GlobalStation target, List<Couple<TrackNode>> couples) {
      Route route = new Route(target);
      if (g == null || couples == null || couples.isEmpty()) return route;

      double acc = 0.0;
      for (Couple<TrackNode> c : couples) {
         if (c == null) continue;
         TrackNode in = c.getFirst();
         TrackNode out = c.getSecond();
         TrackEdge e = CreateApi.edgeBetween(g, in, out);
         if (e == null) continue;
         boolean reversed = (e.node1 != in);
         route.legs.add(new Leg(e, acc, reversed));
         acc += CreateApi.length(e);
      }
      route.total = acc;

      collectPoints(g, route);
      return route;
   }

   /**
    * 把现成的 Leg 列表装成 Route。弧长原点由调用方决定
    * （RailSearch 的约定是：原点 = 车此刻的位置）。
    */
   public static Route assemble(TrackGraph g, GlobalStation target, List<Leg> legs) {
      Route route = new Route(target);
      if (legs != null) route.legs.addAll(legs);
      double end = 0.0;
      for (Leg l : route.legs) {
         double e = l.endS();
         if (e > end) end = e;
      }
      route.total = end;
      collectPoints(g, route);
      return route;
   }

   /** 两条边是不是同一段物理轨道（节点对无序相等）。正反方向在 Create 里是两个不同对象。 */
   public static boolean sameLink(TrackEdge a, TrackEdge b) {
      if (a == null || b == null) return false;
      if (a == b) return true;
      return (a.node1 == b.node1 && a.node2 == b.node2)
          || (a.node1 == b.node2 && a.node2 == b.node1);
   }

   // ================================================================ 内部

   private static void collectPoints(TrackGraph g, Route route) {
      for (GlobalStation st : CreateApi.stations(g)) {
         addHit(route, st, true);
      }
      for (SignalBoundary sg : CreateApi.signals(g)) {
         addHit(route, sg, false);
      }
      route.points.sort((x, y) -> Double.compare(x.s, y.s));

      for (Hit h : route.points) {
         if (h.point == route.target) {
            route.stationS = h.s;
            break;
         }
      }
   }

   private static void addHit(Route route, TrackEdgePoint ep, boolean station) {
      if (ep == null) return;
      for (Leg l : route.legs) {
         double pos = CreateApi.positionOn(ep, l.edge);
         if (!Double.isFinite(pos)) continue;
         route.points.add(new Hit(l.arcOf(pos), ep, l.edge, station));
         return;
      }
   }

   // ================================================================ 实例缓存（每个调度器一份）

   private String routeKey = null;
   private Route route = null;

   public Route route() {
      return this.route;
   }

   public boolean holds(String key) {
      return key != null && key.equals(this.routeKey) && this.route != null;
   }

   public void store(String key, Route r) {
      this.routeKey = key;
      this.route = r;
   }

   public void clear() {
      this.routeKey = null;
      this.route = null;
   }

   public String key() {
      return this.routeKey;
   }
}
