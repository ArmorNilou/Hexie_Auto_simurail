package willow.train.hexietrain;

import java.util.List;
import java.util.Map;

import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.signal.SignalBoundary;
import com.simibubi.create.content.trains.signal.TrackEdgePoint;
import com.simibubi.create.content.trains.station.GlobalStation;

/**
 * 第⑤步：停车判据交给 Create 原生边点监听。
 *
 * 不做任何"距离小于 X 就算到"的手写判定 —— 那正是 VIOLATIONS.md 里的 V4 / V7。
 * 做法就是 Create 自己的那套：TravellingPoint.travel(...) + IEdgePointListener。
 * 监听器为某个边点触发，就是这个边点被碰上了；站点和信号机走同一个监听器。
 *
 * 选择器只走缓存路线里的下一条边，所以探的永远是"这一段路线前方"的边点。
 */
public final class EdgePointWatch {

   private EdgePointWatch() {}

   /** 前方最近的边点。 */
   public static final class Hit {
      /** 从起点到这个边点的沿轨距离（travel 报的，不是我们算的） */
      public final double distance;
      public final TrackEdgePoint point;
      /** 监听器给的节点对：信号机查红绿要用它 */
      public final net.createmod.catnip.data.Couple<TrackNode> nodes;
      public final boolean station;

   public Hit(double distance, TrackEdgePoint point, boolean station, net.createmod.catnip.data.Couple<TrackNode> nodes) {
      this.distance = distance;
      this.point = point;
      this.station = station;
      this.nodes = nodes;
   }

      public String name() {
         return CreateApi.nameOf(this.point);
      }
   }

   /**
    * 沿着缓存路线往前探，返回第一个被监听器抓到的站点/信号机边点。
    * limit 是最大探测距离。
    */
   public static Hit probe(TrackGraph g, RailCache.Route route, TravellingPoint from, double limit) {
      if (g == null || route == null || from == null || from.edge == null) return null;
      TravellingPoint p = CreateApi.copy(from);
      if (p == null) return null;

      Hit[] found = new Hit[1];

      TravellingPoint.ITrackSelector selector = (graph, pair) -> {
         List<Map.Entry<TrackNode, TrackEdge>> cands = pair.getSecond();
         if (cands == null || cands.isEmpty()) return null;
         TrackEdge next = nextLegEdge(route, p.edge);
         if (next == null) return null;          // 路线到头了，不再往下走
         for (Map.Entry<TrackNode, TrackEdge> e : cands) {
            if (RailCache.sameLink(e.getValue(), next)) return e;
         }
         return null;
      };

      TravellingPoint.IEdgePointListener listener = (distance, pair) -> {
         TrackEdgePoint ep = pair.getFirst();
         if (ep instanceof GlobalStation || ep instanceof SignalBoundary) {
            double dv = distance == null ? Double.NaN : distance.doubleValue();
            found[0] = new Hit(dv, ep, ep instanceof GlobalStation, pair.getSecond());
            return false;   // 命中即停，不再往下走
         }
         return true;
      };

      CreateApi.travel(p, g, Math.abs(limit <= 0.0 ? 8192.0 : limit), selector, listener);
      return found[0];
   }

   private static TrackEdge nextLegEdge(RailCache.Route route, TrackEdge current) {
      List<RailCache.Leg> legs = route.legs;
      for (int i = 0; i < legs.size(); i++) {
         if (RailCache.sameLink(legs.get(i).edge, current)) {
            return i + 1 < legs.size() ? legs.get(i + 1).edge : null;
         }
      }
      return null;
   }
}
