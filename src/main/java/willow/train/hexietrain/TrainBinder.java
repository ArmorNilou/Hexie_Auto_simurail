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

public final class TrainBinder {
   public static UUID PENDING_TRAIN_ID = null;
   private static final int SCAN_H = 12;
   private static final int SCAN_V = 8;
   public static final Set<UUID> OUR_TRAINS = ConcurrentHashMap.newKeySet();
   public static final Map<UUID, BlockPos> TRAIN_DISPATCHERS = new ConcurrentHashMap<>();
   public static final Set<UUID> PERSISTENT_LOADS = ConcurrentHashMap.newKeySet();
   public static final Map<UUID, UUID> SUBLEVEL_TRAINS = new ConcurrentHashMap<>();

   /**
    * 每辆虚拟车登记的物理结构：外层 = 车厢（前→后），内层 = 该车厢的物理转向架方块坐标（前→后）。
    *
    * <p><b>为什么非要有这张表</b>：虚拟车的每个端点都必须抄物理轴<b>当 tick</b> 的位置。
    * 没有这张表就只能每 tick 扫 25×17×25 个方块去"找"转向架（旧实现就是这么干的），
    * 既慢又会把不相干的转向架认成自己的。这里在 {@code bind} 时一次性记死，
    * 之后 {@code reanchor} 只做 O(转向架数) 的查表。
    *
    * <p><b>为什么存 BlockPos 而不是 BlockEntity 引用</b>：方块实体可能被卸载重载，
    * 拿着旧引用的结果就是"抄了一个已经不在世界里的轴"。存坐标、用时再取，
    * 取不到就整次不写（宁可这 tick 不动，绝不只写半列车）。这是内存态，
    * 世界重载后由 {@code adoptStructure()} 重新登记。
    */
   private static final Map<UUID, List<List<BlockPos>>> STRUCTURE = new ConcurrentHashMap<>();

   /** 上一 tick 各车自己的中心点：算真实速度用。绝不能用全局静态量（多车会互相污染）。 */
   private static final Map<UUID, Vec3> LAST_CENTER = new ConcurrentHashMap<>();

   /** 各车的实测物理运动。真实速度的唯一来源（{@code train.speed} 恒为 0，不能再用它上报）。 */
   private static final Map<UUID, TrainBinder.PhysicalMotion> MOTION = new ConcurrentHashMap<>();

   /**
    * 客户端侧的列车名缓存：{@code trainId → 名字}。
    *
    * <p><b>为什么需要</b>：{@code Train} 是纯服务端对象（没有任何 send/sync 方法），
    * 客户端那份 {@code Train} 永远停在构造时的默认名 {@code create.train.unnamed}「未命名列车」。
    * 而 {@code CarriageContraptionEntity.getDisplayName()} 读的正是 {@code carriage.train.name}，
    * 所以 Xaero 小地图这类按实体名显示的地方永远显示「未命名列车」（2026-11-22 用户实测）。
    *
    * <p>填充途径不新增网络包：调度器 BlockEntity 的同步包里带上名字
    * （见 {@code TrainDispatcherBlockEntity.write/read} 的 {@code clientPacket} 分支），
    * 客户端 BE 读到就登记到这里，再由 {@code CarriageNameMixin} 取用。
    */
   private static final Map<UUID, String> CLIENT_NAMES = new ConcurrentHashMap<>();

   /** 客户端拿某个车的名字（没登记过返回 null）。 */
   public static String clientNameOf(UUID trainId) {
      return trainId == null ? null : CLIENT_NAMES.get(trainId);
   }

   /** 客户端登记名字（由 BE 的同步包读到 NBT 后调用）。 */
   public static void putClientName(UUID trainId, String name) {
      if (trainId == null) {
         return;
      }
      if (name == null || name.isEmpty()) {
         CLIENT_NAMES.remove(trainId);
      } else {
         CLIENT_NAMES.put(trainId, name);
      }
   }

   private static PhysicsBogeyBlockEntity lastFront;
   public static String anchorProbe = "n/a";
   public static int anchorCalls = 0;

   public static void unregister(UUID trainId) {
      if (trainId != null) {
         OUR_TRAINS.remove(trainId);
         PERSISTENT_LOADS.remove(trainId);
         TRAIN_DISPATCHERS.remove(trainId);
         SUBLEVEL_TRAINS.values().removeIf(trainId::equals);
         STRUCTURE.remove(trainId);
         LAST_CENTER.remove(trainId);
         MOTION.remove(trainId);
         // 必须走 purge 而不是 Create.RAILWAYS.removeTrain：map 里已经没有这个 id 时
         // removeTrain 会直接 return，两个 list 里的残影一个都摘不掉。
         purgeVirtualTrain(trainId);
      }
   }

   /**
    * 把某个 id 的虚拟列车从 Create 的三个表里彻底摘掉。
    *
    * <p><b>为什么必须做</b>（都对 6.0.10 字节码核过）：
    * <pre>
    *   addTrain(t):      trains.put(t.id, t);  movingTrains.add(t);
    *   removeTrain(id):  Train t = trains.remove(id);  if (t == null) return;
    *                     movingTrains.remove(t);  waitingTrains.remove(t);
    * </pre>
    * 也就是说 <b>map 一旦被同 id 的新对象覆盖，旧对象就再没有任何人摘得掉</b>。
    * 旧对象留在 {@code movingTrains} 里会继续被 {@code earlyTick}，继续按它冻住的
    * {@code occupiedSignalBlocks} 往 {@code SignalEdgeGroup.trains} 里报名 ——
    * 于是它<b>永久占着几段区间，堵住所有人的车</b>（不只是堵我们自己）。
    * 所以「判定时排除本车」治不了这个病，只能在源头把它摘干净。
    *
    * <p>（{@code SignalEdgeGroup.trains} 本身每 tick 会被
    * {@code GlobalRailwayManager.tick} 清空重建，所以「残影在 trains 集合里」不是问题；
    * 问题是残影还在被 tick、还在往集合里报名。）
    *
    * @return 摘掉的个数（≥2 就说明确实有残影，日志会留证）
    */
   public static int purgeVirtualTrain(UUID trainId) {
      if (trainId == null) {
         return 0;
      }
      int removed = 0;
      try {
         if (Create.RAILWAYS == null) {
            return 0;
         }
         Train live = (Train)Create.RAILWAYS.trains.get(trainId);
         if (live != null) {
            clearOccupancy(live);
            Create.RAILWAYS.removeTrain(trainId);
            removed++;
         }
         for (List<Train> list : railwayTrainLists()) {
            java.util.Iterator<Train> it = list.iterator();
            while (it.hasNext()) {
               Train t = it.next();
               if (t != null && trainId.equals(t.id)) {
                  clearOccupancy(t);
                  it.remove();
                  removed++;
               }
            }
         }
      } catch (Throwable t) {
         HexieTrain.LOGGER.warn("[reap] 摘除虚拟列车 {} 失败：{}", trainId, t.toString());
      }
      return removed;
   }

   /** 残影就算还挂在某个 list 里，只要它不占区间就害不到别人。 */
   private static void clearOccupancy(Train t) {
      try {
         if (t.occupiedSignalBlocks != null) {
            t.occupiedSignalBlocks.clear();
         }
         if (t.reservedSignalBlocks != null) {
            t.reservedSignalBlocks.clear();
         }
      } catch (Throwable ignored) {
      }
   }

   private static java.lang.reflect.Field MOVING_FIELD = null;
   private static java.lang.reflect.Field WAITING_FIELD = null;
   private static boolean listFieldsResolved = false;

   /**
    * {@code movingTrains} / {@code waitingTrains} 是 {@code GlobalRailwayManager} 的 private 字段，
    * 只能反射。取不到就返回空表 —— 扫不到残影而已，判定语义一点不受影响。
    */
   @SuppressWarnings("unchecked")
   private static List<List<Train>> railwayTrainLists() {
      List<List<Train>> out = new ArrayList<>(2);
      try {
         if (!listFieldsResolved) {
            listFieldsResolved = true;
            java.lang.reflect.Field m = com.simibubi.create.content.trains.GlobalRailwayManager.class.getDeclaredField("movingTrains");
            m.setAccessible(true);
            MOVING_FIELD = m;
            java.lang.reflect.Field w = com.simibubi.create.content.trains.GlobalRailwayManager.class.getDeclaredField("waitingTrains");
            w.setAccessible(true);
            WAITING_FIELD = w;
         }
         Object rm = Create.RAILWAYS;
         if (rm != null && MOVING_FIELD != null && MOVING_FIELD.get(rm) instanceof List<?> l) {
            out.add((List<Train>)l);
         }
         if (rm != null && WAITING_FIELD != null && WAITING_FIELD.get(rm) instanceof List<?> l) {
            out.add((List<Train>)l);
         }
      } catch (Throwable t) {
         HexieTrain.LOGGER.warn("[reap] 取 movingTrains/waitingTrains 失败（只是扫不到残影，判定不受影响）：{}", t.toString());
      }
      return out;
   }

   /**
    * 定期清扫：我们自己的 id 只允许有一个对象活着（= {@code RAILWAYS.trains} 里那个）。
    * list 里同 id 的其它对象一律摘掉并清空它们的占区表，日志留证。
    */
   public static void sweepGhostTrains() {
      if (OUR_TRAINS.isEmpty()) {
         return;
      }
      try {
         List<List<Train>> lists = railwayTrainLists();
         if (lists.isEmpty()) {
            return;
         }
         for (UUID id : List.copyOf(OUR_TRAINS)) {
            try {
               Train live = Create.RAILWAYS == null ? null : (Train)Create.RAILWAYS.trains.get(id);
               for (List<Train> list : lists) {
                  java.util.Iterator<Train> it = list.iterator();
                  while (it.hasNext()) {
                     Train t = it.next();
                     if (t == null || t == live || !id.equals(t.id)) {
                        continue;
                     }
                     clearOccupancy(t);
                     it.remove();
                     HexieTrain.LOGGER.warn(
                        "[reap] 摘掉残影虚拟列车 {}（不在 RAILWAYS.trains 里却还在被 tick ⇒ 会永久占区间堵别人的车）", t.id);
                  }
               }
            } catch (Throwable ignored) {
            }
         }
      } catch (Throwable t) {
         HexieTrain.LOGGER.warn("[reap] 清扫异常 {}", t.toString());
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
      UUID subLevelId = null;

      try {
         ServerSubLevel subLevel = SubLevelSuspend.containing(level, dispatcherPos);
         if (subLevel != null) {
            subLevelId = subLevel.getUniqueId();
         }
      } catch (Throwable var35) {
      }

      PhysicsBogeyBlockEntity bogey = findBogey(level, dispatcherPos);
      if (bogey == null) {
         return TrainBinder.Result.fail("附近没有物理转向架");
      }

      // 接管：世界重载后 Create.RAILWAYS 里还留着这辆车，但我们的结构登记表是内存态、
      // 已经空了。只补登记结构、绝不动车；登记不出来或与车厢数不符就往下走重建 ——
      // 绝不带着空结构继续跑（那样 reanchor 会整次不写，车就冻在原地）。
      if (subLevelId != null) {
         UUID existing = SUBLEVEL_TRAINS.get(subLevelId);
         if (existing != null) {
            Train live = (Train)Create.RAILWAYS.trains.get(existing);
            if (live != null && !live.invalid && adoptStructure(level, existing, explicit, bogey)) {
               return TrainBinder.Result.success(existing, "接管既有列车（未重建）");
            }

            HexieTrain.LOGGER.warn("[HexieTrain][bind] 旧列车 {} 无法接管（失效或结构对不上），注销后重建", shortId(existing));
            unregister(existing);
         }
      }

      {
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

            // 世界主轴顺序 ≠ 沿轨前后顺序，先串成链再分组：
            // 这样"车厢顺序"和"车厢内转向架顺序"都是物理前后序，
            // reanchor 判"哪根轴在前"才有一致的方向（占区沿轨走的方向就靠它）。
            Map<UUID, List<PhysicsBogeyBlockEntity>> sections = splitSections(level, chainOrder(source));

            List<Carriage> carriages = new ArrayList<>();
            List<Integer> spacing = new ArrayList<>();
            List<List<BlockPos>> struct = new ArrayList<>();
            PhysicsBogeyBlockEntity prevLastBogey = null;

            for (List<PhysicsBogeyBlockEntity> section : sections.values()) {
               List<PhysicsBogeyBlockEntity> usable = new ArrayList<>(section.size());
               for (PhysicsBogeyBlockEntity b : section) {
                  if (hasTrackPoint(b)) {
                     usable.add(b);
                  }
               }

               if (usable.isEmpty()) {
                  HexieTrain.LOGGER.warn("[HexieTrain][bind] 跳过一节：轨道点缺失");
                  continue;
               }

               PhysicsBogeyBlockEntity fb = usable.get(0);
               PhysicsBogeyBlockEntity lb = usable.get(usable.size() - 1);
               CarriageBogey lead = newBogey(fb);
               CarriageBogey trail = usable.size() >= 2 ? newBogey(lb) : null;
               if (lead == null || usable.size() >= 2 && trail == null) {
                  HexieTrain.LOGGER.warn("[HexieTrain][bind] 跳过一节：构造转向架失败");
                  continue;
               }

               // bogeySpacing 的语义（对 Train.forEachTravellingPointBackwards 字节码核过）是
               // **这节车前后两根转向架中心之间的距离**，不是同一根转向架两根轴的距离。
               // 以前量的是同一根轴的两个端点（实测 2.00 m），于是整列车在图上只有 2~4 m：
               // Create 每 tick 判一次撞车（[crash] 各 794 次），占区也跟着错。
               int bogeyGap = trail == null ? 0 : Math.max(1, Math.min(64, centerGap(fb, lb)));

               Carriage carriage = new Carriage(lead, trail, bogeyGap);
               carriage.presentConductors.replace(v -> Boolean.TRUE);

               try {
                  DimensionalCarriageEntity dce = carriage.getDimensional(level);
                  Vec3 head = pointWorld(fb.getAxle(true).getTrackPoint());
                  Vec3 tail = pointWorld(lb.getAxle(false).getTrackPoint());
                  if (head != null && tail != null) {
                     dce.rotationAnchors = Couple.create(head, tail);
                  }
               } catch (Throwable var32) {
               }

               if (ModList.get().isLoaded("electroenergetics")) {
                  ElectricCompat.attach(level, dispatcherPos, carriage);
               }

               // Train.carriageSpacing[i] 的语义：第 i 节的后转向架中心 ↔ 第 i+1 节的前转向架中心
               if (prevLastBogey != null) {
                  spacing.add(Math.max(0, Math.min(64, centerGap(prevLastBogey, fb))));
               }
               prevLastBogey = lb;

               List<BlockPos> registered = new ArrayList<>(usable.size());
               for (PhysicsBogeyBlockEntity b : usable) {
                  registered.add(b.getBlockPos().immutable());
               }
               struct.add(registered);
               carriages.add(carriage);
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

               // 结构登记必须和 carriages 一一对应：reanchor 靠它逐节抄点，
               // 数量对不上就整次不写（宁可不写，也不要只写半列车）。
               STRUCTURE.put(trainId, struct);

               // 把"到底几节车、每节几根转向架、多长"打成一行 —— 下一步全靠这条日志定位，
               // 不要再靠猜。spacing/length 单位都是米（Create 自己就是这么存的 int）。
               StringBuilder detail = new StringBuilder();
               int totalBogey = 0;
               for (int i = 0; i < struct.size(); i++) {
                  List<BlockPos> sec = struct.get(i);
                  totalBogey += sec.size();
                  if (i > 0) {
                     detail.append(" | ");
                  }
                  detail.append("第").append(i + 1).append("节: 转向架").append(sec.size())
                     .append(" 间距=").append(carriages.get(i).bogeySpacing).append("m");
               }
               HexieTrain.LOGGER.info("[HexieTrain][bind] 结构：车厢={} 转向架={} spacing={} {}", carriages.size(), totalBogey, spacing, detail);

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
               // 登记前先摘掉同 id 的旧对象：addTrain 只是 put + list.add，map 被覆盖之后
               // 旧对象就再也没人摘得掉，它会永久占着信号区间、堵住所有人的车。
               int ghosts = purgeVirtualTrain(trainId);
               if (ghosts > 0) {
                  HexieTrain.LOGGER.warn("[HexieTrain][bind] 登记前摘掉同 id 旧对象 {} 个（id={}）", ghosts, trainId);
               }
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

   /**
    * 把虚拟车每一节车厢、每一根转向架的两个端点，直接抄成物理轴<b>当 tick</b> 的位置。
    *
    * <p><b>为什么必须是"抄"而不是"积分"</b>（对 Create 6.0.10 字节码核过）：
    * 占区计算是 {@code Train.collectInitiallyOccupiedSignalBlocks()} 起手取
    * "最后一节车的 trailing 点"，然后 {@code Train.forEachTravellingPointBackwards()}
    * <b>逐节车、逐根转向架</b>读它自己的 {@code points.getFirst()/getSecond()}，
    * 再用 {@code Carriage.bogeySpacing}、{@code Train.carriageSpacing}、
    * {@code AbstractBogeyBlock.getWheelPointSpacing()} 当作"从这一点沿轨再走多远就到下一个点"
    * 喂给 {@code TravellingPoint.travel(graph, distance, ...)}。
    * 也就是说<b>位置完全由这些点决定，速度根本不参与</b>。
    * 于是虚拟车只要每 tick 把点抄准，位置误差在结构上恒等于 0 ——
    * "虚拟车速度和物理车绝对相等"这个要求<b>不需要去满足，因为它不需要存在</b>。
    *
    * <p><b>旧实现错在哪</b>：走的是 {@code CreateRouteBinding.fromPoints(anchor, [tail])}
    * 再 {@code boundedPointAt(sLead/sTail)} 投影，三个后果都实测到了：
    * <ol>
    *   <li>路线一失效就抛 {@code RouteInvalidException}，整次调用在写点之前就跳出，
    *       <b>一个点都没写</b>（1.1.26 日志里 136 次，只有异常、没有更新）；</li>
    *   <li>所有车厢被塞进<b>同一对</b> sLead/sTail，两节车叠在同一个位置；</li>
    *   <li>每 tick 扫 25×17×25 个方块找转向架（一万多次 getBlockEntity）。</li>
    * </ol>
    * 三件事现在全部消失：查表取坐标、逐节抄、一次 O(转向架数)。
    *
    * <p><b>失败策略</b>：任何一节车的任何一个转向架取不到（区块没加载、方块被拆、结构没登记、
    * 结构与车厢数不符），<b>整次不写</b>并把原因放进 {@link #anchorProbe}。
    * 宁可这一 tick 保持原位，也绝不写半列车 —— 写半列会让占区凭空缩短，后果是放后车进闭锁区间。
    */
   public static void reanchor(Train train, ServerLevel level, BlockPos center) {
      if (train == null || train.carriages.isEmpty()) {
         return;
      }

      try {
         List<List<BlockPos>> struct = STRUCTURE.get(train.id);
         if (struct == null) {
            anchorProbe = "结构未登记";
            return;
         }

         if (struct.size() != train.carriages.size()) {
            anchorProbe = "结构" + struct.size() + "节≠车厢" + train.carriages.size() + "节";
            return;
         }

         // 1) 解析全车转向架（前→后）
         List<List<PhysicsBogeyBlockEntity>> resolved = new ArrayList<>(struct.size());
         List<PhysicsBogeyBlockEntity> flat = new ArrayList<>();

         for (List<BlockPos> section : struct) {
            List<PhysicsBogeyBlockEntity> list = new ArrayList<>(section.size());
            for (BlockPos pos : section) {
               PhysicsBogeyBlockEntity found = bogeyAt(level, pos);
               if (found == null) {
                  anchorProbe = "转向架取不到 " + pos.toShortString();
                  return;
               }
               list.add(found);
            }
            resolved.add(list);
            flat.addAll(list);
         }

         if (flat.isEmpty()) {
            anchorProbe = "结构为空";
            return;
         }

         lastFront = flat.get(0);
         anchorCalls++;

         // 2) 行进方向：最前面两根转向架的中心连线。相邻、一定非零、任何弯道上都可靠；
         //    用它判断每根转向架"哪根轴在前"，比去猜 Simurail 的 reverseStates 稳。
         Vec3 dir = null;
         if (flat.size() >= 2) {
            Vec3 a = bogeyCenter(flat.get(0));
            Vec3 b = bogeyCenter(flat.get(1));
            if (a != null && b != null) {
               Vec3 delta = a.subtract(b);
               if (delta.lengthSqr() > 1.0E-6) {
                  dir = delta.normalize();
               }
            }
         }

         // 3) 逐车厢抄点
         int writtenCarriages = 0;
         for (int i = 0; i < resolved.size(); i++) {
            Carriage carriage = train.carriages.get(i);
            List<PhysicsBogeyBlockEntity> section = resolved.get(i);
            CarriageBogey lead = carriage.bogeys.getFirst();
            CarriageBogey trail = carriage.bogeys.getSecond();
            boolean okLead = writeBogeyPoints(lead, section.get(0), dir);
            boolean okTrail = trail == null || section.size() < 2
               || writeBogeyPoints(trail, section.get(section.size() - 1), dir);
            if (okLead && okTrail) {
               writtenCarriages++;
            } else {
               // 某一节这一拍写不进去（轴点 edge 瞬时为 null，过道岔/变道时会看到）。
               // 它保留**上一拍的点**，于是这一拍的占区变成"旧点..新点"，只会变长不会变短 ——
               // 是安全侧（不会提前放后车进来），所以允许这一节跳过、其余照写。
               // 2026-11-22 13:54~14:00 实测：39 拍里 2 拍出现"车厢1/2"，下一拍即恢复。
               if (++this_skipLogs % 200 == 1) {
                  HexieTrain.LOGGER.warn("[HexieTrain][anchor] 第 {} 节这一拍写不进去（占区只会变长，安全侧）车={}",
                     i + 1, shortId(train.id));
               }
            }
         }

         // 4) 真实速度（每车独立，绝不用全局静态量）：中心位移 × 20
         Vec3 centerNow = bogeyCenter(flat.get(0));
         if (centerNow != null) {
            Vec3 previous = LAST_CENTER.put(train.id, centerNow);
            double measured = previous == null ? 0.0 : centerNow.distanceTo(previous) * 20.0;
            MOTION.put(train.id, new TrainBinder.PhysicalMotion(
               centerNow, dir == null ? new Vec3(0.0, 0.0, 1.0) : dir, measured, Double.NaN, Double.NaN));
         }

         anchorProbe = "车厢" + writtenCarriages + "/" + resolved.size() + " 转向架" + flat.size();
      } catch (Throwable t) {
         HexieTrain.LOGGER.warn("[HexieTrain][anchor] {}", t.toString());
      }
   }

   /** 上次跟人说"哪节没被登记"的计数（只用来限流日志）。 */
   private static volatile int this_skipLogs = 0;

   /** 把一根物理转向架的两个轴抄进虚拟车的一个 CarriageBogey。 */
   private static boolean writeBogeyPoints(CarriageBogey bogey, PhysicsBogeyBlockEntity physical, Vec3 forward) {
      if (bogey == null || physical == null) {
         return false;
      }

      try {
         TravellingPoint front = physical.getAxle(true).getTrackPoint();
         TravellingPoint back = physical.getAxle(false).getTrackPoint();
         if (front == null || back == null || front.edge == null || back.edge == null) {
            return false;
         }

         TravellingPoint lead = front;
         TravellingPoint trail = back;

         // 哪根轴在前由几何决定：两根轴在行进方向上的投影谁大谁在前。
         if (forward != null) {
            Vec3 a = pointWorld(front);
            Vec3 b = pointWorld(back);
            if (a != null && b != null && a.subtract(b).dot(forward) < 0.0) {
               lead = back;
               trail = front;
            }
         }

         copyPointInto(lead, bogey.leading());
         copyPointInto(trail, bogey.trailing());
         return true;
      } catch (Throwable t) {
         return false;
      }
   }

   /**
    * 把一组转向架按"最近邻"串成一条链，让返回顺序在物理上<b>前后相连且方向一致</b>。
    *
    * <p><b>为什么需要它</b>：{@code rosterBogeys()} 的顺序是 {@code BogeyRoster.ordered()}，
    * 那是<b>按世界主轴排序</b>，不是沿轨顺序。而 Create 算占区时是
    * {@code forEachTravellingPointBackwards()} —— 从车尾那个点出发，<b>逐点沿轨走
    * {@code distance} 米</b>去撞信号边界。也就是说"这两个点之间朝哪个方向走 20 米"直接决定
    * 占的是哪 20 米：
    * <ul>
    *   <li>方向一致 ⇒ 走的就是本车实际占的那段，正确；</li>
    *   <li>方向反了 ⇒ 走到别的 20 米上，<b>本车该占的那段反而空着</b> ⇒ 后车开进来。</li>
    * </ul>
    * 世界主轴排序在直线轨道上恰好等于前后序，在 S 弯 / 斜线上就不是了，所以不能拿它当准。
    *
    * <p>最近邻串链不需要任何路线投影：同一节车的转向架间距（十几米）远大于同一根转向架的
    * 两轴间距（两米），最近邻一定落在相邻转向架上。量不出中心（轴点缺失）就<b>保持原序不动</b>，
    * 不做任何推测性连接。
    */
   private static List<PhysicsBogeyBlockEntity> chainOrder(List<PhysicsBogeyBlockEntity> input) {
      List<PhysicsBogeyBlockEntity> rest = new ArrayList<>(input);
      if (rest.size() <= 1) {
         return rest;
      }

      List<PhysicsBogeyBlockEntity> out = new ArrayList<>(rest.size());
      PhysicsBogeyBlockEntity current = rest.remove(0);
      out.add(current);

      while (!rest.isEmpty()) {
         Vec3 center = bogeyCenter(current);
         if (center == null) {
            break;
         }
         int best = -1;
         double bestDistance = Double.MAX_VALUE;
         for (int i = 0; i < rest.size(); i++) {
            Vec3 other = bogeyCenter(rest.get(i));
            if (other == null) {
               continue;
            }
            double distance = center.distanceToSqr(other);
            if (distance < bestDistance) {
               bestDistance = distance;
               best = i;
            }
         }
         if (best < 0) {
            break;
         }
         current = rest.remove(best);
         out.add(current);
      }

      // 量不出来的留在后面：不丢、也不硬接
      out.addAll(rest);
      return out;
   }

   /** 把物理转向架按"所属子结构"分组：一个子结构 = 一节车。 */
   private static Map<UUID, List<PhysicsBogeyBlockEntity>> splitSections(ServerLevel level, List<PhysicsBogeyBlockEntity> source) {
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

      return sections;
   }

   private static List<List<BlockPos>> blockPosLists(Map<UUID, List<PhysicsBogeyBlockEntity>> sections) {
      List<List<BlockPos>> out = new ArrayList<>(sections.size());
      for (List<PhysicsBogeyBlockEntity> section : sections.values()) {
         List<BlockPos> pos = new ArrayList<>(section.size());
         for (PhysicsBogeyBlockEntity b : section) {
            pos.add(b.getBlockPos().immutable());
         }
         out.add(pos);
      }
      return out;
   }

   /**
    * 接管既有列车时重新登记结构（世界重载后 {@link #STRUCTURE} 是空的）。
    * 只有"登记得出来"且"节数与车上车厢数一致"才算接管成功，否则返回 false 让调用方重建。
    */
   private static boolean adoptStructure(ServerLevel level, UUID trainId, List<PhysicsBogeyBlockEntity> explicit,
                                            PhysicsBogeyBlockEntity seed) {
      try {
         List<PhysicsBogeyBlockEntity> source = explicit;
         if (source == null || source.isEmpty()) {
            PhysicsBogeyGroup group = PhysicsBogeyGroup.createAndAssign(seed);
            source = group == null ? List.of() : new ArrayList<>(group.bogeys);
         }
         if (source.isEmpty()) {
            return false;
         }

         List<List<BlockPos>> struct = blockPosLists(splitSections(level, chainOrder(source)));
         Train live = (Train)Create.RAILWAYS.trains.get(trainId);
         if (live == null || live.invalid || live.carriages.size() != struct.size()) {
            HexieTrain.LOGGER.warn("[HexieTrain][bind] 接管对不上：结构 {} 节 vs 车上 {} 节",
               struct.size(), live == null ? -1 : live.carriages.size());
            return false;
         }

         STRUCTURE.put(trainId, struct);
         HexieTrain.LOGGER.info("[HexieTrain][bind] 接管 {}：结构已重新登记 {} 节", shortId(trainId), struct.size());
         return true;
      } catch (Throwable t) {
         HexieTrain.LOGGER.warn("[HexieTrain][bind] 接管登记异常 {}", t.toString());
         return false;
      }
   }

   /**
    * 从绑定表重新登记结构（世界重载后 {@link #STRUCTURE} 是空的，而
    * {@code boundTrain} 是从存档恢复的 ⇒ {@code bind} 根本不会被调用）。
    *
    * <p><b>为什么必须能重新登记</b>：{@code STRUCTURE} 是内存态，存档里没有它。
    * 2026-11-22 13:27 实测：物理车从 1 m/s 跑到 7.5 m/s，而虚拟车的点一次都没被更新，
    * {@code anchorProbe} 一直是"结构未登记" —— 占区停留在**上一个存档里那个位置**，
    * 等于给别的车留了一个幽灵预留。这不是"位置算错了"，是"根本没算"。
    *
    * @return true = 登记成功且节数与车上的车厢数一致
    */
   public static boolean registerStructureFrom(UUID trainId, ServerLevel level, List<PhysicsBogeyBlockEntity> source) {
      if (trainId == null || source == null || source.isEmpty()) {
         return false;
      }

      try {
         List<List<BlockPos>> struct = blockPosLists(splitSections(level, chainOrder(source)));
         Train live = (Train)Create.RAILWAYS.trains.get(trainId);
         if (live != null && !live.invalid && live.carriages.size() != struct.size()) {
            // 对不上就**不登记**：宁可这一拍不写点，也不能拿错的节数去写（那会写半列车）。
            HexieTrain.LOGGER.warn("[HexieTrain][bind] 结构重新登记对不上：结构 {} 节 vs 车上 {} 节（车={}）",
               struct.size(), live.carriages.size(), shortId(trainId));
            return false;
         }

         STRUCTURE.put(trainId, struct);
         int total = 0;
         for (List<BlockPos> sec : struct) {
            total += sec.size();
         }
         HexieTrain.LOGGER.info("[HexieTrain][bind] 结构已按绑定表重新登记：车厢={} 转向架={}（车={}）",
            struct.size(), total, shortId(trainId));
         return true;
      } catch (Throwable t) {
         HexieTrain.LOGGER.warn("[HexieTrain][bind] 结构重新登记异常 {}", t.toString());
         return false;
      }
   }

   /** 结构登记还在不在，而且节数对不对。{} 声明要写在注解里，这里就用普通注释。 */
   public static boolean structureMatches(UUID trainId, int carriages) {
      List<List<BlockPos>> struct = trainId == null ? null : STRUCTURE.get(trainId);
      return struct != null && struct.size() == carriages;
   }

   public static boolean hasStructure(UUID trainId) {
      return trainId != null && STRUCTURE.containsKey(trainId);
   }

   /**
    * 这个方块位置是不是<b>我们自己某辆车</b>的物理转向架（见 {@link #STRUCTURE}）。
    *
    * <p>给物理占区判定用：Simurail 把"这一区间里有哪些物理转向架"放在
    * {@code SignalEdgeGroup} 的一个集合里，我们要从中排除自己那一列，
    * 否则又会变成"自己占的区间把自己锁死"。
    */
   public static boolean isOurBogey(BlockPos pos) {
      if (pos == null) {
         return false;
      }
      for (List<List<BlockPos>> sections : STRUCTURE.values()) {
         for (List<BlockPos> section : sections) {
            for (BlockPos p : section) {
               if (p.equals(pos)) {
                  return true;
               }
            }
         }
      }
      return false;
   }

   private static PhysicsBogeyBlockEntity bogeyAt(ServerLevel level, BlockPos pos) {
      try {
         // C2ME：没加载的区块绝不许 getBlockEntity（CompletableFuture.join 会死锁）
         if (!level.isLoaded(pos)) {
            return null;
         }
         return level.getBlockEntity(pos) instanceof PhysicsBogeyBlockEntity b ? b : null;
      } catch (Throwable t) {
         return null;
      }
   }

   private static boolean hasTrackPoint(PhysicsBogeyBlockEntity b) {
      try {
         TravellingPoint f = b.getAxle(true).getTrackPoint();
         TravellingPoint r = b.getAxle(false).getTrackPoint();
         return f != null && r != null && f.edge != null && r.edge != null;
      } catch (Throwable t) {
         return false;
      }
   }

   /** 一根转向架的中心 = 两根轴的世界位置中点。 */
   private static Vec3 bogeyCenter(PhysicsBogeyBlockEntity b) {
      if (b == null) {
         return null;
      }
      try {
         Vec3 f = pointWorld(b.getAxle(true).getTrackPoint());
         Vec3 r = pointWorld(b.getAxle(false).getTrackPoint());
         if (f != null && r != null) {
            return f.add(r).scale(0.5);
         }
         return f != null ? f : r;
      } catch (Throwable t) {
         return null;
      }
   }

   /** 两根转向架中心之间的距离（米，四舍五入）。量不出来就返回 0，由调用方决定最小值。 */
   private static int centerGap(PhysicsBogeyBlockEntity a, PhysicsBogeyBlockEntity b) {
      Vec3 ca = bogeyCenter(a);
      Vec3 cb = bogeyCenter(b);
      return ca == null || cb == null ? 0 : (int)Math.round(ca.distanceTo(cb));
   }

   private static CarriageBogey newBogey(PhysicsBogeyBlockEntity physical) {
      try {
         TravellingPoint f = copyPoint(physical.getAxle(true).getTrackPoint());
         TravellingPoint r = copyPoint(physical.getAxle(false).getTrackPoint());
         if (f == null || r == null) {
            return null;
         }
         // 每根转向架必须有自己的 bogeyData：构造函数会往里写 UpsideDown
         CompoundTag data = new CompoundTag();
         data.putString("BogeyStyle", AllBogeyStyles.STANDARD.id.toString());
         data.put("BogeyData", AllBogeyStyles.STANDARD.defaultData.copy());
         return new CarriageBogey((AbstractBogeyBlock)AllBlocks.SMALL_BOGEY.get(), false, data, f, r);
      } catch (Throwable t) {
         return null;
      }
   }

   private static String shortId(UUID id) {
      return id == null ? "null" : id.toString().substring(0, 8);
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

   private static TravellingPoint copyPoint(TravellingPoint src) {
      if (src == null) {
         return null;
      }
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
               // C2ME：没加载的区块绝不许 getBlockEntity（会 join 到别的线程，死锁）
               if (!level.isLoaded(cursor)) {
                  continue;
               }
               if (level.getBlockEntity(cursor) instanceof PhysicsBogeyBlockEntity found) {
                  return found;
               }
            }
         }
      }

      return null;
   }

   private static Vec3 pointWorld(TravellingPoint point) {
      if (point == null) {
         return null;
      }
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

   public static TrainBinder.PhysicalMotion motionOf(UUID trainId) {
      return trainId == null ? TrainBinder.PhysicalMotion.NONE : (TrainBinder.PhysicalMotion)MOTION.getOrDefault(trainId, TrainBinder.PhysicalMotion.NONE);
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
