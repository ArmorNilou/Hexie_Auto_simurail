package willow.train.hexietrain;

import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;
import com.crystaelix.simurail.content.bogey.PhysicsBogeyGroup;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllBogeyStyles;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageBogey;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.entity.Carriage.DimensionalCarriageEntity;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.schedule.Schedule;
import com.simibubi.create.content.trains.schedule.ScheduleItem;
import com.simibubi.create.content.trains.station.GlobalStation;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import willow.train.hexie.compat.create.CreateRouteBinding;

public final class TrainBinder {
   public static TrainBinder.PhysicalMotion motion = TrainBinder.PhysicalMotion.NONE;
   public static UUID PENDING_TRAIN_ID = null;
   private static final int SCAN_H = 12;
   private static final int SCAN_V = 8;
   public static final Set<UUID> OUR_TRAINS = ConcurrentHashMap.newKeySet();
   public static final Map<UUID, BlockPos> TRAIN_DISPATCHERS = new ConcurrentHashMap<>();
   public static final Set<UUID> PERSISTENT_LOADS = ConcurrentHashMap.newKeySet();
   public static final Map<UUID, UUID> SUBLEVEL_TRAINS = new ConcurrentHashMap<>();
   private static PhysicsBogeyBlockEntity lastFront;
   public static String anchorProbe = "n/a";
   public static double lastAnchorS = Double.NaN;
   public static double prevAnchorS = Double.NaN;
   public static CreateRouteBinding lastRoute = null;
   public static TrackGraph lastGraph = null;
   public static int anchorCalls = 0;

   public static void unregister(UUID trainId) {
      if (trainId != null) {
         OUR_TRAINS.remove(trainId);
         PERSISTENT_LOADS.remove(trainId);
         TRAIN_DISPATCHERS.remove(trainId);
         SUBLEVEL_TRAINS.values().removeIf(trainId::equals);
         Create.RAILWAYS.removeTrain(trainId);
      }
   }

   public static boolean isOurCarriage(Carriage carriage) {
      return carriage != null && carriage.train != null && OUR_TRAINS.contains(carriage.train.id);
   }

   /**
    * 最近一次绑定找到的那个转向架。
    *
    * <p><b>只读</b>：给探针/绑定表当"种子"用，调用方必须自己判 {@code isRemoved()}。
    * 这里不重扫方块（重扫要 {@code level.getBlockEntity}，是全局 ticker 的雷区）。
    */
   public static PhysicsBogeyBlockEntity lastBogey() {
      return lastFront;
   }

   private TrainBinder() {
   }

   public static TrainBinder.Result bind(ServerLevel level, BlockPos dispatcherPos, ItemStack scheduleStack, UUID ownerId) {
      return bind(level, dispatcherPos, scheduleStack, ownerId, null);
   }

   /**
    * @param explicit 显式转向架清单（绑定表 ordered() 的结果）。非空时<b>以它为准</b>造车厢，
    *                 不再用 PhysicsBogeyGroup 的 connector 链 —— 我们的转向架是故意不连的，
    *                 走 connector 链只能认出 1 根，Create 那边就会得到一节两米长的假车厢。
    */
   public static TrainBinder.Result bind(ServerLevel level, BlockPos dispatcherPos, ItemStack scheduleStack,
                                            UUID ownerId, List<PhysicsBogeyBlockEntity> explicit) {
      // >>> 原 bind 的方法体从这里开始
      UUID subLevelId = null;

      try {
         ServerSubLevel subLevel = SubLevelSuspend.containing(level, dispatcherPos);
         if (subLevel != null) {
            subLevelId = subLevel.getUniqueId();
            UUID existing = SUBLEVEL_TRAINS.get(subLevelId);
            if (existing != null) {
               Train live = (Train)Create.RAILWAYS.trains.get(existing);
               if (live != null && !live.invalid) {
                  return TrainBinder.Result.success(existing, "接管既有列车（未重建）");
               }

               HexieTrain.LOGGER.warn("[HexieTrain][bind] 旧列车 {} 已失效，注销后重建", existing);
               unregister(existing);
            }
         }
      } catch (Throwable var35) {
      }

      PhysicsBogeyBlockEntity bogey = findBogey(level, dispatcherPos);
      if (bogey == null) {
         return TrainBinder.Result.fail("附近没有物理转向架");
      } else {
         PhysicsBogeyGroup group;
         try {
            group = PhysicsBogeyGroup.createAndAssign(bogey);
         } catch (Throwable var34) {
            return TrainBinder.Result.fail("编组失败：" + var34.getClass().getSimpleName());
         }

         // 有绑定表清单就以它为准：转向架是故意不连的，connector 链认不全
         List<PhysicsBogeyBlockEntity> source = (explicit != null && !explicit.isEmpty())
            ? new ArrayList<>(explicit) : (group != null ? new ArrayList<>(group.bogeys) : new ArrayList<>());

         if (!source.isEmpty()) {
            bogey = source.get(0);
            TrackGraph graph = group.getTrackGraph();
            if (graph == null) {
               try {
                  TravellingPoint p = source.get(0).getAxle(true).getTrackPoint();
                  if (p != null && p.edge != null) {
                     for (TrackGraph g : Create.RAILWAYS.trackNetworks.values()) {
                        if (g.getNodes().contains(p.node1.getLocation()) || g.getNodes().contains(p.node2.getLocation())) {
                           graph = g;
                           break;
                        }
                     }
                  }
               } catch (Throwable var37) {
               }

               if (graph == null) {
                  return TrainBinder.Result.fail("物理结构不在轨道上（或跨图）");
               }

               HexieTrain.LOGGER.info("[HexieTrain][bind] 跨图回退：使用第一根轴所属图");
            }

            CompoundTag bogeyData = new CompoundTag();
            bogeyData.putString("BogeyStyle", AllBogeyStyles.STANDARD.id.toString());
            bogeyData.put("BogeyData", AllBogeyStyles.STANDARD.defaultData.copy());
            Map<UUID, List<PhysicsBogeyBlockEntity>> sections = new LinkedHashMap<>();

            for (PhysicsBogeyBlockEntity b : source) {
               UUID key = null;

               try {
                  SubLevel sl = Sable.HELPER.getContaining(level, b.getBlockPos());
                  if (sl != null) {
                     key = sl.getUniqueId();
                  }
               } catch (Throwable var33) {
               }

               if (key == null) {
                  key = UUID.nameUUIDFromBytes(("bogey:" + b.getBlockPos().asLong()).getBytes());
               }

               sections.computeIfAbsent(key, k -> new ArrayList<>()).add(b);
            }

            List<Carriage> carriages = new ArrayList<>();
            List<Integer> spacing = new ArrayList<>();
            Vec3 prevTail = null;

            for (List<PhysicsBogeyBlockEntity> section : sections.values()) {
               PhysicsBogeyBlockEntity firstBogey = section.get(0);
               PhysicsBogeyBlockEntity lastBogey = section.get(section.size() - 1);
               TravellingPoint f1 = copyPoint(firstBogey.getAxle(true).getTrackPoint());
               TravellingPoint f2 = copyPoint(firstBogey.getAxle(false).getTrackPoint());
               TravellingPoint b1 = copyPoint(lastBogey.getAxle(true).getTrackPoint());
               TravellingPoint b2 = copyPoint(lastBogey.getAxle(false).getTrackPoint());
               if (f1 != null && f2 != null && b1 != null && b2 != null) {
                  CarriageBogey frontBogey = new CarriageBogey((AbstractBogeyBlock)AllBlocks.SMALL_BOGEY.get(), false, bogeyData, f1, f2);
                  CarriageBogey backBogey = new CarriageBogey((AbstractBogeyBlock)AllBlocks.SMALL_BOGEY.get(), false, bogeyData, b1, b2);
                  int bogeyGap = 2;
                  Vec3 axleA = f1.getPosition(graph);
                  Vec3 axleB = f2.getPosition(graph);
                  if (axleA != null && axleB != null) {
                     int measured = (int)Math.round(axleA.distanceTo(axleB));
                     bogeyGap = Math.max(1, Math.min(32, measured));
                  }

                  Carriage carriage = new Carriage(frontBogey, backBogey, bogeyGap);
                  carriage.presentConductors.replace(v -> Boolean.TRUE);

                  try {
                     DimensionalCarriageEntity dce = carriage.getDimensional(level);
                     Vec3 head = f1.getPosition(graph);
                     Vec3 tail = b2.getPosition(graph);
                     if (head != null && tail != null) {
                        dce.rotationAnchors = Couple.create(head, tail);
                     }
                  } catch (Throwable var32) {
                  }

                  if (ModList.get().isLoaded("electroenergetics")) {
                     ElectricCompat.attach(level, dispatcherPos, carriage);
                  }

                  Vec3 headPos = f1.getPosition(graph);
                  Vec3 tailPos = b2.getPosition(graph);
                  if (prevTail != null && headPos != null) {
                     int gap = (int)Math.round(prevTail.distanceTo(headPos));
                     spacing.add(Math.max(0, Math.min(32, gap)));
                  }

                  prevTail = tailPos;
                  carriages.add(carriage);
               } else {
                  HexieTrain.LOGGER.warn("[HexieTrain][bind] 跳过一节：轨道点缺失");
               }
            }

            if (carriages.isEmpty()) {
               return TrainBinder.Result.fail("无法从物理结构构造车厢");
            } else {
               UUID trainId = PENDING_TRAIN_ID;
               if (trainId == null && subLevelId != null) {
                  trainId = TrainBindings.resolve(level, subLevelId);
               }

               if (trainId == null) {
                  trainId = UUID.randomUUID();
               }

               UUID owner = UUID.randomUUID();

               Train train;
               try {
                  train = new Train(trainId, owner, graph, carriages, spacing, true, 0);
               } catch (Throwable var31) {
                  return TrainBinder.Result.fail("建车失败：" + var31.getClass().getSimpleName());
               }

               if (scheduleStack != null && !scheduleStack.isEmpty()) {
                  try {
                     Schedule schedule = ScheduleItem.getSchedule(level.registryAccess(), scheduleStack);
                     if (schedule != null) {
                        train.runtime.setSchedule(schedule, false);
                        train.runtime.paused = false;
                        HexieTrain.LOGGER.info("[HexieTrain][bind] 时刻表已装入（已放行）");
                     }
                  } catch (Throwable var30) {
                     return TrainBinder.Result.fail("读取时刻表失败：" + var30.getClass().getSimpleName());
                  }
               }

               boolean skipRegister = false;
               Create.RAILWAYS.addTrain(train);
               if (ownerId != null) {
                  train.owner = ownerId;
               }

               OUR_TRAINS.add(trainId);
               if (subLevelId != null) {
                  SUBLEVEL_TRAINS.put(subLevelId, trainId);
               }

               TRAIN_DISPATCHERS.put(trainId, dispatcherPos.immutable());

               try {
                  for (GlobalStation st : train.graph.getPoints(EdgePointType.STATION)) {
                     TrackNode nA = train.graph.locateNode((TrackNodeLocation)st.edgeLocation.getFirst());
                     TrackNode nB = train.graph.locateNode((TrackNodeLocation)st.edgeLocation.getSecond());
                     TrackEdge edgeAB = nA != null && nB != null ? (TrackEdge)train.graph.getConnectionsFrom(nA).get(nB) : null;
                     if (edgeAB != null && !edgeAB.getEdgeData().getPoints().contains(st)) {
                        edgeAB.getEdgeData().addPoint(train.graph, st);
                     }
                  }
               } catch (Throwable var36) {
               }

               HexieTrain.LOGGER.info("[HexieTrain][bind] 完成");
               return TrainBinder.Result.success(trainId, "已注册为列车（" + group.bogeys.size() + " 个转向架）");
            }
         } else {
            return TrainBinder.Result.fail("编组为空");
         }
      }
   }

   public static double anchorError(ServerLevel level, BlockPos center, Train train) {
      try {
         if (train != null && !train.carriages.isEmpty()) {
            PhysicsBogeyBlockEntity first = lastFront;
            if (first != null && !first.isRemoved()) {
               TravellingPoint phys = first.getAxle(true).getTrackPoint();
               if (phys == null) {
                  return 0.0;
               } else {
                  Vec3 virt = ((Carriage)train.carriages.getFirst()).getLeadingPoint().getPosition(null);
                  return phys.getPosition(null).distanceTo(virt);
               }
            } else {
               return 0.0;
            }
         } else {
            return 0.0;
         }
      } catch (Throwable var61) {
         return 0.0;
      }
   }

   public static void reanchor(Train train, ServerLevel level, BlockPos center) {
      try {
         if (train == null || train.carriages.isEmpty()) {
            return;
         }

         anchorCalls++;
         PhysicsBogeyBlockEntity first = null;
         MutableBlockPos cur = new MutableBlockPos();

         label216:
         for (int dx = -12; dx <= 12; dx++) {
            for (int dy = -8; dy <= 8; dy++) {
               for (int dz = -12; dz <= 12; dz++) {
                  cur.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                  if (level.getBlockEntity(cur) instanceof PhysicsBogeyBlockEntity bg) {
                     first = bg;
                     break label216;
                  }
               }
            }
         }

         if (first == null) {
            anchorProbe = "未找到转向架";
            return;
         }

         // 给探针 / 绑定表留一个种子。这里本来就每 tick 都在扫，不额外增加任何开销。
         lastFront = first;

         PhysicsBogeyGroup group = null;

         try {
            group = first.getGroup();
         } catch (Throwable var24) {
         }

         if (group == null || group.bogeys.isEmpty()) {
            anchorProbe = "编组为空";
            return;
         }

         TravellingPoint pLead = null;
         TravellingPoint pTail = null;

         try {
            pLead = group.getLeadingAxle() == null ? null : group.getLeadingAxle().getTrackPoint();
         } catch (Throwable var23) {
         }

         try {
            pTail = group.getTrailingAxle() == null ? null : group.getTrailingAxle().getTrackPoint();
         } catch (Throwable var22) {
         }

         if (pLead == null) {
            anchorProbe = "首轴点为空";
            return;
         }

         if (pLead.edge == null) {
            anchorProbe = "首轴无边";
            return;
         }

         TrackGraph graph = null;

         for (TrackGraph g : Create.RAILWAYS.trackNetworks.values()) {
            if (g.locateNode(pLead.node1.getLocation()) != null) {
               graph = g;
               break;
            }
         }

         if (graph == null) {
            anchorProbe = "图未找到";
            return;
         }

         CreateRouteBinding route = CreateRouteBinding.fromPoints(level, graph, pLead, pTail != null ? List.of(pTail) : List.of());
         if (route == null) {
            anchorProbe = "route=null";
            return;
         }

         double sLead = route.distanceOf(pLead);
         double sTail = pTail != null ? route.distanceOf(pTail) : sLead;
         if (sTail > sLead) {
            double tmp = sLead;
            sLead = sTail;
            sTail = tmp;
         }

         for (Carriage car : train.carriages) {
            TravellingPoint lp = route.boundedPointAt(sLead);
            TravellingPoint tp = route.boundedPointAt(sTail);
            if (lp != null) {
               copyPointInto(lp, car.getLeadingPoint());
            }

            if (tp != null) {
               copyPointInto(tp, car.getTrailingPoint());
            }
         }

         prevAnchorS = lastAnchorS;
         lastAnchorS = sLead;

         try {
            if (Double.isFinite(sLead) && Double.isFinite(lastAnchorS) && !Double.isNaN(prevAnchorS)) {
               double v = (sLead - prevAnchorS) * 20.0;
               Vec3 leadW = null;
               Vec3 tailW = null;

               try {
                  leadW = pLead == null ? null : pointWorld(pLead);
               } catch (Throwable var21) {
               }

               try {
                  tailW = pTail == null ? null : pointWorld(pTail);
               } catch (Throwable var20) {
               }

               Vec3 carCenter = leadW != null && tailW != null ? leadW.add(tailW).scale(0.5) : (leadW != null ? leadW : Vec3.ZERO);
               Vec3 heading = leadW != null && tailW != null && leadW.distanceToSqr(tailW) > 1.0E-6
                  ? leadW.subtract(tailW).normalize()
                  : new Vec3(0.0, 0.0, 1.0);
               motion = new TrainBinder.PhysicalMotion(carCenter, heading, v, sLead, sTail);
            }
         } catch (Throwable var25) {
         }

         lastRoute = route;
         lastGraph = graph;
         anchorProbe = "sLead=" + String.format("%.2f", sLead) + " sTail=" + String.format("%.2f", sTail);
      } catch (Throwable var261) {
         HexieTrain.LOGGER.warn("[HexieTrain][anchor] {}", var261.toString());
      }
   }

   private static void copyPointInto(TravellingPoint src, TravellingPoint dst) {
      if (src != null && dst != null) {
         dst.edge = src.edge;
         dst.node1 = src.node1;
         dst.node2 = src.node2;
         dst.position = src.position;
         dst.upsideDown = src.upsideDown;
         dst.blocked = false;
      }
   }

   private static void setPoint(TravellingPoint dst, TravellingPoint src, double pos, double len) {
      if (dst != null) {
         if (dst.edge == null) {
            dst.edge = src.edge;
            dst.node1 = src.edge.node1;
            dst.node2 = src.edge.node2;
            dst.upsideDown = src.upsideDown;
         }

         double l = dst.edge != null ? dst.edge.getLength() : len;
         dst.position = Math.max(0.0, Math.min(Math.max(0.0, l), pos));
         dst.blocked = false;
      }
   }

   private static void syncPoint(TravellingPoint src, TravellingPoint dst) {
      if (src != null && dst != null && dst.edge != null && src.edge != null) {
         try {
            Vec3 world = src.getPosition(null);
            TrackEdge edge = dst.edge;
            int samples = 64;
            double bestT = -1.0;
            double bestD = Double.MAX_VALUE;

            for (int i = 0; i <= samples; i++) {
               double t = (double)i / (double)samples;
               double d = edge.getPosition(null, t).distanceToSqr(world);
               if (d < bestD) {
                  bestD = d;
                  bestT = t;
               }
            }

            if (bestT < 0.0) {
               return;
            }

            if (bestD > 9.0) {
               return;
            }

            dst.position = bestT * edge.getLength();
         } catch (Throwable var14) {
         }
      }
   }

   private static void copyInto(TravellingPoint src, TravellingPoint dst) {
      if (dst != null) {
         dst.node1 = src.node1;
         dst.node2 = src.node2;
         dst.edge = src.edge;
         dst.position = src.position;
         dst.upsideDown = src.upsideDown;
         dst.blocked = src.blocked;
      }
   }

   private static TravellingPoint copyPoint(TravellingPoint src) {
      TrackNode n1 = src.node1;
      TrackNode n2 = src.node2;
      return new TravellingPoint(n1, n2, src.edge, src.position, src.upsideDown);
   }

   public static PhysicsBogeyGroup groupAt(ServerLevel level, BlockPos pos) {
      try {
         PhysicsBogeyBlockEntity bogey = findBogey(level, pos);
         return bogey == null ? null : PhysicsBogeyGroup.createAndAssign(bogey);
      } catch (Throwable var31) {
         return null;
      }
   }

   private static PhysicsBogeyBlockEntity findBogey(ServerLevel level, BlockPos center) {
      MutableBlockPos cursor = new MutableBlockPos();

      for (int dx = -12; dx <= 12; dx++) {
         for (int dy = -8; dy <= 8; dy++) {
            for (int dz = -12; dz <= 12; dz++) {
               cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
               BlockEntity be = level.getBlockEntity(cursor);
               if (be instanceof PhysicsBogeyBlockEntity) {
                  return (PhysicsBogeyBlockEntity)be;
               }
            }
         }
      }

      return null;
   }

   private static Vec3 pointWorld(TravellingPoint point) {
      TrackEdge edge = point.edge;
      if (edge == null) {
         return null;
      } else {
         Vec3 a = edge.node1.getLocation().getLocation();
         Vec3 b = edge.node2.getLocation().getLocation();
         double len = Math.max(1.0E-6, edge.getLength());
         double t = Math.max(0.0, Math.min(1.0, point.position / len));
         return a.add(b.subtract(a).scale(t));
      }
   }

   public static Train trainOfNavigation(Object navigation) {
      try {
         for (Train tr : Create.RAILWAYS.trains.values()) {
            if (tr != null && tr.navigation == navigation) {
               return tr;
            }
         }
      } catch (Throwable var3) {
      }

      return null;
   }

   public static record PhysicalMotion(Vec3 center, Vec3 heading, double speed, double sLead, double sTail) {
      public static final TrainBinder.PhysicalMotion NONE = new TrainBinder.PhysicalMotion(Vec3.ZERO, new Vec3(0.0, 0.0, 1.0), 0.0, Double.NaN, Double.NaN);
   }

   public static record Result(boolean ok, UUID trainId, String message) {
      static TrainBinder.Result fail(String why) {
         return new TrainBinder.Result(false, null, why);
      }

      static TrainBinder.Result success(UUID id, String msg) {
         return new TrainBinder.Result(true, id, msg);
      }
   }
}