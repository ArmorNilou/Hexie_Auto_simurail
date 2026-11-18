package willow.train.hexietrain;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.crystaelix.simurail.content.bogey.PhysicsBogeyAxle;
import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.signal.SignalBoundary;
import com.simibubi.create.content.trains.signal.SingleBlockEntityEdgePoint;
import com.simibubi.create.content.trains.signal.TrackEdgePoint;
import com.simibubi.create.content.trains.station.GlobalStation;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * 第 1 块：机械动力公开 API 的统一入口。
 *
 * 目的只有一个 —— 让上层（缓存 / 导航 / 驱动）永远不去直接摸 Create 的类名，
 * 需要什么就在这里加一个静态方法。所有方法都不带策略、不带缓存、不带状态。
 *
 * 签名全部对着 create-6.0.10.jar 的 javap 结果写，没有猜的。
 */
public final class CreateApi {

   private CreateApi() {}

   // ================================================================ 列车

   /** 按 UUID 取列车（Create 的列车表是全局的）。 */
   public static Train train(UUID id) {
      if (id == null) return null;
      try {
         return Create.RAILWAYS.trains.get(id);
      } catch (Throwable t) {
         return null;
      }
   }

   public static TrackGraph graph(Train train) {
      return train == null ? null : train.graph;
   }

   public static boolean usable(Train train) {
      return train != null && !train.derailed && !train.invalid && train.graph != null;
   }

   // ================================================================ 图

   /** 图上所有站点边点（Create 的边点注册表）。 */
   public static Collection<GlobalStation> stations(TrackGraph g) {
      if (g == null) return List.of();
      try {
         return g.getPoints(EdgePointType.STATION);
      } catch (Throwable t) {
         return List.of();
      }
   }

   /** 图上所有信号边点。 */
   public static Collection<SignalBoundary> signals(TrackGraph g) {
      if (g == null) return List.of();
      try {
         return g.getPoints(EdgePointType.SIGNAL);
      } catch (Throwable t) {
         return List.of();
      }
   }

   /**
    * 从某节点出发能接上的所有边。
    * 返回的 Map 以「另一头的节点」为键 —— 这是 Create 的约定，已用现有代码验证过。
    */
   public static Map<TrackNode, TrackEdge> connections(TrackGraph g, TrackNode node) {
      if (g == null || node == null) return null;
      try {
         return g.getConnectionsFrom(node);
      } catch (Throwable t) {
         return null;
      }
   }

   public static TrackNode node(TrackGraph g, TrackNodeLocation loc) {
      if (g == null || loc == null) return null;
      try {
         return g.locateNode(loc);
      } catch (Throwable t) {
         return null;
      }
   }

   /** 取连接 a、b 两个节点的那条边。 */
   public static TrackEdge edgeBetween(TrackGraph g, TrackNode a, TrackNode b) {
      if (g == null || a == null || b == null) return null;
      Map<TrackNode, TrackEdge> c = connections(g, a);
      if (c != null) {
         TrackEdge e = c.get(b);
         if (e != null) return e;
      }
      Map<TrackNode, TrackEdge> c2 = connections(g, b);
      return c2 == null ? null : c2.get(a);
   }

   public static TrackNode[] ends(TrackEdge e) {
      return e == null ? new TrackNode[]{null, null} : new TrackNode[]{e.node1, e.node2};
   }

   public static TrackNode opposite(TrackEdge e, TrackNode one) {
      if (e == null || one == null) return null;
      if (one == e.node1) return e.node2;
      if (one == e.node2) return e.node1;
      return null;
   }

   public static double length(TrackEdge e) {
      try {
         return e == null ? 0.0 : e.getLength();
      } catch (Throwable t) {
         return 0.0;
      }
   }

   public static boolean isTurn(TrackEdge e) {
      try {
         return e != null && e.isTurn();
      } catch (Throwable t) {
         return false;
      }
   }

   /** 沿边的朝向：fromNode1=true 表示从 node1 指向 node2。 */
   public static Vec3 direction(TrackEdge e, boolean fromNode1) {
      try {
         return e == null ? Vec3.ZERO : e.getDirection(fromNode1);
      } catch (Throwable t) {
         return Vec3.ZERO;
      }
   }

   // ================================================================ 边点

   /**
    * 边点在某条边上的位置。
    * Create 的 getLocationOn 对「不在该边上」返回非有限值，这里统一成 NaN。
    */
   /**
    * 这个边点是不是骑在 e 这条边上。
    *
    * 必须两端都对上（无序）。TrackEdgePoint.getLocationOn 本身**不做这个检查**：
    *   return isPrimary(edge.node1) ? edge.getLength() - position : position;
    * 对任何一条边都返回一个数，永不 NaN。直接拿它判"在不在该边上"会把
    * 路中间的边误判成站点边（实测：车在路中间却被报"距站 -0.2 = 已到站"）。
    */
   public static boolean hostsPoint(TrackEdge e, TrackEdgePoint ep) {
      if (e == null || ep == null || ep.edgeLocation == null) return false;
      TrackNode n1 = e.node1;
      TrackNode n2 = e.node2;
      if (n1 == null || n2 == null) return false;
      try {
         TrackNodeLocation a = ep.edgeLocation.getFirst();
         TrackNodeLocation b = ep.edgeLocation.getSecond();
         if (a == null || b == null) return false;
         TrackNodeLocation p1 = n1.getLocation();
         TrackNodeLocation p2 = n2.getLocation();
         return (a.equals(p1) && b.equals(p2)) || (a.equals(p2) && b.equals(p1));
      } catch (Throwable t) {
         return false;
      }
   }

   /**
    * 边点在 e 上的位置（从 node1 量起）。不在该边上返回 NaN。
    * 换算规则与 Create 一致：isPrimary(node1) 为真说明这条边是反向的，要翻。
    */
   public static double positionOn(TrackEdgePoint ep, TrackEdge e) {
      if (e == null || ep == null) return Double.NaN;
      if (!hostsPoint(e, ep)) return Double.NaN;
      try {
         double len = e.getLength();
         double pos = ep.isPrimary(e.node1) ? len - ep.position : ep.position;
         if (Double.isFinite(pos) && pos >= 0.0 && pos <= len) return pos;
      } catch (Throwable t) {
      }
      return Double.NaN;
   }

   /** 边点自己的两个端点节点（来自 edgeLocation，Create 的权威身份）。 */
   public static TrackNode[] nodesOf(TrackGraph g, TrackEdgePoint ep) {
      if (g == null || ep == null || ep.edgeLocation == null) return new TrackNode[]{null, null};
      TrackNode a = node(g, ep.edgeLocation.getFirst());
      TrackNode b = node(g, ep.edgeLocation.getSecond());
      return new TrackNode[]{a, b};
   }

   /** 边点所在的那条边。 */
   public static TrackEdge edgeOf(TrackGraph g, TrackEdgePoint ep) {
      TrackNode[] n = nodesOf(g, ep);
      return edgeBetween(g, n[0], n[1]);
   }

   /** 站点是否允许从该节点进入（Create 的准入方向判定）。 */
   public static boolean admitsFrom(GlobalStation st, TrackNode n) {
      if (st == null || n == null) return false;
      try {
         return st.canApproachFrom(n);
      } catch (Throwable t) {
         return false;
      }
   }

   public static boolean isPrimary(TrackEdgePoint ep, TrackNode n) {
      if (ep == null || n == null) return false;
      try {
         return ep.isPrimary(n);
      } catch (Throwable t) {
         return false;
      }
   }

   public static BlockPos blockPosOf(TrackEdgePoint ep) {
      if (ep instanceof SingleBlockEntityEdgePoint s) {
         try {
            return s.getBlockEntityPos();
         } catch (Throwable t) {
         }
      }
      return null;
   }

   public static String nameOf(TrackEdgePoint ep) {
      if (ep == null) return "-";
      if (ep instanceof GlobalStation st) return st.name;
      try {
         UUID id = ep.getId();
         return id == null ? "-" : id.toString();
      } catch (Throwable t) {
         return "-";
      }
   }

   public static boolean isStation(TrackEdgePoint ep) {
      return ep instanceof GlobalStation;
   }

   // ================================================================ 车位

   /** 车头点（第一节车厢的前端）。副本，动它不会动真车。 */
   public static TravellingPoint leadPoint(Train train) {
      try {
         if (train == null || train.carriages == null || train.carriages.isEmpty()) return null;
         return copy(train.carriages.getFirst().getLeadingPoint());
      } catch (Throwable t) {
         return null;
      }
   }

   /** 车尾点（最后一节车厢的后端）。副本。 */
   public static TravellingPoint tailPoint(Train train) {
      try {
         if (train == null || train.carriages == null || train.carriages.isEmpty()) return null;
         return copy(train.carriages.getLast().getTrailingPoint());
      } catch (Throwable t) {
         return null;
      }
   }

   /**
    * 物理转向架那根<b>真正在操舵</b>的轴（前轴）的行走点，副本。
    *
    * <p>为什么用这根轴当"车头/车尾"：BogeySteering 判定道岔用的就是它
    * （{@code bogey.getAxle(true).getTrackPoint()}）。导航起点必须落在同一根轴上，
    * 路线才会覆盖到它；差了哪怕一个轴的位置，就可能出现"路线里没有这条边 ⇒ 走直行"。
    */
   public static TravellingPoint pointOf(PhysicsBogeyBlockEntity bogey) {
      try {
         if (bogey == null || bogey.isRemoved()) return null;
         PhysicsBogeyAxle axle = bogey.getAxle(true);
         return axle == null ? null : copy(axle.getTrackPoint());
      } catch (Throwable t) {
         return null;
      }
   }

   public static TravellingPoint copy(TravellingPoint p) {
      if (p == null || p.edge == null) return null;
      try {
         return new TravellingPoint(p.node1, p.node2, p.edge, p.position, p.upsideDown);
      } catch (Throwable t) {
         return null;
      }
   }

   /** 在某条边上造一个临时行走点。position 从 node1 量起。 */
   public static TravellingPoint pointOn(TrackEdge e, double position) {
      if (e == null) return null;
      try {
         return new TravellingPoint(e.node1, e.node2, e, position, false);
      } catch (Throwable t) {
         return null;
      }
   }

   public static Vec3 worldPosition(TrackGraph g, TravellingPoint p) {
      try {
         return (g == null || p == null || p.edge == null) ? null : p.getPosition(g);
      } catch (Throwable t) {
         return null;
      }
   }

   /** 让 Create 自己推着走一段，返回实际走过的距离（可能被轨道尽头截断）。 */
   public static double travel(TravellingPoint p, TrackGraph g, double signedDistance,
                               TravellingPoint.ITrackSelector selector,
                               TravellingPoint.IEdgePointListener listener) {
      if (p == null || g == null || p.edge == null) return 0.0;
      try {
         return p.travel(g, signedDistance, selector, listener, p.ignoreTurns(), p.ignorePortals());
      } catch (Throwable t) {
         return 0.0;
      }
   }

   /** 不回头选择器：优先走一条不是当前所在边的候选。 */
   public static TravellingPoint.ITrackSelector noUturn(TravellingPoint p) {
      return (graph, pair) -> {
         List<Map.Entry<TrackNode, TrackEdge>> cands = pair.getSecond();
         if (cands == null || cands.isEmpty()) return null;
         for (Map.Entry<TrackNode, TrackEdge> e : cands) {
            if (e.getValue() != p.edge) return e;
         }
         return cands.get(0);
      };
   }

   /** 什么都不抓的边点监听器（只走路，不看边点）。 */
   public static TravellingPoint.IEdgePointListener ignoreEdgePoints() {
      return (distance, pair) -> true;
   }

   // ================================================================ 站点

   /** 按站名精确匹配找站点（时刻表 targetName 就是站名）。 */
   public static GlobalStation stationNamed(TrackGraph g, String name) {
      if (g == null || name == null || name.isEmpty()) return null;
      for (GlobalStation st : stations(g)) {
         if (st != null && name.equals(st.name)) return st;
      }
      return null;
   }

   /**
    * 站点准入端：edgeLocation 的两个端点里允许 canApproachFrom 的那个。
    * 这是 Create 对「这个站该从哪一头进」的权威回答，不是我们猜的。
    */
   public static TrackNode admissionEntryNode(TrackGraph g, GlobalStation st) {
      if (g == null || st == null || st.edgeLocation == null) return null;
      TrackNode a = node(g, st.edgeLocation.getFirst());
      if (a != null && admitsFrom(st, a)) return a;
      TrackNode b = node(g, st.edgeLocation.getSecond());
      if (b != null && admitsFrom(st, b)) return b;
      return null;
   }
}
