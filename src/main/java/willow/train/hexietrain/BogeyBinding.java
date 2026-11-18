package willow.train.hexietrain;

import com.crystaelix.simurail.content.bogey.PhysicsBogeyAxle;
import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;
import com.crystaelix.simurail.content.bogey.PhysicsBogeyGroup;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.graph.TrackEdge;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3dc;

/**
 * 转向架绑定表。
 *
 * <p>目前只实现<b>只读探针</b>：把「这列车由哪些转向架组成、它们怎么连的、
 * 谁的轴在真正判定道岔、车体横向与行进左/右的符号关系」一次性打成日志。
 * 不写任何东西、不发任何信号、不改任何状态。
 *
 * <h2>安全不变量（必须保持）</h2>
 * 本类<b>禁止</b>出现下列任何一个调用：
 * <pre>
 *   level.getBlockState / level.getSignal / level.getBlockEntity / 任何 getChunk
 * </pre>
 * 原因见 {@code TrainDispatcherBlockEntity.serverTick} 顶部注释：
 * DispatcherTicker 是全局 ServerTickEvent，会在未加载区块上跑；
 * 而那些调用会走 {@code ServerChunkCache.getChunk -> CompletableFuture.join()}，
 * 在 C2ME 异步区块下服务器线程会把自己等死（2026-11-17 01:14 那次）。
 *
 * <p>特别地：{@code PhysicsBogeyBlockEntity.getSteerValue()} 与
 * {@code getGroupSteerValue()} 内部就是 {@code level.getSignal(pos.relative(d), d)}，
 * 属于同一个雷区，本类<b>一律不调用</b>。
 *
 * <p>允许的只有：字段读取、Sable 的 plot 查表（{@code getContaining}）、纯数学。
 */
public final class BogeyBinding {

   private BogeyBinding() {
   }

   /** 已经打过快照的签名；一样就不再打。 */
   private static String lastSig = null;

   /**
    * 打一次快照。同一个编组只打一次，编组变了（数量 / 位置 / 子结构）才重打。
    *
    * @return 本次是否真的打了日志
    */
   public static boolean probe(ServerLevel level, BlockPos dispatcherPos) {
      Seed scan = new Seed();
      PhysicsBogeyBlockEntity seed = findSeed(level, dispatcherPos, scan);
      if (seed == null || seed.isRemoved()) {
         return false;   // 还没绑定 / 已失效：静默等下一轮，不刷屏
      }


      PhysicsBogeyGroup group;
      try {
         group = seed.getGroup();
      } catch (Throwable t) {
         HexieTrain.LOGGER.warn("[bind] 取编组失败：{}", t.toString());
         return false;
      }
      if (group == null || group.bogeys.isEmpty()) {
         return false;
      }

      List<PhysicsBogeyBlockEntity> list = new ArrayList<>(group.bogeys);

      // 箱内找到的转向架里，有几个真的属于这条 connector 链
      int members = 0;
      for (PhysicsBogeyBlockEntity hit : scan.hits) {
         if (list.contains(hit)) {
            members++;
         }
      }

      String sig = signature(level, dispatcherPos, list, scan.hits.size());
      if (sig.equals(lastSig)) {
         return false;
      }
      lastSig = sig;

      int[] comp = components(list);

      HexieTrain.LOGGER.info("[bind] ── 快照 ── 子结构={} 调度器={} 箱内转向架={} 本组={} 链数={}",
         new Object[]{subLevelId(level, dispatcherPos), dispatcherPos.toShortString(),
            scan.hits.size(), list.size(), countComps(comp)});

      if (scan.hits.size() > members) {
         HexieTrain.LOGGER.info(
            "[bind] ── 注意 ── 箱内共 {} 个转向架，只有 {} 个连在本组里；其余 {} 个是独立编组，各自调 steer()",
            new Object[]{scan.hits.size(), members, scan.hits.size() - members});
      }

      for (int i = 0; i < list.size(); i++) {
         PhysicsBogeyBlockEntity b = list.get(i);
         logBogey(i, b, list, group, comp[i]);
      }

      logSymbol(list, group);
      logVerdict(list, comp, scan.hits.size(), members);
      return true;
   }

   // ================================================================ 找种子

   /**
    * 找种子转向架，同时把「箱子里一共有几个转向架」记进 {@code out}。
    *
    * <p>扫描要 {@code level.getBlockEntity}，那在雷区里；所以每个位置都先问
    * {@code hasChunkAt}（纯查询、绝不加载区块），只对**已加载**区块取方块实体。
    * 未加载一律跳过，绝不触发区块加载。
    *
    * <p><b>为什么要扫全箱而不是找到第一个就返回</b>：{@code getGroup()} 只沿 connector
    * 链走，没连的转向架看不到。扫全箱才能区分「只有 1 个转向架」和「有 N 个但没连」。
    */
   private static PhysicsBogeyBlockEntity findSeed(ServerLevel level, BlockPos center, Seed out) {
      MutableBlockPos cur = new MutableBlockPos();
      for (int dx = -12; dx <= 12; dx++) {
         for (int dy = -8; dy <= 8; dy++) {
            for (int dz = -12; dz <= 12; dz++) {
               cur.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
               if (!level.hasChunkAt(cur)) {
                  continue;
               }
               if (level.getBlockEntity(cur) instanceof PhysicsBogeyBlockEntity b && !b.isRemoved()) {
                  out.hits.add(b);
               }
            }
         }
      }

      // reanchor 每 tick 都在扫，它抓到的那个是权威种子；没有才退回箱内第一个
      PhysicsBogeyBlockEntity cached = TrainBinder.lastBogey();
      if (cached != null && !cached.isRemoved()) {
         return cached;
      }
      return out.hits.isEmpty() ? null : out.hits.get(0);
   }

   /** 一次扫描的产物：种子 + 箱内所有转向架（用来区分「只有 1 个」和「有 N 个但没连」）。 */
   private static final class Seed {
      final List<PhysicsBogeyBlockEntity> hits = new ArrayList<>();
   }

   // ================================================================ 每架一行

   private static void logBogey(int i, PhysicsBogeyBlockEntity b, List<PhysicsBogeyBlockEntity> list,
                                PhysicsBogeyGroup group, int comp) {
      Direction facing = safeFacing(b);
      boolean rev = safeRev(group, i);
      int fwd = indexOfIdentity(list, safeConnected(b, true));
      int bwd = indexOfIdentity(list, safeConnected(b, false));

      HexieTrain.LOGGER.info("[bind] B{} pos={} facing={} rev={} 前连={} 后连={} 链{}",
         new Object[]{i, b.getBlockPos().toShortString(), facing, rev, name(fwd), name(bwd), comp});

      logAxle(i, "F", safeAxle(b, true));
      logAxle(i, "B", safeAxle(b, false));
   }

   private static void logAxle(int i, String tag, PhysicsBogeyAxle axle) {
      if (axle == null) {
         HexieTrain.LOGGER.info("[bind]   B{} {}=null", i, tag);
         return;
      }
      TravellingPoint p = axle.getTrackPoint();
      String edge = edgeDesc(p == null ? null : p.edge);
      String pos = p == null ? "-" : String.format("%.2f", p.position);
      boolean front;
      try {
         front = axle.isCurrentFront();
      } catch (Throwable t) {
         front = false;
      }
      String world = "-";
      try {
         if (p != null && p.edge != null) {
            world = vec(worldOf(p));
         }
      } catch (Throwable t) {
         world = "ERR";
      }
      HexieTrain.LOGGER.info("[bind]   B{} 轴{} edge={} pos={} 当前前端={} 世界={}",
         new Object[]{i, tag, edge, pos, front, world});
   }

   // ================================================================ 符号自检

   /**
    * 关键自检：车体横向（{@code getLateral()}）与「行进方向的右侧」是同一个方向吗？
    *
    * <p>Simurail 的选边函数是 {@code desired = travelDir + steerValue * lateral}
    * （{@code PhysicsBogeyAxle.lambda$steer$18}），其中 lateral 取自轴坐标系。
    * 如果 {@code getLateral() == 行进右}，那么 {@code steerValue > 0} 就是往行进右偏；
     * 如果不是，符号就要整体取反。这一行日志直接把答案算出来，不用猜、不用实测。
    */
   private static void logSymbol(List<PhysicsBogeyBlockEntity> list, PhysicsBogeyGroup group) {
      PhysicsBogeyBlockEntity b = pickLeading(list);
      if (b == null) {
         return;
      }
      try {
         Vec3 lateral = joml(b.getLateral());
         Vec3 bodyDir = joml(b.getDirection());

         PhysicsBogeyAxle axle = leadingAxle(list, group, b);
         Vec3 travel = axle == null ? null : travelDirOf(axle.getTrackPoint());
         if (travel == null) {
            travel = bodyDir;
         }
         Vec3 right = travel.cross(new Vec3(0.0, 1.0, 0.0));
         double dot = lateral.dot(right);

         String verdict = Math.abs(dot) < 0.3
            ? "横向与行进轴不垂直（换向/侧倾中），暂不判定"
            : (dot > 0.0
               ? "steerValue>0 ⇒ 往【行进右】偏，steerValue<0 ⇒ 往【行进左】偏"
               : "steerValue>0 ⇒ 往【行进左】偏，steerValue<0 ⇒ 往【行进右】偏");

         HexieTrain.LOGGER.info("[bind] 符号 B{} 车体横向={} 行进={} 行进右={} dot={}",
            new Object[]{indexOfIdentity(list, b), vec(lateral), vec(travel), vec(right),
               String.format("%+.3f", dot)});
         HexieTrain.LOGGER.info("[bind] 结论 {}", verdict);
      } catch (Throwable t) {
         HexieTrain.LOGGER.warn("[bind] 符号自检失败：{}", t.toString());
      }
   }

   /** 车体朝向的「行进方向」（沿当前边的切线，取远端）。 */
   private static Vec3 travelDirOf(TravellingPoint p) {
      if (p == null || p.edge == null) {
         return null;
      }
      try {
         boolean alongEdge = (p.node1 == p.edge.node1);
         Vec3 d = alongEdge ? p.edge.getDirection(false) : p.edge.getDirection(true).scale(-1.0);
         return d == null || d.lengthSqr() < 1.0E-8 ? null : d;
      } catch (Throwable t) {
         return null;
      }
   }

   // ================================================================ 结论

   private static void logVerdict(List<PhysicsBogeyBlockEntity> list, int[] comp, int boxCount, int members) {
      int chains = countComps(comp);
      if (boxCount > members) {
         HexieTrain.LOGGER.info(
            "[bind] 结论 箱内 {} 个转向架只有 {} 个连在本组 ⇒ 剩下的各自独立、各自调 steer()；"
               + "想「控一个就够」必须先用 connector 把它们串起来",
            new Object[]{boxCount, members});
      }
      if (list.size() <= 1) {
         HexieTrain.LOGGER.info("[bind] 结论 本组只有 1 个转向架，没有分叉风险，单控它即可");
      } else if (chains == 1) {
         HexieTrain.LOGGER.info(
            "[bind] 结论 转向架已连成 1 条链 ⇒ 只有链头那根轴的 steer() 被调用，其余轴 follow 前车，"
               + "控一个就够");
      } else if (chains == list.size()) {
         HexieTrain.LOGGER.info(
            "[bind] 结论 {} 个转向架各自独立 ⇒ 每个转向架的前轴都各自调 steer()，必须逐个控，漏一个就分叉",
            list.size());
      } else {
         HexieTrain.LOGGER.info("[bind] 结论 {} 个转向架分成 {} 条链 ⇒ 每条链的链头都要控", list.size(), chains);
      }
   }

   // ================================================================ 小工具

   private static PhysicsBogeyBlockEntity pickLeading(List<PhysicsBogeyBlockEntity> list) {
      for (PhysicsBogeyBlockEntity b : list) {
         PhysicsBogeyAxle f = safeAxle(b, true);
         PhysicsBogeyAxle a = safeAxle(b, false);
         if (isFront(f) || isFront(a)) {
            return b;
         }
      }
      return list.isEmpty() ? null : list.get(0);
   }

   private static PhysicsBogeyAxle leadingAxle(List<PhysicsBogeyBlockEntity> list, PhysicsBogeyGroup group,
                                               PhysicsBogeyBlockEntity b) {
      PhysicsBogeyAxle f = safeAxle(b, true);
      PhysicsBogeyAxle a = safeAxle(b, false);
      if (isFront(f)) {
         return f;
      }
      if (isFront(a)) {
         return a;
      }
      return f != null ? f : a;
   }

   private static boolean isFront(PhysicsBogeyAxle axle) {
      if (axle == null) {
         return false;
      }
      try {
         return axle.isCurrentFront();
      } catch (Throwable t) {
         return false;
      }
   }

   private static String signature(ServerLevel level, BlockPos dispatcherPos, List<PhysicsBogeyBlockEntity> list,
                                   int boxCount) {
      StringBuilder sb = new StringBuilder(64);
      sb.append(subLevelId(level, dispatcherPos)).append('|').append(boxCount).append('|').append(list.size());
      for (PhysicsBogeyBlockEntity b : list) {
         sb.append('|').append(b.getBlockPos().asLong());
      }
      return sb.toString();
   }

   private static String subLevelId(ServerLevel level, BlockPos pos) {
      try {
         ServerSubLevel sub = SubLevelSuspend.containing(level, pos);
         return sub == null ? "无子结构" : shortId(sub.getUniqueId());
      } catch (Throwable t) {
         return "?";
      }
   }

   private static String shortId(java.util.UUID id) {
      return id == null ? "-" : id.toString().substring(0, 8);
   }

   private static int[] components(List<PhysicsBogeyBlockEntity> list) {
      int n = list.size();
      int[] comp = new int[n];
      Arrays.fill(comp, -1);
      ArrayDeque<Integer> queue = new ArrayDeque<>();
      int c = 0;
      for (int i = 0; i < n; i++) {
         if (comp[i] >= 0) {
            continue;
         }
         comp[i] = c;
         queue.add(i);
         while (!queue.isEmpty()) {
            int k = queue.poll();
            PhysicsBogeyBlockEntity b = list.get(k);
            for (boolean side : new boolean[]{true, false}) {
               int j = indexOfIdentity(list, safeConnected(b, side));
               if (j >= 0 && comp[j] < 0) {
                  comp[j] = c;
                  queue.add(j);
               }
            }
         }
         c++;
      }
      return comp;
   }

   private static int countComps(int[] comp) {
      int max = -1;
      for (int v : comp) {
         if (v > max) {
            max = v;
         }
      }
      return max + 1;
   }

   private static int indexOfIdentity(List<PhysicsBogeyBlockEntity> list, PhysicsBogeyBlockEntity target) {
      if (target == null) {
         return -1;
      }
      for (int i = 0; i < list.size(); i++) {
         if (list.get(i) == target) {
            return i;
         }
      }
      return -1;
   }

   private static String name(int index) {
      return index < 0 ? "null" : ("B" + index);
   }

   private static Direction safeFacing(PhysicsBogeyBlockEntity b) {
      try {
         return b.getFacing();
      } catch (Throwable t) {
         return null;
      }
   }

   private static boolean safeRev(PhysicsBogeyGroup group, int i) {
      try {
         return group.reverseStates.getBoolean(i);
      } catch (Throwable t) {
         return false;
      }
   }

   private static PhysicsBogeyBlockEntity safeConnected(PhysicsBogeyBlockEntity b, boolean front) {
      try {
         return b.getConnected(front);
      } catch (Throwable t) {
         return null;
      }
   }

   private static PhysicsBogeyAxle safeAxle(PhysicsBogeyBlockEntity b, boolean front) {
      try {
         return b.getAxle(front);
      } catch (Throwable t) {
         return null;
      }
   }

   private static Vec3 joml(Vector3dc v) {
      return v == null ? Vec3.ZERO : new Vec3(v.x(), v.y(), v.z());
   }

   private static String vec(Vec3 v) {
      return v == null ? "-" : String.format("(%.2f,%.2f,%.2f)", v.x, v.y, v.z);
   }

   private static String edgeDesc(TrackEdge e) {
      if (e == null) {
         return "null";
      }
      try {
         Vec3 a = e.node1.getLocation().getLocation();
         Vec3 b = e.node2.getLocation().getLocation();
         return String.format("(%.0f,%.0f,%.0f)->(%.0f,%.0f,%.0f) len=%.1f",
            a.x, a.y, a.z, b.x, b.y, b.z, e.getLength());
      } catch (Throwable t) {
         return "ERR";
      }
   }

   /** 边上的点换算成世界坐标（线性插值，和 TrainBinder.pointWorld 同口径）。 */
   private static Vec3 worldOf(TravellingPoint p) {
      TrackEdge e = p.edge;
      Vec3 a = e.node1.getLocation().getLocation();
      Vec3 b = e.node2.getLocation().getLocation();
      double len = Math.max(1.0E-6, e.getLength());
      double t = Math.max(0.0, Math.min(1.0, p.position / len));
      return a.add(b.subtract(a).scale(t));
   }
}