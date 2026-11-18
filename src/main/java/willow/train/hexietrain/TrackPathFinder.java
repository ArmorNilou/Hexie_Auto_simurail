package willow.train.hexietrain;

import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.signal.SignalBoundary;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Map.Entry;
import net.createmod.catnip.data.Couple;
import net.minecraft.world.phys.Vec3;

public final class TrackPathFinder {
   private TrackPathFinder() {
   }

   public static TrackPathFinder.Result shortest(TrackGraph graph, TrackNode from, TrackNode to, int maxNodes) {
      if (graph == null || from == null || to == null) {
         return null;
      } else if (from == to) {
         return new TrackPathFinder.Result(0.0, 1, List.of(from));
      } else {
         try {
            Map<TrackNode, Double> dist = new HashMap<>();
            Map<TrackNode, TrackNode> prev = new HashMap<>();
            PriorityQueue<TrackNode> pq = new PriorityQueue<>(
               (a, b) -> Double.compare(dist.getOrDefault(a, Double.MAX_VALUE), dist.getOrDefault(b, Double.MAX_VALUE))
            );
            dist.put(from, 0.0);
            pq.add(from);

            while (!pq.isEmpty() && dist.size() <= maxNodes) {
               TrackNode cur = pq.poll();
               double d = dist.getOrDefault(cur, Double.MAX_VALUE);
               if (cur == to) {
                  break;
               }

               Map<TrackNode, TrackEdge> conns = graph.getConnectionsFrom(cur);
               if (conns != null) {
                  for (Entry<TrackNode, TrackEdge> e : conns.entrySet()) {
                     TrackNode nxt = e.getKey();
                     if (allowedDirection(graph, cur, nxt)) {
                        double len = e.getValue() == null ? 0.0 : e.getValue().getLength();
                        double nd = d + Math.max(0.001, len);
                        if (nd < dist.getOrDefault(nxt, Double.MAX_VALUE) - 1.0E-6) {
                           dist.put(nxt, nd);
                           prev.put(nxt, cur);
                           pq.add(nxt);
                        }
                     }
                  }
               }
            }

            if (!dist.containsKey(to)) {
               return null;
            } else {
               ArrayDeque<TrackNode> stack = new ArrayDeque<>();

               for (TrackNode curx = to; curx != null; curx = prev.get(curx)) {
                  stack.addFirst(curx);
               }

               return new TrackPathFinder.Result(dist.get(to), stack.size(), new ArrayList<>(stack));
            }
         } catch (Throwable var18) {
            return null;
         }
      }
   }

   public static boolean allowedDirection(TrackGraph g, TrackNode from, TrackNode to) {
      try {
         if (from != null && to != null) {
            TrackNodeLocation locA = from.getLocation();
            TrackNodeLocation locB = to.getLocation();

            for (SignalBoundary p : g.getPoints(EdgePointType.SIGNAL)) {
               Couple<TrackNodeLocation> el = p.edgeLocation;
               if (el != null) {
                  boolean fwd = ((TrackNodeLocation)el.getFirst()).equals(locA) && ((TrackNodeLocation)el.getSecond()).equals(locB);
                  boolean rev = ((TrackNodeLocation)el.getFirst()).equals(locB) && ((TrackNodeLocation)el.getSecond()).equals(locA);
                  if (fwd) {
                     return true;
                  }

                  if (rev) {
                     return false;
                  }
               }
            }

            return true;
         } else {
            return true;
         }
      } catch (Throwable var10) {
         return true;
      }
   }

   public static TrackNode nearestNode(TrackGraph graph, Vec3 world) {
      if (graph != null && world != null) {
         try {
            TrackNode best = null;
            double bestD = Double.MAX_VALUE;

            for (TrackNodeLocation loc : graph.getNodes()) {
               try {
                  double d = loc.getLocation().distanceToSqr(world);
                  if (d < bestD) {
                     TrackNode n = graph.locateNode(loc);
                     if (n != null) {
                        bestD = d;
                        best = n;
                     }
                  }
               } catch (Throwable var10) {
               }
            }

            return bestD <= 1024.0 ? best : null;
         } catch (Throwable var11) {
            return null;
         }
      } else {
         return null;
      }
   }

   public static record Result(double length, int nodes, List<TrackNode> path) {
   }
}
