package willow.train.hexietrain;

import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.AllItems;
import com.simibubi.create.Create;
import com.simibubi.create.content.kinetics.transmission.SplitShaftBlockEntity;
import com.simibubi.create.content.redstone.link.LinkBehaviour;
import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler.Frequency;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.Navigation;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.schedule.Schedule;
import com.simibubi.create.content.trains.schedule.ScheduleEntry;
import com.simibubi.create.content.trains.schedule.ScheduleItem;
import com.simibubi.create.content.trains.schedule.ScheduleRuntime;
import com.simibubi.create.content.trains.schedule.destination.DestinationInstruction;
import com.simibubi.create.content.trains.signal.SignalBlockEntity;
import com.simibubi.create.content.trains.signal.SignalBoundary;
import com.simibubi.create.content.trains.signal.SignalBlockEntity.SignalState;
import com.simibubi.create.content.trains.station.GlobalStation;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform.Sided;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import willow.train.hexie.compat.create.CreateSignalAspects;

public class TrainDispatcherBlockEntity extends SplitShaftBlockEntity implements MenuProvider, Container {
   public static final float MAX_RPM = 256.0F;
   public static final float STRESS_CAPACITY = 512.0F;
   public static final int FREQ_SLOTS = SignalChannel.VALUES.length * 2;
   private final ItemStack[] frequencies = new ItemStack[FREQ_SLOTS];
   private final WirelessChannel[] wireless = new WirelessChannel[SignalChannel.VALUES.length];
   private final int[] outputs = new int[SignalChannel.VALUES.length];
   private final List<String> stopNames = new ArrayList<>();
   private String nextTargetName = "";
   private double lastDistance = 0.0;
   private static final double SLOW_ZONE_BLOCKS = 5.0;
   private static final double SLOW_ZONE_SPEED_MPS = 3.0;
   private static final double CREEP_SPEED_MPS = 0.3;
   private static final double STOP_ZONE = 3.0;
   private int arrivedEntry = -1;
   private int stoppedEntry = -1;
   private boolean waitingForSchedule = false;
   private int targetLogTicks = 0;
   private int lastEntrySeen = -1;
   private final Vector3d measuredVelocity = new Vector3d();
   private double measuredSpeedMps = 0.0;
   private int driveSide = 0;
   /** 上一个 tick 的规划弧长（判断是否沿路线前进） */
   private double lastPlanS = Double.NaN;
   /** 本段是否已做过一次换面纠正 */
   private boolean legFlipUsed = false;
   /** 方向认证结果：null=未认证 true=当前面对（一次 1 tick 传动实测 + 路径期望方向比对） */
   private Boolean sideConfirmed = null;
   /** 面锁存：认证通过后锁死，只有到站/导航丢失目标才解锁（避免红石闪断导致方向不变） */
   private boolean sideLatched = false;
   /** 锁存归属：目标站名 + 段类型；不符则锁存失效 */
   private String latchedTarget = null;
   private int latchedLegKind = 0;
   private boolean reversalDone = false;
   private int lastUsedSide = 0;
   private boolean polarityFixed = false;
   private int dirAgreeTicks = 0;
   private float outputRatio = 0.0F;
   private DispatcherState dispatcherState = DispatcherState.IDLE;
   private int lastRatioQuantum = -1;
   private double routeDistance = -1.0;
   private int routeTicks = 0;
   private int brakeLogTicks = 0;
   private int derailLogTicks = 0;
   private static final Set<TrainDispatcherBlockEntity> ACTIVE = ConcurrentHashMap.newKeySet();
   private static final Map<UUID, TrainDispatcherBlockEntity> TRAIN_OWNER = new ConcurrentHashMap<>();
   private Vec3 lastLeadPos = null;
   private int stallTicks = 0;
   private int rebindCooldown = 0;
   private int missingTicks = 0;
   private int presenceTicks = 0;
   private long lastSendTick = -1L;
   private int diagTicks = 0;
   private GlobalStation dwellingStation = null;
   private String lastDestName = "";
   private int lastEntryLogged = -1;
   private double pendingLeg = -1.0;
   private double cacheDistShown = Double.NaN;
   private ItemStack lastSlotSnapshot = ItemStack.EMPTY;
   private String previewStation = null;
   private int previewEntry = Integer.MIN_VALUE;
   private long navAcquireTick = -1000L;
   private double previewSNow = 0.0;
   private long previewLastTick = -1L;
   private int approachTicks = 0;
   private double lastDistForArrive = Double.NaN;
   private static final double V_CRUISE_MPS = 15.0;
   private int departTicks = 0;
   private final ScheduleMirror mirror = new ScheduleMirror();
   private double prevStationDist = Double.NaN;
   private boolean arrivedFlag = false;
   private boolean arrivalReported = false;
   private CompoundTag conditionCtx = new CompoundTag();
   private int waitingEntry = Integer.MIN_VALUE;
   private int probeTicksArrive = 0;
   private int probeTicksSig = 0;
   private double lastBlockedSignalAt = -1.0;
   private int mirrorEmptyTicks = 0;
   private int emptySlotTicks = 0;
   private int mailTicks = 0;
   private int myEntry = 0;
   private float myDir = 1.0F;
   private int myDirEntry = Integer.MIN_VALUE;
   private int lastAcquiredEntry = Integer.MIN_VALUE;
   private long lastAcquireTick = -1000L;
   private static final boolean BRAKE_DISABLED_FOR_TEST = false;
   /**
    * 第④步驱动总开关。暂缓：驱动层设计还没定稿。
    * 关掉时自动模式只跑只读诊断（[probe]），一点牵引都不给。
    * 打开后走 driveTick：面标定 + 距站→油门/制动，带 400 次决策硬上限。
    */
   private static final boolean DRIVE_ENABLED = true;
   private int gateProbeTicks = 0;
   private int sigProbeTicks = 0;
   private int cacheProbeTicks = 0;
   private Vec3 prevWorld = null;
   private double lastSignalDist = -1.0;
   private boolean manualMode = false;
   private static final double MANUAL_MAX_SPEED = 0.25;
   private ItemStack scheduleSlot = ItemStack.EMPTY;
   private String lastBindMessage = "";
   private UUID boundTrain;
   public static final Map<UUID, TrainDispatcherBlockEntity> BY_TRAIN = new ConcurrentHashMap<>();
   private int entryProbeTicks = 0;
   private int stateProbeTicks = 0;
   private UUID assemblyId;
   private boolean recordLoaded = false;
   private UUID persistentTrainId;
   private UUID owner;
   private Object lastDestination;
   private boolean linearizeBrake = true;
   private int extCounterClockwise = 0;
   private int extClockwise = 0;
   private double legRemaining = -1.0;
   private Object lastLegDest = null;
   private int lastLegEntry = -1;
   private int probeTicks = 0;
   private LinkBehaviour[] links;
   private int driveLogTicks = 0;

   /** driveTick 的节拍计数：每 5 tick 做一次决策 */
   private int driveGateTicks = 0;
   private boolean probeOk = false;
   private boolean probeBackwards = false;
   private int probeEdges = 0;
   private double probeMillis = 0.0;
   // ---- 第④步驱动试验（带硬上限，可随时观察）----
   private RailSearch.Result driveResult = null;
   private String driveTarget = null;
   private int driveTicks = 0;
   /** 看门狗是否已触发：true 时只抱闸，直到换到新的一段（target 变化）才解除 */
   private boolean driveHalted = false;
   /** 触发看门狗时那一拍的 entry。entry 一换就必须解除，否则会互锁死。 */
   private int haltEntry = -1;
   /** 位置失效/恢复的拍数，用来限流日志 */
   private int recoverTicks = 0;
   /** Create 自己把 navigation.destination 设回来的次数（诊断：它设回来就会抢走岔口决策） */
   private int navClearedTicks = 0;

   // ---- 转向架绑定探针（只读快照，见 BogeyBinding）----
   /** 已经成功打出过快照了吗（只是节拍开关，不影响复查） */
   private boolean bindProbeDone = false;
   /** 探针节拍：未成功时每 20 tick 试一次；成功后每 1200 tick 复查一次签名（编组变了会重打） */
   private int bindProbeTicks = 0;

   // ---- 转向架绑定表（见 BogeyRoster：不用 Simurail 的 connector）----
   private final BogeyRoster roster = new BogeyRoster();
   /** 逐转向架道岔子系统（见 BogeySteering）。 */
   private final BogeySteering steering = new BogeySteering();
   /** 上一次 [nav] 覆盖状态的位组合，只用来限流日志。-1 = 还没打过 */
   private int lastCoverState = -1;
   /** 这次寻路用的是哪一端：0=未定 1=绑定表首端 2=绑定表尾端。两端共同决定，只选一个。 */
   /** 沿轨串链得出的尾端 / 头端转向架位置。尾端=寻路起点+方向参照，头端=距站。 */
   private net.minecraft.core.BlockPos navTailPos = null;
   private net.minecraft.core.BlockPos navHeadPos = null;
   /** 上一拍的路线弧长，只用来检测"油门在给、s 却在倒退"这种方向错。 */
   private double lastRouteS = Double.NaN;
   /** 车厢数（= 子层个数）。客户端数不了，服务端数好随包发。 */
   private int rosterCars = 0;

   /** 空转日志限流：每 20 次决策（5 秒）打一条 */
   private int idleLogTicks = 0;
   /** 规划失败后的重试冷却（driveTick 计次）。一次失败要跑最多 N 次寻路，不能每拍重来。 */
   private int planCooldown = 0;
   /** 每段最多多少次决策（每次 5 tick）：400 ⇒ 2000 tick ⇒ 100 秒 */
   private static final int DRIVE_TICK_CAP = 400;
   /** 面标定：把车推向 {@link #dirBogey} 那个物理端的驱动面（1=逆时针 2=顺时针） */
   private int dirFace = 1;
   /** 本拍实际用哪个面驱动；applyPhase 只认它 */
   private int activeFace = 1;
   /** 面标定是否已成立：0=未标定，±1=已标定（符号只作记录，不参与选面） */
   private int dirSign = 0;
   /**
    * 标定得出的「这个面把车推向哪个物理端」，存那个端的转向架坐标。
    *
    * <p><b>这是整套方向逻辑的唯一事实。</b>转向架坐标是物理端，不随路线、不随
    * 规划换端而变。于是任何一次规划只要比一比"领先端是不是这个端"就能定驱动面：
    * <pre>
    *   activeFace = 领先端 == dirBogey ? dirFace : 另一个面
    * </pre>
    * 换向、换路线、重新规划<b>全都不需要重标</b>，也不会来回翻面抽搐。
    *
    * <p>以前存的是"路线朝 node1 还是 node2"——那是<b>边</b>的属性（node1/node2 是
    * 每条边各自的任意编号），换条边就失效。所以每次串链换端都得重标，重标期间车不动，
    * 现象就是"犹豫前后走"。
    */
   private net.minecraft.core.BlockPos dirBogey = null;
   // ---- 面标定（方向必须实测：写死会在别的装配上翻车）----
   /** 标定油门：只要能把车推动一点点 */
   private static final int CAL_THROTTLE = 4;
   /** 每个面最多给 40 次决策（10 秒）去证明它能推动车 */
   private static final int CAL_TICK_LIMIT = 40;
   /** 位移超过这个值才认定「这个面能推动车」，并据此定方向 */
   private static final double CAL_MIN_TRAVEL = 0.08;
   /** 0=取样 4=确认静止 1=量位移 3=已标定 */
   private int calPhase = 0;
   /** 正在试的面：1=逆时针 2=顺时针 */
   private int calFace = 1;
   private int calTick = 0;
   /** 两个面都推不动时的冷却，避免每拍刷屏重试 */
   private int calCooldown = 0;
   private double calPos0 = Double.NaN;
   /** 标定基线：领先端在本段路线上的弧长。位移量的是"路线 s 变了多少"——这才是路线坐标系，边的 node1→node2 与它无关 */
   private double calS0 = Double.NaN;
   private TrackEdge calEdge = null;
   private UUID calTrain = null;
   private boolean driveStopped = false;
   /** 时刻表已走到头（非循环表）⇒ 停在最后一站不动 */
   private boolean scheduleDone = false;
   /** 驱动阶段：对应 DispatcherState 四模态 + 收油门过渡 */
   private static final int PH_IDLE  = 0;   // 待命：什么都不做
   private static final int PH_RUN   = 1;   // 行驶中：激活传动 + 油门
   private static final int PH_CUT   = 2;   // 收油门：传动还在，油门按固定速率降到 0
   private static final int PH_COAST = 3;   // 滑车：不输出传动、不输出刹车
   private static final int PH_BRAKE = 4;   // 急刹：输出刹车频率信号、不输出传动
   /** 每次决策（5 tick）油门变化 1 ⇒ 0→15 约 75 tick */
   private static final int THROTTLE_RATE = 3;   // 每次决策涨 3（约 1.25 秒到满），仍是曲线不是阶跃
   /** 速度环死区：vT 与 v 差在这个范围内就滑车 */
   private static final double DV_BAND = 0.3;
   /** 制动减速度 m/s²：停车距离曲线用（先沿用老代码的 0.6，实测后再调） */
   private static final double A_BRAKE = 0.6;
   /** 加速侧带宽：低于目标 0.8 m/s 就给满油门（不再是 dv/2.0 那种小增益） */
   private static final double ACC_BAND = 0.8;
   /** 滑车带宽：只在略超目标时滑车 */
   private static final double BRK_BAND = 0.15;
   /** 速度环满量程：dv 达到这个值就给满油门/满刹车 */
   private static final double DV_FULL = 2.0;
   /** 到站速度阈值：碰到边点还不够，速度也得降下来才算到站 */
   private static final double V_ARRIVE = 0.3;
   /** 距站多少米开始收油门 */
   private static final double STOP_TRIGGER = 6.0;
   /** 到站容差：距站 ≤ 这个值就算到站，直接摁死刹车 */
   private static final double ARRIVE_TOL = 0.5;
   private int drivePhase = PH_IDLE;
   private int throttleCmd = 0;
   private int coastTicks = 0;
   /** 本拍 Create 原生边点监听器有没有在跟前触发 */
   /** 本拍边点监听器抓到的是【红灯信号】⇒ 停车等待 */
   /** 刹车指令，跟油门一样按固定速率递增 */
   private int brakeCmd = 0;
   private int offRouteLogTicks = 0;
   private int autoLogTicks = 0;
   private int bidiLogTicks = 0;
   private boolean offRoute = false;

   public boolean driveSideActive() {
      return this.driveSide != 0;
   }

   public boolean isDriving() {
      return this.counterClockwiseSignal() > 0 || this.clockwiseSignal() > 0;
   }

   private boolean isOwner() {
      if (this.boundTrain == null) {
         return false;
      } else {
         TrainDispatcherBlockEntity o = TRAIN_OWNER.putIfAbsent(this.boundTrain, this);
         if (o != null && o != this) {
            if (!o.isRemoved() && o.getLevel() != null && o.getLevel() == this.level && o.worldPosition.equals(this.worldPosition)) {
               return false;
            } else {
               TRAIN_OWNER.put(this.boundTrain, this);
               return true;
            }
         } else {
            return true;
         }
      }
   }

   public static Set<TrainDispatcherBlockEntity> activeDispatchers() {
      ACTIVE.removeIf(TrainDispatcherBlockEntity::isRemoved);   // 陈旧实例绝不能再 tick
      return ACTIVE;
   }

   /**
    * 唯一读入口：槽位内容变化 / 读档 / 首次加载都走这里。
    *
    * <p>真相只有一份 —— 槽位时刻表物品的 savedProgress。myEntry 只是它的内存缓存，
    * 所以这里不做任何"内容变化就清 0"的猜测：游标一律等于物品里的值。
    */
   private void syncMirrorFromSlot() {
      try {
         ItemStack current = this.scheduleSlot();
         boolean same = ItemStack.matches(current, this.lastSlotSnapshot);
         if (same && (current.isEmpty() || this.mirror.size() > 0)) {
            return;
         }

         this.lastSlotSnapshot = current.copy();
         if (current.isEmpty()) {
            this.mirror.clear();
            this.stopNames.clear();
            this.myEntry = 0;
            this.cacheDistShown = Double.NaN;
            this.probeOk = false;
            this.setChanged();
            this.sendData();
            HexieTrain.LOGGER.info("[mirror] 时刻表被取出 → 清空镜像与游标");
            return;
         }

         RegistryAccess registries = this.level != null ? this.level.registryAccess() : null;
         Schedule schedule = registries == null ? null : ScheduleItem.getSchedule(registries, current);
         if (schedule == null) {
            HexieTrain.LOGGER.warn("[mirror] 槽位物品不是有效时刻表");
            return;
         }

         this.adoptFromSlot(schedule);
      } catch (Throwable t) {
         HexieTrain.LOGGER.warn("[mirror] 读取异常 {}", t.toString());
      }
   }

   /** 唯一"采纳"动作：镜像按物品重建，游标 = 物品的 savedProgress。 */
   private void adoptFromSlot(Schedule schedule) {
      try {
         int before = this.myEntry;
         RegistryAccess registries = this.level != null ? this.level.registryAccess() : null;
         if (registries == null) return;
         CompoundTag blob = schedule.write(registries);
         this.mirror.capture(blob, schedule.savedProgress);

         // 单一真相：游标就是物品里那个 int
         int entry = Math.max(0, schedule.savedProgress);
         if (entry >= Math.max(1, this.mirror.size())) {
            entry = 0;
         }
         this.myEntry = entry;
         if (before != entry) {
            this.scheduleDone = false;   // 游标真的变了才解除"已走完"闩锁
         }

         this.refreshStopNames();
         this.setChanged();
         this.sendData();
         HexieTrain.LOGGER.info("[mirror] 读表 → {} 站 {} | entry={}",
            new Object[]{this.mirror.size(), this.mirror.describe(), this.myEntry});
      } catch (Throwable t) {
         HexieTrain.LOGGER.warn("[mirror] 采纳异常 {}", t.toString());
      }
   }

   /**
    * 唯一写入口：把游标盖进**槽位里那张**时刻表物品，只改 savedProgress 这一个 int。
    *
    * <p>绝不重建整张表 —— 表内容原封不动 copy 一份，只改一个整数，所以不存在
    * "站表被写坏"的可能。值没变就一个字节都不写。
    */
   private void setEntry(int entry) {
      this.myEntry = Math.max(0, entry);
      this.stampInto(this.scheduleSlot);
      this.setChanged();
   }

   /** 把当前游标盖进给定物品（只改 savedProgress）。取出/回写/同步都走它。 */
   /**
    * 把当前游标盖进给定物品（只改 Create 认的那个 Progress 键）。取出/回写/同步都走它。
    *
    * <p><b>键名必须是 "Progress"</b>。Create 的 Schedule.write / fromTag 字节码里写死的就是它，
    * 而且 savedProgress &lt;= 0 时干脆不写这个键（等于"没有进度"）。
    * 以前这里用 "savedProgress" 是自造的死键：写进去没人读，读回来永远是 0，
    * 于是物品里真正的 Progress 一直停在旧值，下一次读表就把正确游标冲掉 ——
    * 表现就是「循环回第 1 站后又跑去第 4 站、再也回不来」。
    */
   private void stampInto(ItemStack stack) {
      try {
         if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof ScheduleItem)) return;
         CompoundTag blob = (CompoundTag) stack.get(AllDataComponents.TRAIN_SCHEDULE);
         if (blob == null) return;

         int onItem = blob.contains("Progress") ? blob.getInt("Progress") : 0;
         boolean junk = blob.contains("savedProgress");   // 旧版本留下的死键，顺手清掉
         if (onItem == this.myEntry && !junk) return;     // 没变就不写

         CompoundTag copy = blob.copy();
         copy.remove("savedProgress");
         if (this.myEntry > 0) {
            copy.putInt("Progress", this.myEntry);
         } else {
            copy.remove("Progress");                      // 与 Create 的 write() 同语义：0 = 不写键
         }
         stack.set(AllDataComponents.TRAIN_SCHEDULE, copy);
         HexieTrain.LOGGER.info("[sched] 游标写入物品 Progress {} -> {}", onItem, this.myEntry);
      } catch (Throwable t) {
         HexieTrain.LOGGER.warn("[sched] 回写进度异常 {}", t.toString());
      }
   }

   /** 重建 = 给槽位物品一份盖好进度的副本（API 保留，语义不再"从 blob 重造表"）。 */
   public ItemStack rebuildSchedule() {
      ItemStack copy = this.scheduleSlot.copy();
      this.stampInto(copy);
      return copy;
   }

   private void dumpMirror() {
      try {
         StringBuilder sb = new StringBuilder();
         sb.append("=== MirrorBlob ===\n");
         sb.append(this.mirror.blob() == null ? "(null)" : this.mirror.blob().toString()).append('\n');
         sb.append("=== stops=")
            .append(this.mirror.size())
            .append(" myEntry=")
            .append(this.myEntry)
            .append(" saved=")
            .append(this.mirror.savedProgress())
            .append(" ===\n");
         sb.append(this.mirror.describe()).append('\n');
         if (this.level != null) {
            Schedule sch = ScheduleItem.getSchedule(this.level.registryAccess(), this.scheduleSlot());
            if (sch != null && sch.entries != null) {
               sb.append("=== 槽位原始条目 ===\n");

               for (int i = 0; i < sch.entries.size(); i++) {
                  ScheduleEntry e = (ScheduleEntry)sch.entries.get(i);
                  String cls = e != null && e.instruction != null ? e.instruction.getClass().getName() : "null";
                  String filter = "-";

                  try {
                     if (e != null && e.instruction instanceof DestinationInstruction d) {
                        filter = String.valueOf(d.getFilter());
                     }
                  } catch (Throwable var9) {
                  }

                  sb.append("#").append(i).append(" class=").append(cls).append(" filter=").append(filter).append('\n');
               }
            }
         }

         Path path = Path.of("logs", "hexietrain-mirror.txt");
         Files.createDirectories(path.getParent());
         Files.writeString(path, sb.toString());
      } catch (Throwable var10) {
      }
   }

   /** 兼容旧调用名：现在只有这一条"采纳"路径。 */
   private void onScheduleInserted(ItemStack stack, boolean ignored) {
      try {
         if (stack == null || stack.isEmpty() || this.level == null) return;
         if (!(stack.getItem() instanceof ScheduleItem)) return;
         Schedule schedule = ScheduleItem.getSchedule(this.level.registryAccess(), stack);
         if (schedule == null) {
            HexieTrain.LOGGER.warn("[mirror] 放入的不是有效时刻表");
            return;
         }
         this.adoptFromSlot(schedule);
         this.emptySlotTicks = 0;
      } catch (Throwable t) {
         HexieTrain.LOGGER.warn("[mirror] 放入异常 {}", t.toString());
      }
   }


   public void dropRebuiltSchedule() {
      try {
         ItemStack fresh = this.rebuildSchedule();
         if (fresh.isEmpty() || this.level == null) {
            return;
         }

         Containers.dropItemStack(
            this.level, (double)this.worldPosition.getX() + 0.5, (double)this.worldPosition.getY() + 0.5, (double)this.worldPosition.getZ() + 0.5, fresh
         );
      } catch (Throwable var2) {
      }
   }

   private void mirrorWatchdog() {
      try {
         if (this.mirror.size() > 0) {
            this.mirrorEmptyTicks = 0;
            return;
         }

         if (this.scheduleSlot().isEmpty()) {
            this.mirrorEmptyTicks = 0;
            return;
         }

         if (++this.mirrorEmptyTicks % 100 != 0) {
            return;
         }

         HexieTrain.LOGGER.warn("[mirror] 槽位有表但镜像为空 {} tick → 强制重读", this.mirrorEmptyTicks);
         this.lastSlotSnapshot = ItemStack.EMPTY;
         this.syncMirrorFromSlot();
      } catch (Throwable var2) {
      }
   }

   public boolean arrivedFlag() {
      return this.arrivedFlag;
   }

   public boolean probeOk() { return this.probeOk; }
   public boolean probeBackwards() { return this.probeBackwards; }
   public int probeEdges() { return this.probeEdges; }
   public double probeMillis() { return this.probeMillis; }

   public double cacheDistanceForUi() {
      return Double.isFinite(this.cacheDistShown) ? this.cacheDistShown : Double.NaN;
   }

   public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
      super.addBehaviours(behaviours);
      if (this.links == null) {
         this.links = new LinkBehaviour[SignalChannel.VALUES.length];
      }

      for (SignalChannel ch : SignalChannel.VALUES) {
         new Sided() {
            public Direction getSide() {
               return Direction.UP;
            }

            public Vec3 getSouthLocation() {
               return Vec3.ZERO;
            }
         };
         new Sided() {
            public Direction getSide() {
               return Direction.DOWN;
            }

            public Vec3 getSouthLocation() {
               return Vec3.ZERO;
            }
         };
      }
   }

   private void pushFrequency(int slot, ItemStack stack) {
      try {
         int ch = slot / 2;
         int bit = slot % 2;
         if (ch >= 0 && ch < this.links.length && this.links[ch] != null) {
            this.links[ch].setFrequency(bit == 0, stack == null ? ItemStack.EMPTY : stack);
         }
      } catch (Throwable var5) {
      }
   }

   public TrainDispatcherBlockEntity(BlockPos pos, BlockState state) {
      this((BlockEntityType<?>)HexieRegistries.TRAIN_DISPATCHER_BE.get(), pos, state);
   }

   public TrainDispatcherBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
      super(type, pos, state);

      for (int i = 0; i < FREQ_SLOTS; i++) {
         this.frequencies[i] = ItemStack.EMPTY;
      }

      for (SignalChannel ch : SignalChannel.VALUES) {
         this.wireless[ch.index()] = new WirelessChannel(this, ch);
      }
   }

   public void updateExternalInput(int ccw, int cw) {
      if (this.extCounterClockwise != ccw || this.extClockwise != cw) {
         this.extCounterClockwise = ccw;
         this.extClockwise = cw;
         this.setChanged();
      }
   }

   public int externalCounterClockwise() {
      return this.extCounterClockwise;
   }

   public int externalClockwise() {
      return this.extClockwise;
   }

   public boolean isUnbound() {
      return this.scheduleSlot.isEmpty() && !this.manualMode && this.counterClockwiseSignal() == 0 && this.clockwiseSignal() == 0;
   }

   public int counterClockwiseSignal() {
      return this.faceSignal(true);
   }

   public int clockwiseSignal() {
      return this.faceSignal(false);
   }

   /**
    * 左 / 右红石输入（手操入口）。
    *
    * <p>注意 COUNTERCLOCKWISE_POWERED / CLOCKWISE_POWERED 是**输出**（DispatcherGearbox.engage 写它们去驱动换向/道岔），
    * 不是这里要读的东西。这里必须读真实世界的红石信号，否则进不了手操。
    *
    * <p>唯一的改动是**顺延**：level.getSignal / getBestNeighborSignal 在区块未加载时会强制加载，
    * 装上 C2ME 后 ServerChunkCache.getChunk 里那个 CompletableFuture.join() 要等服务器线程自己推进，
    * 于是把自己的服务器线程等死（2026-11-17 01:14「退出后重进进不去」）。
    * 所以：没加载就先不读，等下一拍 —— 读到的永远是真实红石，语义一条没变。
    */
   /**
    * 左 / 右红石输入（手操入口）。
    *
    * <p>逆/顺是两个**不同的面**：{@code getCounterClockwiseDirection}（= FACING 本身）和
    * {@code getClockwiseDirection}（= FACING 的相对面）。所以必须各读各的 —— 以前是遍历六个方向取最大值，
    * 结果 counterClockwiseSignal() 和 clockwiseSignal() 永远是同一个数，手操时方向信息根本不存在，
    * 只能往一边走。
    *
    * <p>进手操的触发条件不变：左右任一输入有电（{@code max(ccw,cw) > 0}）。
    *
    * <p>顺延：邻居区块没就绪时这一拍不读，绝不让 level 去加载区块 ——
    * C2ME 的 ServerChunkCache.getChunk 是 CompletableFuture.join()，会把服务器线程等死
    * （2026-11-17 01:14「退出后重进进不去」）。
    */
   private int faceSignal(boolean ccw) {
      if (this.level == null) {
         return 0;
      } else {
         try {
            BlockState st = this.getBlockState();
            if (!(st.getBlock() instanceof TrainDispatcherBlock block)) {
               return 0;
            }
            Direction d = ccw ? block.getCounterClockwiseDirection(st) : block.getClockwiseDirection(st);
            BlockPos np = this.worldPosition.relative(d);
            if (!this.level.hasChunkAt(np)) {
               return 0;   // 邻居区块还没就绪：这一拍不读，顺延
            }
            return this.level.getSignal(np, d);
         } catch (Throwable t) {
            return 0;
         }
      }
   }

   private Direction horizontalFacing() {
      Direction facing = (Direction)this.getBlockState().getValue(TrainDispatcherBlock.FACING);
      return facing.getAxis().isHorizontal() ? facing : null;
   }

   public float getRotationSpeedModifier(Direction face) {
      if (face == this.getSourceFacing()) {
         return 1.0F;
      } else {
         boolean ccw = (Boolean)this.getBlockState().getValue(TrainDispatcherBlock.COUNTERCLOCKWISE_POWERED);
         boolean cw = (Boolean)this.getBlockState().getValue(TrainDispatcherBlock.CLOCKWISE_POWERED);
         if (ccw && cw) {
            return 0.0F;
         } else if (ccw) {
            return 1.0F;
         } else {
            return cw ? -1.0F : 0.0F;
         }
      }
   }

   public void tick() {
      super.tick();
      if (this.level != null && !this.level.isClientSide && !ACTIVE.contains(this)) {
         ACTIVE.add(this);
      }
   }

   public void onRedstoneChanged() {
      if (this.boundTrain == null) {
      }

      this.setChanged();
   }

   public String currentTargetName() {
      try {
         if (this.boundTrain != null) {
            Train tr = (Train)Create.RAILWAYS.trains.get(this.boundTrain);
            if (tr != null && tr.navigation != null && tr.navigation.destination != null) {
               return tr.navigation.destination.name;
            }
         }
      } catch (Throwable var2) {
      }

      return null;
   }

   public List<String> stopNames() {
      return this.stopNames;
   }

   public void refreshStopNames() {
      this.stopNames.clear();

      try {
         if (this.mirror.size() > 0) {
            for (int i = 0; i < this.mirror.size(); i++) {
               String n = this.mirror.targetName(i);
               this.stopNames.add(n != null && !n.isBlank() ? n : "（未命名）");
            }

            return;
         }
      } catch (Throwable var7) {
      }

      if (this.level != null && !this.scheduleSlot.isEmpty() && this.scheduleSlot.getItem() instanceof ScheduleItem) {
         try {
            Schedule schedule = ScheduleItem.getSchedule(this.level.registryAccess(), this.scheduleSlot);
            if (schedule != null && schedule.entries != null) {
               for (ScheduleEntry entry : schedule.entries) {
                  if (entry != null && entry.instruction instanceof DestinationInstruction dest) {
                     String name = dest.getFilter();
                     this.stopNames.add(name != null && !name.isBlank() ? name : "（未命名）");
                  } else {
                     this.stopNames.add("（非车站指令）");
                  }
               }
            }
         } catch (Throwable var6) {
         }
      }

      this.sendData();
   }

   public boolean shouldInjectSpeed() {
      return true;   // 解耦已废弃：永远是物理车在跑，速度永远注入
   }

   public double physicalSpeedPerTick() {
      try {
         return this.bodySpeedAlongTrackBps() / 20.0;
      } catch (Throwable var2) {
         return 0.0;
      }
   }


   public boolean manualMode() {
      return this.manualMode;
   }

   /** 转向架绑定表（调度器级）。 */


   /** 路线到底盖住整列车没有（只在覆盖状态变化时记一行，避免重规划时刷屏）。 */
   private void logRouteEnds(TravellingPoint endA, TravellingPoint endB, RailCache.Route route) {
      boolean aOn = endA != null && endA.edge != null && route.legOf(endA.edge) != null;
      boolean bOn = endB != null && endB.edge != null && route.legOf(endB.edge) != null;
      int state = (aOn ? 1 : 0) | (bOn ? 2 : 0);
      if (state == this.lastCoverState) {
         return;
      }
      this.lastCoverState = state;
      HexieTrain.LOGGER.info("[nav] 路线两端：首端={} 尾端={} 整车覆盖={}", aOn ? "在线" : "离线",
         bOn ? "在线" : "离线", aOn && bOn ? "是" : "否");
   }

   /** 绑定表 → 实体清单（按列车主轴排序），给 TrainBinder 造车厢用。区块没加载的跳过。 */
   /**
    * 绑定表里所有转向架的操舵轴行走点。
    *
    * <p>用途是<b>测量</b>沿轨前后顺序（RailSearch 用路线的弧长 s 挑出真正的首尾）；
    * 导航本身只用挑出来的那两端，中间这些不参与判定。
    */
   /** 某个位置的转向架操舵轴行走点（副本）；区块没加载或不是转向架返回 null。 */
   private TravellingPoint pointOf(BlockPos p) {
      if (p == null || this.level == null || !this.level.hasChunkAt(p)) return null;
      return this.level.getBlockEntity(p) instanceof PhysicsBogeyBlockEntity b ? CreateApi.pointOf(b) : null;
   }

   private List<TravellingPoint> bogeyPoints() {
      List<TravellingPoint> out = new ArrayList<>();
      for (PhysicsBogeyBlockEntity b : this.rosterBogeys()) {
         TravellingPoint p = CreateApi.pointOf(b);
         if (p != null && p.edge != null) out.add(p);
      }
      return out;
   }

   /**
    * 沿轨串链：用当前路线的弧长 s 给所有绑定转向架排序。
    * <b>s 最小 = 后端</b>（route 的 s=0，就是拿它当起点搜出来的那端），
    * <b>s 最大 = 领先端</b>（距站只认它）。
    *
    * <p>为什么不用 BogeyRoster.ordered()：那是世界主轴排序，斜线/弯道/多车厢时不代表列车前后。
    * s 是沿轨弧长，单调递增，这才是物理上的前后顺序。
    *
    * <p>任何一根算不出 s（不在这条路线上）就整体不动 —— 不猜。
    * RailSearch 已经用同一条判据验过，所以正常不会走到这里。
    *
    * <p><b>换端不再作废面标定。</b>标定存的是"这个面把车推向哪个物理端"
    * （{@link #dirBogey}，一个转向架坐标），跟路线朝哪、s 轴怎么摆都无关，
    * 所以换端只需要重算 {@link #activeFace}，不需要重标。
    */
   private void refreshNavEnds(TrackGraph g) {
      if (this.driveResult == null || !this.driveResult.ok || this.driveResult.route == null) return;
      List<PhysicsBogeyBlockEntity> bs = this.rosterBogeys();
      if (bs.isEmpty()) return;
      BlockPos tail = null; BlockPos head = null;
      double sMin = Double.POSITIVE_INFINITY; double sMax = Double.NEGATIVE_INFINITY;
      boolean any = false;
      for (PhysicsBogeyBlockEntity b : bs) {
         TravellingPoint p = CreateApi.pointOf(b);
         if (p == null || p.edge == null) continue;
         double s = this.driveResult.route.sOf(p.edge, p.position);
         if (!Double.isFinite(s)) {
            // 严格：只要有一根算不出弧长，这次串链整体作废，保持上一次的两端不动。
            return;
         }
         any = true;
         if (s < sMin) { sMin = s; tail = b.getBlockPos(); }
         if (s > sMax) { sMax = s; head = b.getBlockPos(); }
      }
      if (!any) return;
      if (tail == null || head == null) return;
      // 两根以上转向架却算出同一个端 = 串链退化，不能当结果用
      if (bs.size() > 1 && java.util.Objects.equals(tail, head)) return;
      boolean changed = !java.util.Objects.equals(tail, this.navTailPos)
         || !java.util.Objects.equals(head, this.navHeadPos);
      this.navTailPos = tail;
      this.navHeadPos = head;
      if (changed) {
         HexieTrain.LOGGER.info("[nav] 沿轨串链：后端={} 领先端={} 弧长跨度={}m（面标定不作废）",
            new Object[]{tail.toShortString(), head.toShortString(), String.format("%.2f", sMax - sMin)});
      }
      // 标定的那个物理端被解绑/拆掉了 ⇒ 标定作废。方向事实依附于那个转向架，它没了就没了。
      if (this.dirSign != 0 && this.dirBogey != null && !this.roster.contains(this.dirBogey)) {
         HexieTrain.LOGGER.info("[nav] 标定端 {} 已不在绑定表里 ⇒ 面标定作废重标",
            this.dirBogey.toShortString());
         invalidateDirection();
      }
   }

   private List<PhysicsBogeyBlockEntity> rosterBogeys() {
      List<PhysicsBogeyBlockEntity> out = new ArrayList<>();
      if (this.level == null) return out;
      for (BlockPos p : this.roster.ordered()) {
         if (!this.level.hasChunkAt(p)) continue;
         if (this.level.getBlockEntity(p) instanceof PhysicsBogeyBlockEntity b && !b.isRemoved()) out.add(b);
      }
      return out;
   }

   public BogeyRoster roster() {
      return this.roster;
   }

   /** 已绑定的转向架数量，UI 用。 */
   public int bogeyCount() {
      return this.roster.size();
   }

   /** 列车车厢数（一个 Sable 子层 = 一节车厢）。0 = 还没绑。由服务端数好随包同步。 */
   public int rosterCars() {
      return this.rosterCars;
   }

   /**
    * 数车厢：roster 里每根转向架问一次"你属于哪个子层"，不同子层 = 不同车厢。
    *
    * <p>为什么用子层而不猜间距：Simurail 里根本没有"车厢"这个概念，Create 的 Carriage 是
    * TrainBinder 按子层 UUID 分组造出来的（见 TrainBinder 145-163 行）。一个子层就是一节车厢，
    * 这是这个物理体系里唯一客观的车厢边界，不存在"多长算一节"的猜测。
    */
   private int countRosterCars() {
      if (!(this.level instanceof ServerLevel sl) || this.roster.positions().isEmpty()) {
         return 0;
      }
      Set<UUID> cars = new HashSet<>();
      for (BlockPos bp : this.roster.positions()) {
         UUID key = null;
         try {
            SubLevel sub = Sable.HELPER.getContaining(sl, bp);
            if (sub != null) {
               key = sub.getUniqueId();
            }
         } catch (Throwable t) {
         }
         if (key == null) {
            key = UUID.nameUUIDFromBytes(("bogey:" + bp.asLong()).getBytes(StandardCharsets.UTF_8));
         }
         cars.add(key);
      }
      return cars.size();
   }

   public ItemStack takeRebuiltSchedule() {
      ItemStack source = this.scheduleSlot.copy();
      if (source.isEmpty()) {
         return ItemStack.EMPTY;
      } else {
         try {
            CompoundTag blob = this.mirror.blob();
            if (blob != null) {
               CompoundTag copy = blob.copy();
               if (this.myEntry > 0) {
                  copy.putInt("Progress", Math.max(1, this.myEntry));
               } else {
                  copy.remove("Progress");
               }
               source.set(AllDataComponents.TRAIN_SCHEDULE, copy);
            }
         } catch (Throwable var4) {
         }

         this.scheduleSlot = ItemStack.EMPTY;
         this.mirror.clear();
         this.stopNames.clear();
         this.myEntry = 0;
         this.setChanged();
         this.sendData();
         return source;
      }
   }

   public void forceAuto() {
      this.extCounterClockwise = 0;
      this.extClockwise = 0;
      this.manualMode = false;
      this.setChanged();
      this.sendData();
      this.setManualMode(false);
   }

   public void setManualMode(boolean value) {
      this.manualMode = value;
      if (this.level != null && !this.level.isClientSide && this.boundTrain != null) {
         try {
            if (this.boundTrain != null) {
               Train trD = (Train)Create.RAILWAYS.trains.get(this.boundTrain);
               if (trD != null) {
                  if (this.level instanceof ServerLevel slR) TrainBinder.reanchor(trD, slR, this.worldPosition);
               }
            }
         } catch (Throwable var6) {
         }

         if (this.scheduleSlot.isEmpty() && this.counterClockwiseSignal() == 0 && this.clockwiseSignal() == 0) {
            this.SetOutputBrake(0);

            try {
               this.detachKinetics();
            } catch (Throwable var4) {
            }

            this.setDispatcherState(DispatcherState.IDLE);
            return;
         }

         Train train = (Train)Create.RAILWAYS.trains.get(this.boundTrain);
         this.probe(train);
         if (train != null) {
            try {
               if (value) {
                  train.navigation.cancelNavigation();
               } else {
                  train.navigation.cancelNavigation();
                  train.manualTick = false;
                  train.targetSpeed = 0.0;
                  train.targetSpeed = 0.0;
               }
            } catch (Throwable var5) {
            }
         }
      }

      this.setChanged();
      this.sendData();
      this.syncTrainPresence();
   }

   public String nextTargetName() {
      return this.nextTargetName;
   }

   public double distanceToTarget() {
      return this.lastDistance;
   }

   public Component getDisplayName() {
      return Component.translatable("block.hexie_auto_simuroute.train_dispatcher");
   }

   public void serverTick() {
      if (this.level != null && !this.level.isClientSide) {
         // ===== 顺延：这个方块自己的区块还没就绪，这一拍整段程序都不跑 =====
         // DispatcherTicker 是全局 ServerTickEvent（遍历所有调度器），不看区块加载状态。
         // 世界刚起来时区块还没准备好，硬跑会在 level.getSignal / getBlockState 里触发区块加载，
         // 而 C2ME 的 ServerChunkCache.getChunk 是 CompletableFuture.join()，它要等服务器线程
         // 自己推进，于是服务器线程把自己等死，世界永远进不去（2026-11-17 01:14 那次）。
         // 顺延就好：没就绪这一拍不跑，就绪之后下一拍自然跑起来。语义一条没改。
         if (!this.level.hasChunkAt(this.worldPosition)) {
            return;
         }
         // ================================================================
         // 三层，越靠前优先越高。哪一层命中就由它独占这一拍的输出，
         // 剩下的层一律不执行 —— 绝不允许两个层同时写同一个通道。
         // ================================================================

         // 层 0：解绑（没有时刻表）⇒ 全部失效
         // 解绑不是说"马上停车"，而是"这个调度器彻底退出"：油门、制动、转向遥控
         // 全部置 0，一个信号都不再发。车还在跑是物理惯性，与我们无关。
         if (this.scheduleSlot.isEmpty()) {
            if (this.boundTrain != null) {
               this.dissolve();
            }
            // 解绑 = 全部失效：距站、调度状态、路线、阶段、到站标记一个都不许留。
            // 以前这里直接 return，syncTrainPresence/resetRuntimeState 永远跑不到，
            // 于是 UI 上的「距站」和「调度：xx」一直停在解绑前的值。
            this.driveResult = null;
            this.driveTarget = null;
            this.cacheDistShown = Double.NaN;
            this.legRemaining = -1.0;
            this.arrivedFlag = false;
            this.driveHalted = false;
            this.drivePhase = PH_IDLE;
            this.throttleCmd = 0;
            this.brakeCmd = 0;
            this.setDispatcherState(DispatcherState.IDLE);
            this.releaseEverything();
            return;
         }

         // 层 1：手动 ⇒ 只认侧面红石，导航/时刻表/绑车状态全都不参与
         if (this.manualMode) {
            this.tickManualGearbox();
            return;
         }

         // 层 2：自动 ⇒ 走下面的状态机（driveTick）
         try {
            if (Create.RAILWAYS == null || Create.RAILWAYS.trackNetworks == null || Create.RAILWAYS.trackNetworks.isEmpty()) {
               return;
            }
         } catch (Throwable var13) {
            return;
         }

         this.refreshWireless();
         this.loadOwnRecordOnce();

         // ===== 转向架绑定探针：只读快照，不参与任何决策（见 BogeyBinding）=====
         // 未成功：每 20 tick 试（等绑定）；成功后：每 1200 tick 复查签名，编组一变就重打
         if (this.bindProbeTicks++ % (this.bindProbeDone ? 1200 : 20) == 0) {
            try {
               if (BogeyBinding.probe((ServerLevel)this.level, this.worldPosition)) {
                  this.bindProbeDone = true;
               }
            } catch (Throwable t) {
               this.bindProbeDone = true;   // 一次性自禁用，不刷屏
               HexieTrain.LOGGER.warn("[bind] 探针异常，已关闭：{}", t.toString());
            }
         }
         if (this.boundTrain != null) {
            TrainDispatcherBlockEntity owner = TRAIN_OWNER.get(this.boundTrain);
            if (owner == null) {
               TRAIN_OWNER.put(this.boundTrain, this);
               owner = this;
            }

            if (owner != this) {
               if (!owner.isRemoved() && owner.getLevel() != null) {
                  return;
               }

               TRAIN_OWNER.put(this.boundTrain, this);
            }
         }

         // ===== 逐转向架道岔子系统：导航中每个绑定转向架各自判定，不在导航就全部放直行 =====
         try {
            BogeyRoster rosterNow = this.roster;
            if (!this.manualMode && !rosterNow.positions().isEmpty()
                  && this.driveResult != null && this.driveResult.ok && this.driveResult.route != null) {
               this.steering.tick((ServerLevel)this.level, this.worldPosition, rosterNow, this.driveResult.route);
            } else {
               this.steering.releaseAll((ServerLevel)this.level, this.worldPosition, rosterNow);
            }
         } catch (Throwable t) {
            HexieTrain.LOGGER.warn("[steer] 子系统异常 {}", t.toString());
         }


         if (this.level instanceof ServerLevel slSt && this.assemblyId != null && this.stateProbeTicks++ % 20 == 0) {
            Train st = this.boundTrain == null ? null : (Train)Create.RAILWAYS.trains.get(this.boundTrain);
            String mode = this.manualMode ? "MANUAL" : (this.boundTrain == null ? "IDLE" : "AUTO");
            String dest = st != null && st.navigation != null && st.navigation.destination != null ? st.navigation.destination.name : "-";
            double sp = 0.0;

            try {
               sp = Math.abs(this.measuredSpeedMps());
            } catch (Throwable var9) {
            }

            TrainBindings.writeState(
               slSt,
               this.assemblyId,
               sp,
               (double)this.worldPosition.getX() + 0.5,
               (double)this.worldPosition.getY() + 0.5,
               (double)this.worldPosition.getZ() + 0.5,
               mode,
               dest,
               this.legRemaining
            );
         }

         this.updateMeasuredSpeed();
         this.syncMirrorFromSlot();
         this.mirrorWatchdog();

         try {
            try {
               if (!this.manualMode) {
                  Train trP = this.boundTrain == null ? null : (Train)Create.RAILWAYS.trains.get(this.boundTrain);
                  if (trP != null && trP.navigation != null && trP.runtime != null && trP.runtime.schedule != null && !trP.runtime.schedule.entries.isEmpty()) {
                     boolean entryChanged = this.myEntry != this.lastAcquiredEntry;
                     boolean lostFor = trP.navigation.destination == null && this.level.getGameTime() - this.lastAcquireTick > 60L;
                     if (entryChanged || lostFor) {
                        this.lastAcquireTick = this.level.getGameTime();
                        this.lastAcquiredEntry = this.myEntry;
                        HexieTrain.LOGGER.info("[mirror] 目标 entry={} station={}", this.myEntry, this.mirror.targetName(this.myEntry));
                     }
                  }
               }
            } catch (Throwable var11) {
            }

            try {
               if (!this.manualMode && this.level instanceof ServerLevel slTP) {
                  Train trA = this.boundTrain == null ? null : (Train)Create.RAILWAYS.trains.get(this.boundTrain);
                  if (trA != null && !trA.carriages.isEmpty()) {
                     TrainBinder.reanchor(trA, slTP, this.worldPosition);
                  }
               }
            } catch (Throwable var8) {
            }

            try {
               Train trC = this.boundTrain == null ? null : (Train)Create.RAILWAYS.trains.get(this.boundTrain);
               if (trC != null) {
                  for (Carriage cC : trC.carriages) {
                     if (cC.presentConductors != null) {
                        cC.presentConductors.replace(v -> Boolean.TRUE);
                     }
                  }
               }
            } catch (Throwable var10) {
            }

            if (this.boundTrain != null && !Create.RAILWAYS.trains.containsKey(this.boundTrain)) {
               if (this.missingTicks % 200 == 0) {
                  HexieTrain.LOGGER.warn("[HexieTrain][run] RAILWAYS 缺列车 {}（{} tick）", this.boundTrain, this.missingTicks);
               }

               if (this.missingTicks++ > 100) {
                  this.boundTrain = null;
                  this.missingTicks = 0;
               }

               return;
            }
         } catch (Throwable var12) {
            HexieTrain.LOGGER.warn("[HexieTrain][run] 自检异常 {}", var12.toString());
         }

         if (this.presenceTicks++ % 20 == 0) {
            this.syncTrainPresence();
         }

         boolean autoAllowed = !this.manualMode && !this.isUnbound() && !this.scheduleSlot.isEmpty();
         if (autoAllowed && this.boundTrain != null) {
            this.tickAutomated();
         } else {
            this.tickManualGearbox();
         }
      }
   }

   private void tickAutomated() {
      if (this.autoLogTicks++ % 40 == 0) {
         HexieTrain.LOGGER
            .info(
               "[auto] 判定 manual={} unbound={} slotEmpty={} bound={}",
               new Object[]{this.manualMode, this.isUnbound(), this.scheduleSlot.isEmpty(), this.boundTrain != null}
            );
      }

      try {
         Train train = (Train)Create.RAILWAYS.trains.get(this.boundTrain);
         if (train == null) {
            return;
         }

         if (this.gateProbeTicks++ % 20 == 0) {
            HexieTrain.LOGGER
               .info(
                  "[gate2] primary={} invalid={} derailed={} carriages={} bound={}",
                  new Object[]{
                     this.isPrimaryDispatcher(),
                     train.invalid,
                     train.derailed,
                     train.carriages.size(),
                     this.boundTrain == null ? "-" : this.boundTrain.toString().substring(0, 8)
                  }
               );
         }

         if (!this.isPrimaryDispatcher()) {
            return;
         }

         try {
            Navigation nv = train.navigation;
            String dn = nv.destination == null ? "null" : nv.destination.name;
            if (train.runtime.currentEntry != this.lastEntryLogged) {
               HexieTrain.LOGGER.info("[HexieTrain][evt] entry {} → {}", this.lastEntryLogged, train.runtime.currentEntry);
               this.lastEntryLogged = train.runtime.currentEntry;
            }

            if (!dn.equals(this.lastDestName)) {
               HexieTrain.LOGGER.info("[HexieTrain][evt] dest {} → {}", this.lastDestName, dn);
               this.lastDestName = dn;
               if (nv.destination != null) {
                  this.pendingLeg = Math.max(0.0, nv.distanceToDestination);
               }
            }

            if (this.diagTicks++ % 20 == 0) {
               HexieTrain.LOGGER
                  .info(
                     "[HexieTrain][FULL] spd={} tgt={} inv={} inRAIL={} dest={} dDest={} behind={} state={} spacing={} spos={} | {}",
                     new Object[]{
                        String.format("%.3f", train.speed),
                        String.format("%.3f", train.targetSpeed),
                        train.invalid,
                        Create.RAILWAYS.trains.containsKey(train.id),
                        train.navigation.destination != null,
                        String.format("%.1f", train.navigation.distanceToDestination),
                        train.navigation.destinationBehindTrain,
                        train.runtime.state,
                        train.carriages.isEmpty() ? -1 : ((Carriage)train.carriages.get(0)).bogeySpacing,
                        this.level instanceof ServerLevel slD ? SubLevelSuspend.worldCenter(slD, this.worldPosition) : null,
                        TrainBinder.anchorProbe
                     }
                  );
               HexieTrain.LOGGER
                  .info(
                     "[HexieTrain][diag] st={} entry={} dest={} dDest={} behind={} spd={} vphys={} vtrack={} leg={} vpos={} spos={} vdist={} wc={}",
                     new Object[]{
                        train.runtime.state,
                        train.runtime.currentEntry,
                        dn,
                        String.format("%.1f", nv.distanceToDestination),
                        nv.destinationBehindTrain,
                        String.format("%.3f", train.speed),
                        String.format("%.2f", this.measuredSpeedMps()),
                        String.format("%.2f", this.bodySpeedAlongTrackBps()),
                        String.format("%.1f", this.legRemaining),
                        this.virtPos(train),
                        this.statPos(nv.destination),
                        this.virtDist(train, nv.destination),
                        SubLevelSuspend.worldCenter((ServerLevel)this.level, this.worldPosition).toString()
                           + " disp="
                           + this.worldPosition.toShortString()
                           + " tid="
                           + train.id.toString().substring(0, 8)
                           + " edgeLen="
                           + String.format("%.1f", this.edgeLen(train))
                           + " edgePos="
                           + String.format("%.1f", this.edgePos(train))
                           + " bound="
                           + (this.boundTrain == null ? "null" : this.boundTrain.toString().substring(0, 8))
                     }
                  );
            }
         } catch (Throwable var20) {
            HexieTrain.LOGGER.warn("[HexieTrain][diag] {}", var20.toString());
         }

         int mL = this.counterClockwiseSignal();
         int mR = this.clockwiseSignal();
         this.updateExternalInput(mL, mR);
         if (this.manualMode || mL > 0 || mR > 0) {
            if (mL <= 0 && mR <= 0) {
               this.SetOutputBrake(15);
               this.setOutput(SignalChannel.THROTTLE, 0);
            } else {
               this.SetOutputBrake(0);
               this.setOutput(SignalChannel.THROTTLE, Math.max(mL, mR));
               if (!this.manualMode) {
                  this.manualMode = true;
                  this.setChanged();
                  this.sendData();
               }

               double mDir = mL > 0 ? 1.0 : -1.0;
               double mMag = (double)Math.max(mL, mR) / 15.0;
               train.manualTick = true;
               train.targetSpeed = mDir * mMag * 0.25;
               train.approachTargetSpeed(1.0F);
            }

            return;
         }

         int ccw = this.counterClockwiseSignal();
         int cw = this.clockwiseSignal();
         this.updateExternalInput(ccw, cw);
         boolean redstonePresent = ccw > 0 || cw > 0;
         if (redstonePresent) {
            if (!this.manualMode) {
               this.manualMode = true;
               this.setChanged();
               this.sendData();
            }

            this.setDispatcherState(DispatcherState.RUNNING);
            double dir = ccw > 0 ? 1.0 : -1.0;
            double mag = (double)Math.max(ccw, cw) / 15.0;
            train.manualTick = true;
            train.targetSpeed = dir * mag * 0.25;
            train.approachTargetSpeed(1.0F);
            return;
         }

         if (this.manualMode) {
            this.setDispatcherState(DispatcherState.IDLE);
            this.SetOutputBrake(0);
            return;
         }

         // 铁律 2：Create 的虚拟车永远不给速度（解耦/虚拟车进站那套已废弃删除）
         train.speed = 0.0;
         train.targetSpeed = 0.0;

         // 第④步：驱动暂缓。关着的时候只跑只读诊断，不产生任何牵引。
         if (DRIVE_ENABLED) {
            this.driveTick(train);
         } else {
            this.neutralOutputs();
         }

         if (this.level instanceof ServerLevel slA) {
            // 铁律 2：Create 侧速度永远压 0，物理速度由 TrainSpeedInjectMixin 注入
            train.speed = 0.0;
            train.targetSpeed = 0.0;
            TrainBinder.reanchor(train, slA, this.worldPosition);
         }

         try {
            if (DRIVE_ENABLED) {
               // 状态由 driveTick 的阶段机决定，这里不再派生覆盖
            } else if (!Double.isFinite(this.cacheDistShown)) {
               this.setDispatcherState(DispatcherState.IDLE);
            } else if (this.output(SignalChannel.BRAKE) > 0) {
               this.setDispatcherState(DispatcherState.BRAKING);
         } else if (Math.abs(this.measuredSpeedMps()) > 0.1) {
               this.setDispatcherState(DispatcherState.COASTING);
            } else {
               this.setDispatcherState(DispatcherState.RUNNING);
            }
         } catch (Throwable var21) {
         }
      } catch (Throwable var25) {
         HexieTrain.LOGGER.warn("[HexieTrain][auto] 异常: {}", var25.toString());
      }
   }



   /**
    * Create 又把 navigation.destination 设回来了（mixin 每 tick 清一次，这里只做计数+限流报告）。
    *
    * <p>为什么必须清干净：{@code Navigation.control()} 只在 {@code destination == null} 时
    * 才用 {@code train.manualSteer}；一旦它非空，Create 就按它自己的 currentPath 选岔，
    * 我们打的方向等于白打。driveTick 里那份 5 tick 清一次是补漏，主清扫在 mixin。
    */
   public void noteNavCleared() {
      this.navClearedTicks++;
      if (this.navClearedTicks <= 3 || this.navClearedTicks % 400 == 0) {
         HexieTrain.LOGGER.info("[nav] Create 又设了 destination，已清 {} 次（它在抢导航，抢走就见不到 manualSteer 生效）",
            this.navClearedTicks);
      }
   }



   /**
    * 第④步：驱动（受控试验版）。
    *
    * 目标不是"一把开好"，而是把两个还不能猜的量暴露出来：
    *   a) 哪一面是前进 —— 激活一面，看距站是不是在变小；连续 3 次变大就翻面，只翻一次
    *   b) 油门值 → 速度 —— 打 [drive] 日志，看 throttle 和 v 的对应
    *
    * 硬上限：每段最多 400 次决策（每次 5 tick，约 100 秒），到上限强制刹车并停手。
    */
   private void driveTick(Train train) {
      if (this.driveGateTicks++ % 5 != 0) return;
      this.idleLogTicks++;

      // 我们不用 Create 的导航，所以清掉它遗留的 destination：
      // Train.tick 里 ScheduleRuntime 有 `if (destination != null) return;`，
      // 留着它会让"等 N 秒"这类条件的 tick 计数几乎不涨（实测 5 秒变 24 秒）。
      try {
         if (train != null && train.navigation != null && train.navigation.destination != null) {
            train.navigation.destination = null;
            train.navigation.destinationBehindTrain = false;
            if (this.idleLogTicks % 20 == 0) HexieTrain.LOGGER.info("[nav] 清掉遗留 navigation.destination（我们的导航不用它）");
         }
      } catch (Throwable tD) {
      }

      boolean canDrive = train != null && train.graph != null && !this.manualMode() && this.boundTrain != null;
      if (this.scheduleDone) {
         if (this.idleLogTicks % 20 == 0) HexieTrain.LOGGER.info("[drive] 空转 原因=时刻表已走完 entry={}", this.myEntry);
         this.holdOutputs();   // 时刻表走完：抱闸停在最后一站，不松闸
         return;
      }
      String target = canDrive ? this.mirror.targetName(this.myEntry) : null;
      GlobalStation st = (canDrive && target != null) ? CreateApi.stationNamed(train.graph, target) : null;
      if (st == null) {
         if (this.idleLogTicks % 20 == 0) HexieTrain.LOGGER.info("[drive] 空转 原因=站点未就绪 target={} entry={} bound={}", target, this.myEntry, this.boundTrain);
         this.phaseIdle();
         return;
      }
      if (this.driveHalted) {
         // 只有换到新的一段（目标变了）才解除，否则一直抱闸。
         // 注意这个判断必须在算出 target 之后：放在前面就永远看不到"换段"，会死锁。
         // 解除条件：目标变了**或者**时刻表 entry 变了（后者能打破"抱闸→到不了站→entry 不变"的死循环）
         boolean legChanged = (this.haltEntry != this.myEntry)
            || (target != null && !target.equals(this.driveTarget));
         if (legChanged) {
            this.haltEntry = -1;
            this.driveHalted = false;
            HexieTrain.LOGGER.info("[drive] 目标变为 {}，解除看门狗停手", target);
         } else {
            if (this.idleLogTicks % 20 == 0) HexieTrain.LOGGER.info("[drive] 空转 原因=看门狗停手（换段才解除）target={} entry={}", this.driveTarget, this.myEntry);
            this.holdOutputs();
            return;
         }
      }



      // ===== 两端：沿轨串链得出的<b>后端</b> / <b>领先端</b>，只这两个点 =====
      // 后端   = 寻路起点（route 的 s=0），由 RailSearch 两端各搜一次决定，这里不推方向
      // 领先端 = route 上 s 最大的转向架，距站只认它
      TravellingPoint navPt = this.pointOf(this.navTailPos);
      if (navPt == null) {
         List<TravellingPoint> seed = this.bogeyPoints();
         navPt = seed.isEmpty() ? CreateApi.leadPoint(train) : seed.get(0);
      }
      TravellingPoint pt = this.pointOf(this.navHeadPos);
      if (pt == null) pt = navPt;
      double s = this.routeS(pt);
      boolean needPlan = this.driveResult == null || !this.driveResult.ok
         || !target.equals(this.driveTarget) || !Double.isFinite(s);

      // 冷却中：抱闸等。一次失败要跑最多 N 次寻路，每拍重来会把服务端线程堵死、日志刷爆。
      // 换站（target 变了）不受冷却限制，那是新的合法请求。
      if (needPlan && this.planCooldown > 0 && target.equals(this.driveTarget)) {
         this.planCooldown--;
         this.holdOutputs();
         return;
      }

      if (needPlan) {
         long planT0 = System.nanoTime();
         // 上一段用的起点端：两端都合格（环线）时优先保持它，免得来回换向抽搐
         TravellingPoint preferred = this.driveResult == null ? null : this.driveResult.startPoint;
         this.driveResult = RailSearch.searchTrainWide(train.graph, train, st, target, 4096.0,
            this.bogeyPoints(), preferred);
         if (this.driveResult.ok && this.driveResult.route != null) {
            this.refreshNavEnds(train.graph);            // 用新路线重新串链
            navPt = this.pointOf(this.navTailPos);
            if (navPt == null) navPt = CreateApi.leadPoint(train);
            pt = this.pointOf(this.navHeadPos);
            if (pt == null) pt = navPt;
         }
         if (this.driveResult.ok && this.driveResult.route != null) {
            this.logRouteEnds(this.pointOf(this.navTailPos), this.pointOf(this.navHeadPos), this.driveResult.route);
         }
         this.probeMillis = (System.nanoTime() - planT0) / 1.0e6;   // 规划耗时，UI 可看
         this.probeOk = this.driveResult.ok;
         this.probeEdges = this.driveResult.ok ? this.driveResult.edges : 0;
         this.probeBackwards = this.driveResult.ok && !this.driveResult.towardNode2;   // 纯诊断，不参与选面
         if (!target.equals(this.driveTarget)) {   // 换段 ⇒ 解除看门狗 + 清到站标记
            this.planCooldown = 0;
            this.driveHalted = false;
            this.arrivedFlag = false;
         }
         this.driveTarget = target;
         this.driveTicks = 0;
         this.throttleCmd = 0;
         this.coastTicks = 0;
         // 路线换了 ⇒ 弧长坐标系换了 ⇒ 上一拍的 s 和标定基线都作废
         this.lastRouteS = Double.NaN;
         this.calS0 = Double.NaN;
         this.calEdge = null;
         this.calPhase = this.dirSign != 0 ? 3 : 0;   // 已标定的不必重标，只重取基线以外的东西
         if (this.drivePhase == PH_IDLE) this.drivePhase = PH_RUN;
         HexieTrain.LOGGER.info("[drive] 新段 target={} entry={} | {}", new Object[]{target, this.myEntry, this.driveResult.describe()});
         if (!this.driveResult.ok) {
            // 规划不成立就抱闸，并把原因说清楚。绝不许"照旧往前开"。
            this.planCooldown = 10;
            HexieTrain.LOGGER.warn("[drive] 规划失败，抱闸停住：{}", this.driveResult.note);
            this.cacheDistShown = Double.NaN;
            this.phaseIdle();
            this.holdOutputs();
            return;
         }
      }

      // ===== 面标定：必须排在规划之后 =====
      // 标定要量的就是"这个面推着车往 route 的 s 增大还是减小走"。没有 route 就没有 s，
      // 量不出来。以前把标定排在规划前，只能拿"边上的位置"凑，而边的 node1→node2
      // 是每条边各自的任意编号，跟路线方向毫无关系 —— 方向错就错在这里。
      if (!this.ensureDirection(train)) {
         if (this.idleLogTicks % 20 == 0) {
            HexieTrain.LOGGER.info("[drive] 空转 原因=标定中/等静止 target={} entry={} dirSign={} calPhase={}",
               new Object[]{target, this.myEntry, this.dirSign, this.calPhase});
         }
         return;
      }

      // ===== 每拍重算驱动面 =====
      // 要的是「朝领先端走」。标定给出的是「哪个面把车推向哪个物理端」，
      // 所以只需比一比领先端是不是那个端 —— 与路线朝 node1/node2、与 s 轴怎么摆都无关。
      if (this.dirBogey == null || this.navHeadPos == null) {
         this.holdOutputs();
         if (this.idleLogTicks % 20 == 0) {
            HexieTrain.LOGGER.warn("[drive] 空转 原因=标定端或领先端缺失 dirBogey={} 领先端={}",
               new Object[]{this.dirBogey == null ? "-" : this.dirBogey.toShortString(),
                  this.navHeadPos == null ? "-" : this.navHeadPos.toShortString()});
         }
         return;
      }
      int faceNow = this.navHeadPos.equals(this.dirBogey) ? this.dirFace : flipFace(this.dirFace);
      if (faceNow != this.activeFace) {
         HexieTrain.LOGGER.info("[dir] 领先端={} 标定推向={} ⇒ activeFace {} → {}（dirFace={} dirSign={}）", new Object[]{
            this.navHeadPos.toShortString(), this.dirBogey.toShortString(),
            this.activeFace, faceNow, this.dirFace, this.dirSign});
      }
      this.activeFace = faceNow;

      RailCache.Route route = this.driveResult.route;
      if (pt == null) { this.phaseIdle(); this.driveResult = null; return; }

      // 减速：用缓存弧长算（便宜、不权威，算错只是快慢问题）
      double sNow = route.sOf(pt.edge, pt.position);
      // 硬不变量：给着油门，领先端的 s 只许增大。
      // 减小 = activeFace 选反了 = 车正在往车站的反方向开。这不是"慢一点"的问题，
      // 是方向事实错了 —— 作废标定、抱闸、下一拍重标，绝不许继续推着它开。
      if (Double.isFinite(this.lastRouteS) && Double.isFinite(sNow)
            && sNow < this.lastRouteS - 0.02 && this.output(SignalChannel.THROTTLE) > 0) {
         HexieTrain.LOGGER.warn("[dir] 领先端 s 倒退 {} → {}（activeFace={} dirFace={} 标定推向={} 领先端={} 后端={}）"
            + " ⇒ 面选反，作废标定并抱闸重标", new Object[]{
            String.format("%.2f", this.lastRouteS), String.format("%.2f", sNow),
            this.activeFace, this.dirFace,
            this.dirBogey == null ? "-" : this.dirBogey.toShortString(),
            this.navHeadPos.toShortString(),
            this.navTailPos == null ? "-" : this.navTailPos.toShortString()});
         invalidateDirection();
         this.calCooldown = 20;
         this.holdOutputs();
         return;
      }
      this.lastRouteS = sNow;
      if (!Double.isFinite(sNow) || !Double.isFinite(route.stationS)) {
         this.probeOk = false;
         // 规划成功却映射不上位置 = 不变量被破坏。抱闸停住，不许像以前那样松闸每拍重规划
         if (this.driveTicks % 20 == 0) {
            HexieTrain.LOGGER.warn("[drive] 车位不在缓存路线里 sNow={} stationS={} → 抱闸重规划",
               sNow, route.stationS);
         }
         this.holdOutputs();
         this.driveResult = null;
         return;
      }
      double d = route.stationS - sNow;
      // 过站：已经越过目标站弧长 ⇒ 直接抱闸报错，不再让速度环空转
      if (d < -ARRIVE_TOL) {   // 只有超出站台容差才算真过站；容差内交给上面的到站判断
         this.holdOutputs();
         if (this.driveTicks % 20 == 0) {
            HexieTrain.LOGGER.warn("[drive] 已越过目标站 {} 米，抱闸停住 target={} entry={}", new Object[]{
               String.format("%.1f", -d), target, this.myEntry});
         }
         this.driveTicks++;
         return;
      }
      if (!Double.isFinite(d)) {
         this.phaseIdle();
         this.driveResult = null;
         return;
      }

      double v = Math.abs(this.measuredSpeedMps());
      // UI 距站由这里喂（railProbe 已删，距站只有这一条来源）
      this.cacheDistShown = d;
      if (this.driveTicks % 10 == 0) this.sendData();
      this.driveTicks++;
      if (this.driveTicks > DRIVE_TICK_CAP) {
         // 看门狗：一段路跑这么久还没到站，说明有东西不对（方向/到站判据/轨道）。
         // 按注释里的承诺强制抱闸并停手，不自动重来 —— 自动重来等于无限开下去。
         HexieTrain.LOGGER.warn("[drive] 超每段 {} 次决策上限，强制抱闸停手 target={} entry={} d={}",
            DRIVE_TICK_CAP, target, this.myEntry, String.format("%.1f", d));
         this.driveHalted = true;
         this.haltEntry = this.myEntry;
         this.phaseIdle();
         this.holdOutputs();
         return;
      }

      // ===== 信号：只能靠边点（SignalBoundary 是唯一可知渠道）=====
      // 到站不再看边点：解耦/虚拟车进站那套已删，没有任何消费者需要 Create 的边点回调，
      // 到站改成我们自己判断 —— 弧长距站 d + 物理速度 + 自己的制动代码。
      double probeWindow = Math.max(0.6, v * 0.25 + 0.5);
      EdgePointWatch.Hit atPoint = EdgePointWatch.probe(train.graph, route, pt, probeWindow);
      boolean isRedSignal = atPoint != null && !atPoint.station && this.signalClosed(atPoint, train);
      if (isRedSignal) {
         this.drivePhase = PH_BRAKE;
         this.throttleCmd = 0;
         this.brakeCmd = 15;
         this.applyPhase(d, v);
         if (this.driveTicks % 20 == 0) {
            HexieTrain.LOGGER.info("[sig] 红灯停车，等待放行 d={} v={}", new Object[]{
               String.format("%.2f", d), String.format("%.2f", v)});
         }
         return;
      }

      // ===== 到站：自己的判断（|d| ≤ ARRIVE_TOL 且 v < V_ARRIVE）=====
      if (Math.abs(d) <= ARRIVE_TOL && v < V_ARRIVE) {
         this.driveStopped = true;
         this.drivePhase = PH_BRAKE;
         this.throttleCmd = 0;
         this.brakeCmd = 15;
         this.applyPhase(d, v);
         if (!this.arrivedFlag) {
            HexieTrain.LOGGER.info("[drive] 到站 target={} d={} v={}", new Object[]{
               this.driveTarget, String.format("%.2f", d), String.format("%.2f", v)});
            HexieTrain.LOGGER.info("[mark] 首次登记到站 target={} entry={} 车d={} arrivedFlag={} → 打点", this.driveTarget, this.myEntry, String.format("%.2f", d), this.arrivedFlag);
            this.arrivedFlag = true;
            String nmA = this.mirror.targetName(this.myEntry);
            this.mirror.markArrived(nmA == null ? "-" : nmA, this.myEntry, this.level.getGameTime());
            HexieTrain.LOGGER.info("[mark] 已登记 markArrived({}, entry={}) gameTime={}", nmA, this.myEntry, this.level.getGameTime());
         }
         this.onArrived(train);
         return;
      }
      // 进了容差但速度还不够低 ⇒ 只重刹，不 latch
      if (Math.abs(d) <= ARRIVE_TOL) {
         this.drivePhase = PH_BRAKE;
         this.throttleCmd = 0;
         this.brakeCmd = 15;
         this.applyPhase(d, v);
         if (this.driveTicks % 10 == 0) {
            HexieTrain.LOGGER.info("[drive] 已进站台容差但 v={} ≥ {}，只重刹 d={}", new Object[]{
               String.format("%.2f", v), String.format("%.1f", V_ARRIVE), String.format("%.2f", d)});
         }
         return;
      }

      // 到站锁 / 停车阶段必须能退出来：被拖走、冲过头都会让 d 重新变大
      if (d > STOP_TRIGGER && (this.driveStopped || this.drivePhase == PH_COAST || this.drivePhase == PH_BRAKE)) {
         if (this.driveStopped) {
            HexieTrain.LOGGER.info("[drive] 距站 {} 且已不在站上 → 解除到站锁，重新起步", String.format("%.1f", d));
         }
         this.driveStopped = false;
         this.drivePhase = PH_RUN;
         this.brakeCmd = 0;
         this.throttleCmd = 0;
         this.coastTicks = 0;
      }

      // 方向不在行驶中翻面：换面=换物理方向，只能在标定阶段做（见 ensureDirection）

      // 允许速度：vAllow = sqrt(2*a*(d-0.5))，a=0.6（沿用老代码）
      double vAllow = Math.sqrt(2.0 * 0.6 * Math.max(0.0, d - 0.5));
      boolean mustStop = d <= STOP_TRIGGER || v > vAllow;
      int before = this.drivePhase;

      // ===== 曲线速度：vT 是唯一公式，dv 决定 加/滑/刹 =====
      // 停车距离曲线用的是【制动减速度】，不是 Create 的加速能力——这是两回事。
      // 之前误用 train.acceleration()（还被 max(0.05,…) 兜底）⇒ vT 全段只有 2~4 m/s ⇒ 永远滑车。
      double vT = Math.max(0.4, Math.min(V_CRUISE_MPS, Math.sqrt(2.0 * A_BRAKE * Math.max(0.0, d - 0.5))));
      double dv = vT - v;

      // 制动底线：老代码的距离路，防速度环跟不上
      double stopZone = Math.max(3.0, v * 1.2);
      int brakeFloor = d >= stopZone ? 0 : (int) Math.round(15.0 * (1.0 - Math.max(0.0, d) / stopZone));
      if (d < 1.0) brakeFloor = 15;

      int wantThrottle;
      if (dv > 0.0) {
         // 低于目标速度就推，**绝不滑车**：滑车只属于"已经到达或超过目标"的区间
         this.drivePhase = PH_RUN;
         this.brakeCmd = 0;
         wantThrottle = (int) Math.round(15.0 * Math.min(1.0, dv / ACC_BAND));
      } else if (dv > -BRK_BAND) {
         this.drivePhase = PH_COAST;
         wantThrottle = 0;
         this.brakeCmd = 0;
      } else {
         this.drivePhase = PH_BRAKE;
         wantThrottle = 0;
         this.brakeCmd = Math.max((int) Math.round(Math.min(15.0, (-dv) / DV_FULL * 15.0)), brakeFloor);
      }

      // 油门按固定速率逼近（执行器惯性）；刹车直接给，安全侧不延迟
      if (wantThrottle > this.throttleCmd) {
         this.throttleCmd = Math.min(wantThrottle, this.throttleCmd + THROTTLE_RATE);
      } else {
         this.throttleCmd = Math.max(wantThrottle, this.throttleCmd - 3);
      }


      if (this.drivePhase != before) {
         HexieTrain.LOGGER.info("[drive] 阶段 {} → {} d={} v={} throttle={}", new Object[]{
            phaseName(before), phaseName(this.drivePhase),
            String.format("%.1f", d), String.format("%.2f", v), this.throttleCmd});
      }

      this.applyPhase(d, v);

      if (this.driveTicks % 2 == 0) {
         HexieTrain.LOGGER.info("[drive] t={} {} face={} d={} v={} throttle={} brake={}", new Object[]{
            this.driveTicks, phaseName(this.drivePhase), this.activeFace, String.format("%.1f", d),
            String.format("%.2f", v), this.output(SignalChannel.THROTTLE), this.output(SignalChannel.BRAKE)});
      }
   }

   /** 把当前阶段翻译成四模态的具体输出。 */


   private void applyPhase(double d, double v) {
      ServerLevel sl = (this.level instanceof ServerLevel x) ? x : null;
      switch (this.drivePhase) {
         case PH_RUN:
         case PH_CUT:
            if (sl != null) {
               DispatcherGearbox.engage(sl, this.worldPosition,
                  this.activeFace == 1 ? DispatcherGearbox.Face.COUNTERCLOCKWISE : DispatcherGearbox.Face.CLOCKWISE);
            }
            this.SetOutputBrake(0);
            this.setOutput(SignalChannel.THROTTLE, this.throttleCmd);
            this.setDispatcherState(DispatcherState.RUNNING);
            break;
         case PH_COAST:
            if (sl != null) DispatcherGearbox.engage(sl, this.worldPosition, DispatcherGearbox.Face.NONE);
            this.SetOutputBrake(0);
            this.setOutput(SignalChannel.THROTTLE, 0);
            this.setDispatcherState(DispatcherState.COASTING);
            break;
         case PH_BRAKE:
            if (sl != null) DispatcherGearbox.engage(sl, this.worldPosition, DispatcherGearbox.Face.NONE);
            this.SetOutputBrake(this.brakeCmd);
            this.setOutput(SignalChannel.THROTTLE, 0);
            this.setDispatcherState(DispatcherState.BRAKING);
            break;
         default:
            this.SetOutputBrake(0);
            this.setOutput(SignalChannel.THROTTLE, 0);
            this.setDispatcherState(DispatcherState.IDLE);
            break;
      }
   }

   /**
    * 到站处理：条件与调度延迟一律问镜像，不自己计时。
    *
    *   Stop.satisfied(...)   —— 停站条件（等 N 秒、货物、红石…）满了没有
    *   Stop.waitingText(...) —— 还在等什么，直接进日志
    *
    * 没满足就保持刹车 15、原地不动；满足了才 markArrived + 推进 entry。
    */
   private void onArrived(Train train) {
      try {
         int size = Math.max(1, this.mirror.size());
         ScheduleMirror.Stop stop = this.mirror.at(this.myEntry);

         boolean ready;
         try {
            ready = stop == null || stop.satisfied(this.level, train, this.mirror.lastArrival() == null ? 0L : this.mirror.lastArrival().tick(), this.conditionCtx);
         } catch (Throwable t) {
            ready = true;
         }

         if (!ready) {
            this.brakeCmd = 15;
            this.throttleCmd = 0;
            if (this.probeTicksArrive++ % 20 == 0) {
               String txt = "";
               try {
                  txt = stop.waitingText(this.level, train, this.mirror.lastArrival() == null ? 0L : this.mirror.lastArrival().tick(), this.conditionCtx);
               } catch (Throwable t) {
               }
               HexieTrain.LOGGER.info("[stop] 停站等待：{} | 站={} entry={}", new Object[]{
                  txt, stop.stationName(), this.myEntry});
            }
            return;
         }

         String name = stop == null ? "-" : stop.stationName();
         // markArrived 已在到站瞬间登记过，这里不能再覆盖，否则"等 5 秒"永远重新计时
         if (this.myEntry + 1 >= size) {
            if (this.mirror.cyclic()) {
               this.setEntry(0);                       // 循环表：回绕到第一站（唯一写入口，会同步盖回物品）
            } else {
               this.scheduleDone = true;               // 非循环表：到此结束，不再推进
               HexieTrain.LOGGER.info("[sched] 时刻表已走完（非循环表），停在 {}", name);
            }
         } else {
            this.setEntry(this.myEntry + 1);
         }
         HexieTrain.LOGGER.info("[sched] 到站推进 -> entry={}/{} stop={}", new Object[]{this.myEntry, size, name});

          // 游标已在 setEntry 里盖回时刻表物品（只改 savedProgress 一个 int）
         this.driveHalted = false;   // 到站推进 = 新的一段，解除看门狗

         // 目标已转换：清空本段一切状态，下一 tick 自然重搜
         this.driveResult = null;
         this.driveTarget = null;
         this.driveStopped = false;
         this.drivePhase = PH_IDLE;
         this.throttleCmd = 0;
         this.brakeCmd = 0;
         this.coastTicks = 0;
         this.conditionCtx = new CompoundTag();
         this.probeTicksArrive = 0;
      } catch (Throwable t) {
         HexieTrain.LOGGER.warn("[sched] 到站处理异常 {}", t.toString());
      }
   }

   private void phaseIdle() {
      // 道岔不再在这里复位：转向由 BogeySteering 负责，'不在导航' 时它会自己 releaseAll。
      this.drivePhase = PH_IDLE;
      this.throttleCmd = 0;
      this.coastTicks = 0;
      this.applyPhase(Double.NaN, 0.0);
   }

   private static String phaseName(int ph) {
      switch (ph) {
         case PH_RUN:   return "行驶中";
         case PH_CUT:   return "收油门";
         case PH_COAST: return "滑车";
         case PH_BRAKE: return "急刹";
         default:       return "待命";
      }
   }

   /** 信号机红绿：走 SignalProbe，和站点用同一套边点触发通道，只是判定不同。 */
   private boolean signalClosed(EdgePointWatch.Hit h, Train train) {
      try {
         if (!(h.point instanceof SignalBoundary sb)) return false;
         TrackNode enter = h.nodes == null ? null : h.nodes.getSecond();
         SignalProbe.Obs obs = SignalProbe.observe(sb, enter, train);
         if (obs == null) return false;
         // SignalProbe.State = CLEAR / BLOCKED / UNKNOWN，只有 BLOCKED 才算红灯
         return obs.state() == SignalProbe.State.BLOCKED;
      } catch (Throwable t) {
         return false;
      }
   }

   /** 另一个驱动面。只有两个面，翻面就是这么个意思。 */
   private static int flipFace(int face) {
      return face == 1 ? 2 : 1;
   }

   /**
    * 面标定作废：把方向事实整个清掉，下一拍从取样重新开始。
    *
    * <p>什么时候必须作废：换车（装配变了）、标定端被解绑、实测发现给着油门 s 在倒退。
    * <b>换端不作废</b> —— 标定存的是物理端，换端只换 {@link #activeFace} 的算法结果。
    */
   private void invalidateDirection() {
      this.dirSign = 0;
      this.dirBogey = null;
      this.calPhase = 0;
      this.calTick = 0;
      this.calFace = 1;
      this.calS0 = Double.NaN;
      this.calPos0 = Double.NaN;
      this.calEdge = null;
      this.lastRouteS = Double.NaN;
   }

   /**
    * 方向标定。产出两件东西：
    * <pre>
    *   dirFace  = 能把车推动的那个驱动面（1=逆时针 2=顺时针）
    *   dirBogey = 那个面把车推向哪个<b>物理端</b>（存那一端的转向架坐标）
    * </pre>
    *
    * <h2>量的是路线弧长，不是边上的位置</h2>
    * 以前量 {@code edge.position}（从 node1 量起）。可 node1/node2 是<b>每条边各自的任意编号</b>，
    * 跟"车往站开"没有任何关系；换一条边，正方向就翻了。所以标出来的东西只能在
    * 标定时那条边上用，一换端就得重标，重标期间车不动 —— 现象就是"犹豫前后走"。
    *
    * <p>现在量 {@code route.sOf(领先端)}：s 的正方向<b>就是</b>行进方向，永远。
    * s 变大 ⇒ 这个面把车推向领先端（{@code dirBogey = navHeadPos}）；
    * s 变小 ⇒ 推向另一端（{@code dirBogey = navTailPos}）。
    * 结果是一个<b>物理端</b>，与路线怎么摆无关，所以换向不必重标。
    *
    * <p>必须排在规划之后调用：没有 route 就没有 s，量不出来。
    *
    * <p>试法：先逆时针面；推不动就试顺时针面；两个都不动 ⇒ 报错、不驱动（不兜底）。
    *
    * @return true 表示已标定、可以行驶
    */
   private boolean ensureDirection(Train train) {
      if (this.driveResult == null || !this.driveResult.ok || this.driveResult.route == null) return false;
      if (this.navHeadPos == null || this.navTailPos == null) return false;

      // 换车必须重标：装配换了，同一个面的物理含义就变了
      if (this.calTrain != null && !this.calTrain.equals(train.id)) {
         this.calTrain = train.id;
         invalidateDirection();
         this.calCooldown = 0;
      }
      if (this.calTrain == null) this.calTrain = train.id;
      if (this.dirSign != 0 && this.calPhase >= 3 && this.dirBogey != null) return true;
      if (!(this.level instanceof ServerLevel sl)) return false;
      if (this.calCooldown > 0) {
         this.calCooldown--;
         this.holdOutputs();
         return false;
      }

      // 量领先端：距站认它，方向也拿它当尺子
      TravellingPoint h = this.pointOf(this.navHeadPos);
      if (h == null || h.edge == null) return false;

      // --- 相位 0：取样。基线同时取"边上位置"（判静止用）和"路线弧长"（量位移用）
      if (this.calPhase == 0) {
         this.holdOutputs();
         this.calEdge = h.edge;
         double len0 = CreateApi.length(this.calEdge);
         this.calPos0 = (h.edge.node1 == this.calEdge.node1) ? h.position : (len0 - h.position);
         this.calS0 = this.driveResult.route.sOf(h.edge, h.position);
         if (!Double.isFinite(this.calS0)) {
            HexieTrain.LOGGER.warn("[cal] 领先端不在本段路线上，取不到基线 ⇒ 本拍不标定");
            this.calEdge = null;
            return false;
         }
         this.calPhase = 4;
         return false;
      }

      // --- 相位 4：确认静止（用边上位置，跨边就重取）
      if (this.calPhase == 4) {
         this.holdOutputs();
         boolean still = RailCache.sameLink(h.edge, this.calEdge);
         if (still) {
            double lenS = CreateApi.length(this.calEdge);
            double pS = (h.edge.node1 == this.calEdge.node1) ? h.position : (lenS - h.position);
            still = Math.abs(pS - this.calPos0) < 0.02;
         }
         if (!still) {
            this.calPhase = 0;                        // 车还在动，重新取样
            return false;
         }
         this.calTick = 0;
         this.calPhase = 1;
         HexieTrain.LOGGER.info("[cal] 取基线 face={} 边长={} s0={}", new Object[]{
            this.calFace, String.format("%.1f", CreateApi.length(this.calEdge)),
            String.format("%.2f", this.calS0)});
         return false;
      }

      // --- 相位 1：给这个面一点油门，量领先端的 s 变了多少
      double sNow = this.driveResult.route.sOf(h.edge, h.position);
      if (!Double.isFinite(sNow)) {
         // 领先端走出路线了：这条路线已经不能描述车，别硬量
         HexieTrain.LOGGER.info("[cal] 领先端已不在本段路线上 ⇒ 放弃本次标定，等重规划");
         this.calPhase = 0;
         this.calEdge = null;
         return false;
      }
      double moved = sNow - this.calS0;

      if (Math.abs(moved) >= CAL_MIN_TRAVEL) {
         this.dirSign = moved > 0.0 ? 1 : -1;
         this.dirFace = this.calFace;
         // 位移为正 = 朝领先端推进 ⇒ 这个面推向领先端
         this.dirBogey = moved > 0.0 ? this.navHeadPos : this.navTailPos;
         this.calPhase = 3;
         this.calTick = 0;
         this.holdOutputs();
         this.setDispatcherState(DispatcherState.IDLE);
         HexieTrain.LOGGER.info("[cal] 标定完成 face={} 领先端s位移={} ⇒ 这个面把车推向 {} 那个物理端",
            new Object[]{this.dirFace, String.format("%.3f", moved), this.dirBogey.toShortString()});
         this.sendData();
         // 这一拍只收尾（已抱闸），下一拍再由 driveTick 规划行驶：
         // 否则同一拍里 holdOutputs() 的刹车与 applyPhase() 的油门互相覆盖，车会抖一下
         return false;
      }

      // 还没动够：继续用这一点油门量推
      this.calTick++;
      DispatcherGearbox.engage(sl, this.worldPosition,
         this.calFace == 1 ? DispatcherGearbox.Face.COUNTERCLOCKWISE : DispatcherGearbox.Face.CLOCKWISE);
      this.SetOutputBrake(0);
      this.setOutput(SignalChannel.THROTTLE, CAL_THROTTLE);
      this.setDispatcherState(DispatcherState.RUNNING);
      if (this.calTick < CAL_TICK_LIMIT) return false;

      // 这个面推不动 → 换另一个；都不动就说清楚，不猜方向
      this.holdOutputs();
      if (this.calFace == 1) {
         this.calFace = 2;
         this.calPhase = 0;
         this.calTick = 0;
         HexieTrain.LOGGER.info("[cal] 逆时针面推不动 → 改试顺时针面");
      } else {
         invalidateDirection();
         // 40 次决策 = 200 tick = 10 秒；原来写 200 = 50 秒，太长
         this.calCooldown = 40;
         this.setDispatcherState(DispatcherState.IDLE);
         HexieTrain.LOGGER.warn("[cal] 两个面都推不动 ⇒ 不驱动（不猜方向）");
      }
      return false;
   }

   private double routeS(TravellingPoint pt) {
      if (this.driveResult == null || !this.driveResult.ok || pt == null) return Double.NaN;
      return this.driveResult.route.sOf(pt.edge, pt.position);
   }

   // ================= 位置失效后的恢复（单向：只按标定方向，不试反方向）=================

   /** 车头点是否已失效：点没有边，或该边的节点在轨道图里定位不到 */
   private boolean headPointBad(Train train) {
      TravellingPoint h = CreateApi.leadPoint(train);
      if (h == null || h.edge == null) return true;
      if (train.graph == null) return true;
      return CreateApi.node(train.graph, h.edge.node1.getLocation()) == null
          || CreateApi.node(train.graph, h.edge.node2.getLocation()) == null;
   }

   /**
    * 把车重新挂回它**物理上真正所在**的那段轨道，并且必须能沿**当前（标定出的）方向**到达
    * 时刻表当前 entry 那个**名字**的站（含准入端校验）。
    * 做不到就报错并保持制动：不换边、不试反方向、不猜位置。
    */
   private void tryRecover(Train train) {
      if (!(this.level instanceof ServerLevel sl)) return;
      TrackGraph g = train.graph;
      if (g == null) return;
      net.minecraft.world.phys.Vec3 center = SubLevelSuspend.worldCenter(sl, this.worldPosition);

      // 目标必须来自时刻表：站名 + 站对象，缺一不恢复
      String target = this.mirror.targetName(this.myEntry);
      GlobalStation st = target == null ? null : CreateApi.stationNamed(g, target);
      if (st == null) {
         if (this.recoverTicks % 20 == 0) {
            HexieTrain.LOGGER.warn("[recover] entry={} 没有可用目标站（target={}）⇒ 只制动不恢复", this.myEntry, target);
         }
         return;
      }

      TrackNode n = g.locateNode(sl, center);
      if (n == null) {
         if (this.recoverTicks % 20 == 0) {
            HexieTrain.LOGGER.warn("[recover] 目标={} 车附近找不到任何轨道节点", target);
         }
         return;
      }

      // 车到底压在哪条轨上：最近节点的相邻边里，取"车心到线段"距离最小的那条（纯几何，我们自己算）
      Map<TrackNode, TrackEdge> conns = g.getConnectionsFrom(n);
      TrackEdge bestEdge = null;
      double bestDist = Double.MAX_VALUE;
      double bestPos = 0.0;
      if (conns != null) {
         for (TrackEdge e : conns.values()) {
            if (e == null) continue;
            net.minecraft.world.phys.Vec3 a = e.node1.getLocation().getLocation();
            net.minecraft.world.phys.Vec3 b = e.node2.getLocation().getLocation();
            net.minecraft.world.phys.Vec3 ab = b.subtract(a);
            double lenSqr = ab.lengthSqr();
            double t = lenSqr < 1.0E-9 ? 0.0 : Math.max(0.0, Math.min(1.0, center.subtract(a).dot(ab) / lenSqr));
            double dist = center.distanceTo(a.add(ab.scale(t)));
            if (dist < bestDist) {
               bestDist = dist;
               bestEdge = e;
               bestPos = t * e.getLength();
            }
         }
      }
      if (bestEdge == null) {
         if (this.recoverTicks % 20 == 0) {
            HexieTrain.LOGGER.warn("[recover] 目标={} 节点 {} 没有可用出边", target, n.getLocation());
         }
         return;
      }

      // 验证：沿当前方向能不能到那个名字的站（站名 + 准入端两道校验都在 searchFrom 里）

      double len = bestEdge.getLength();
      double pos = Math.max(0.0, Math.min(len, bestPos));
      TravellingPoint probe = new TravellingPoint(bestEdge.node1, bestEdge.node2, bestEdge, pos, false);
      RailSearch.Result r = RailSearch.searchFrom(g, probe, st, target, 4096.0);
      if (!r.ok) {
         if (this.recoverTicks % 20 == 0) {
            HexieTrain.LOGGER.warn("[recover] 目标={} entry={} 车在边长={} pos={} 方向={} ⇒ 不可达：{}",
               target, this.myEntry, String.format("%.1f", len), String.format("%.1f", pos),
               "未定", r.note);
         }
         return;
      }

      // 验证通过 ⇒ 写回活对象。注意：CarriageBogey.leading() 返回的是活点，
      // 而 CreateApi.leadPoint() 返回的是拷贝，所以恢复必须走前者。
      int carriageCount = 0;
      for (Carriage c : train.carriages) {
         if (c == null || c.bogeys == null) continue;
         com.simibubi.create.content.trains.entity.CarriageBogey bg = c.bogeys.getFirst();
         if (bg == null) continue;
         TravellingPoint lp = bg.leading();
         if (lp != null) {
            lp.node1 = bestEdge.node1;
            lp.node2 = bestEdge.node2;
            lp.edge = bestEdge;
            lp.position = pos;
         }
         TravellingPoint tp = bg.trailing();
         if (tp != null) {
            tp.node1 = bestEdge.node1;
            tp.node2 = bestEdge.node2;
            tp.edge = bestEdge;
            tp.position = Math.max(0.0, Math.min(len, pos - c.bogeySpacing));
         }
         carriageCount++;
      }
      train.derailed = false;
      train.invalid = false;
      TrainBinder.reanchor(train, sl, this.worldPosition);
      HexieTrain.LOGGER.info("[recover] 目标={} entry={} 已重挂 车厢={} 边长={} pos={} 方向={} 距站={}",
         target, this.myEntry, carriageCount, String.format("%.1f", len), String.format("%.1f", pos),
         r.towardNode2 ? "朝node2" : "朝node1", String.format("%.1f", r.distance));
   }
   /** 不驱动时：松闸、无牵引。 */
   private void neutralOutputs() {
      this.SetOutputBrake(0);
      this.setOutput(SignalChannel.THROTTLE, 0);
   }

   /** 停住时：抱闸、无牵引。 */
   private void holdOutputs() {
      this.SetOutputBrake(15);
      this.setOutput(SignalChannel.THROTTLE, 0);
      // 抱闸的同时必须把换向器的转速输出切掉，否则车被旋转推着走、抱闸拉不住
      try {
         if (this.level instanceof ServerLevel sl) {
            DispatcherGearbox.engage(sl, this.worldPosition, DispatcherGearbox.Face.NONE);
         }
      } catch (Throwable t) {
      }
   }


   /**
    * 手操：左右红石输入 → 两个**互相独立**的转向输出。
    *
    * <p>LEFT / RIGHT 是 Create Redstone Link 的两个无线通道（setOutput → pushWireless），
    * 玩家把它们分别接到转向架的左、右输入上；通电就往那一侧的道岔开，都不通电默认直行。
    *
    * <p>以前这段有两个硬伤：
    * <ul>
    *   <li>ccw/cw 来自同一个六向最大值 ⇒ 永远相等 ⇒ applyDriveSide 永远给"左"，只会往一边走。</li>
    *   <li>末尾无条件 setOutput(LEFT,0) + setOutput(RIGHT,0)，把上面刚写的值又抹掉 ⇒ 输出从来没通过电。</li>
    * </ul>
    * 现在：左输入只驱动 LEFT 通道，右输入只驱动 RIGHT 通道，各管各的，谁也不清零谁。
    */
   private void tickManualGearbox() {
      try {
         int ccw = this.counterClockwiseSignal();
         int cw = this.clockwiseSignal();
         this.updateExternalInput(ccw, cw);
         int mag = Math.max(ccw, cw);
         if (mag > 0) {
            this.SetOutputBrake(0);
            this.setOutput(SignalChannel.THROTTLE, mag);
            this.setDispatcherState(DispatcherState.RUNNING);
         } else {
            // 手动模式 + 没有任何牵引输入 = **抱闸**。原来这里写 0（松闸）= 撒手不管，
            // 车有惯性就继续跑，玩家切到手动也停不下来（2026-11-18 那次失控）。
            // 无输入 = 完全松手：油门 0、制动 0，并且把换向器转速切掉。
            try {
               if (this.level instanceof ServerLevel slm) DispatcherGearbox.engage(slm, this.worldPosition, DispatcherGearbox.Face.NONE);
            } catch (Throwable tm) {
            }
            this.SetOutputBrake(0);
            this.setOutput(SignalChannel.THROTTLE, 0);
            this.setDispatcherState(DispatcherState.IDLE);
         }

      } catch (Throwable var4) {
      }
   }




   private void probe(Train train) {
      if (this.probeTicks++ % 20 == 0) {
         StringBuilder b = new StringBuilder(520);
         b.append("[probe] pos=").append(this.worldPosition.toShortString());
         add(b, "primary", () -> String.valueOf(this.isPrimaryDispatcher()));
         add(b, "manual", () -> String.valueOf(this.manualMode));
         add(b, "slotEmpty", () -> String.valueOf(this.scheduleSlot.isEmpty()));
         add(b, "bound", () -> this.boundTrain == null ? "-" : this.boundTrain.toString().substring(0, 8));
         add(b, "tid", () -> train == null ? "-" : train.id.toString().substring(0, 8));
         if (train != null) {
            add(b, "speed", () -> ff(train.speed));
            add(b, "tgtSpeed", () -> ff(train.targetSpeed));
            add(b, "derail", () -> String.valueOf(train.derailed));
            add(b, "curSt", () -> {
               GlobalStation c = train.getCurrentStation();
               return c == null ? "-" : String.valueOf(c.name);
            });
            add(b, "state", () -> String.valueOf(train.runtime.state));
            add(b, "entry", () -> train.runtime.currentEntry + "/" + (train.runtime.schedule == null ? -1 : train.runtime.schedule.entries.size()));
            add(b, "paused", () -> String.valueOf(train.runtime.paused));
            add(
               b,
               "conds",
               () -> {
                  ScheduleRuntime rt = train.runtime;
                  return rt.schedule != null && rt.currentEntry >= 0 && rt.currentEntry < rt.schedule.entries.size()
                     ? String.valueOf(((ScheduleEntry)rt.schedule.entries.get(rt.currentEntry)).conditions.size())
                     : "-";
               }
            );
            add(b, "prog", () -> String.valueOf(train.runtime.conditionProgress));
            Navigation nv = train.navigation;
            add(b, "dest", () -> nv.destination == null ? "-" : String.valueOf(nv.destination.name));
            add(b, "dDest", () -> ff(nv.distanceToDestination));
            add(b, "dSig", () -> ff(nv.distanceToSignal));
            add(b, "behind", () -> String.valueOf(nv.destinationBehindTrain));
            add(b, "waitSig", () -> nv.waitingForSignal == null ? "-" : "Y");
            add(b, "pathN", () -> {
               try {
                  Field fl = nv.getClass().getDeclaredField("currentPath");
                  fl.setAccessible(true);
                  return String.valueOf(((List)fl.get(nv)).size());
               } catch (Throwable var2x) {
                  return "ERR";
               }
            });
         }

         add(b, "vphys", () -> ff(this.measuredSpeedMps()));
         add(b, "vtrack", () -> ff(this.bodySpeedAlongTrackBps()));
         add(b, "leg", () -> ff(this.legRemaining));
         add(b, "pendingLeg", () -> ff(this.pendingLeg));
         add(b, "lastEntry", () -> String.valueOf(this.lastLegEntry));
         add(b, "dwell", () -> this.dwellingStation == null ? "-" : String.valueOf(this.dwellingStation.name));
         add(b, "dPhys", () -> {
            try {
               GlobalStation d = train == null ? null : train.navigation.destination;
               return d == null ? "-" : ff(this.physicalDistanceTo(d));
            } catch (Throwable var3x) {
               return "ERR";
            }
         });
         add(b, "brakeCh", () -> String.valueOf(this.output(SignalChannel.BRAKE)));
         add(b, "bsCCW", () -> String.valueOf(this.getBlockState().getValue(TrainDispatcherBlock.COUNTERCLOCKWISE_POWERED)));
         add(b, "bsCW", () -> String.valueOf(this.getBlockState().getValue(TrainDispatcherBlock.CLOCKWISE_POWERED)));
         add(b, "rsCCW", () -> String.valueOf(this.counterClockwiseSignal()));
         add(b, "rsCW", () -> String.valueOf(this.clockwiseSignal()));
         add(b, "hasSrc", () -> String.valueOf(this.hasSource()));
         add(b, "srcFace", () -> {
            try {
               return String.valueOf(this.getSourceFacing());
            } catch (Throwable var2x) {
               return "-";
            }
         });
         HexieTrain.LOGGER.info(b.toString());
      }
   }

   private static String ff(double d) {
      return String.format("%.2f", d);
   }

   private static void add(StringBuilder b, String key, Supplier<String> s) {
      b.append(" ").append(key).append("=");

      try {
         b.append(s.get());
      } catch (Throwable var4) {
         b.append("ERR:").append(var4.getClass().getSimpleName());
      }
   }


   // ===== 导航层已整体移除（Nav.java / RoutePreview.java 已删）=====
   // 重建顺序见 docs\进度与断点.md：①缓存 → ②双向搜索 → ③方向+距离 → ④驱动 → ⑤边点停车

   public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
      return new TrainDispatcherMenu(containerId, playerInventory, this);
   }

   private boolean signalClosed(Level lv, BlockPos pos, double sSig) {
      try {
         // 绝不让 level 在 tick 里加载区块：getBlockEntity 会强制加载，C2ME 下
         // ServerChunkCache.getChunk 的 CompletableFuture.join() 会把服务器线程等死
         // （2026-11-17 01:14 进不去世界就是这一类）。判不了就按红灯处理，不猜绿灯。
         if (!lv.hasChunkAt(pos)) {
            HexieTrain.LOGGER.warn("[sig] 信号方块所在区块未加载，无法判定 → 按红灯处理 pos={}", pos);
            return true;
         }
         if (lv.getBlockEntity(pos) instanceof SignalBlockEntity sbe) {
            SignalState nativeState = sbe.getState();
            SignalState shown = CreateSignalAspects.visibleState(sbe, nativeState);
            return shown != SignalState.GREEN;
         }
      } catch (Throwable var8) {
      }

      return false;
   }

   private double nearestPlayerDistance() {
      if (this.level instanceof ServerLevel serverLevel) {
         Vec3 var9 = SubLevelSuspend.worldCenter(serverLevel, this.worldPosition);
         double best = Double.MAX_VALUE;

         for (ServerPlayer player : serverLevel.players()) {
            double d = player.position().distanceTo(var9);
            if (d < best) {
               best = d;
            }
         }

         return best;
      } else {
         return 0.0;
      }
   }

   private void sendDataThrottled() {
      long t = this.level == null ? 0L : this.level.getGameTime();
      if (t != this.lastSendTick) {
         this.lastSendTick = t;
         this.sendData();
      }
   }

   private void setOutput(SignalChannel channel, int value) {
      int v = Math.max(0, Math.min(15, value));
      if (this.outputs[channel.index()] != v) {
         this.outputs[channel.index()] = v;
         this.pushWireless(channel);   // 光改数组网络不知道，必须主动触发重算
         this.sendDataThrottled();
         this.sendData();
      }
   }


   /**
    * 把强度变化推给红石网络。
    * Create 的 RedstoneLinkNetworkHandler 没有 tick()，
    * 只有 updateNetworkOf 被调时才会重读 getTransmittedStrength 并广播。
    * 不推这一下，外部永远读到注册那一刻的旧值。
    */
   private void pushWireless(SignalChannel channel) {
      try {
         if (this.level == null || this.level.isClientSide) return;
         WirelessChannel ch = this.wireless[channel.index()];
         if (ch == null) return;
         Create.REDSTONE_LINK_NETWORK_HANDLER.updateNetworkOf(this.level, ch);
      } catch (Throwable t) {
      }
   }
   public Couple<Frequency> networkKey(SignalChannel channel) {
      int base = channel.index() * 2;
      return Couple.create(frequencyOf(this.frequencies[base]), frequencyOf(this.frequencies[base + 1]));
   }

   private static Frequency frequencyOf(ItemStack stack) {
      return stack != null && !stack.isEmpty() ? Frequency.of(stack) : Frequency.EMPTY;
   }

   public void initialize() {
      super.initialize();
      ACTIVE.add(this);
      if (this.level != null && !this.level.isClientSide) {
         for (WirelessChannel var4 : this.wireless) {
            ;
         }
      }
   }


   /**
    * 区块卸载也必须从 ACTIVE 注销。
    *
    * <p>Create 的 SmartBlockEntity.setRemoved() 是 final，而且它只在**非区块卸载**时
    * 调 remove()（if (!chunkUnloaded) remove();），区块卸载走 onChunkUnloaded()。
    * 这个方块在 Sable 子世界里会被反复装卸，卸载时不注销 ⇒ ACTIVE 只增不减 ⇒
    * DispatcherTicker（全局 ServerTickEvent）会把所有陈旧实例一起 tick。
    * 实测一次会话里重建 296 次 ⇒ 296 个实例同时驱动同一列车。
    */
   @Override
   public void onChunkUnloaded() {
      super.onChunkUnloaded();
      hexie$unregister();
   }

   /** Create 的 remove() 钩子：方块被破坏/搬走（非区块卸载）时调用。 */
   @Override
   public void remove() {
      hexie$unregister();
   }

   private void hexie$unregister() {
      try {
         if (this.boundTrain != null && this.worldPosition.equals(TrainBinder.TRAIN_DISPATCHERS.get(this.boundTrain))) {
            TrainBinder.TRAIN_DISPATCHERS.remove(this.boundTrain);
         }
      } catch (Throwable var9) {
      }

      try {
         if (this.boundTrain != null && TRAIN_OWNER.get(this.boundTrain) == this) {
            TRAIN_OWNER.remove(this.boundTrain);
         }
      } catch (Throwable var8) {
      }

      boolean blockGone = this.level != null && !this.level.getBlockState(this.worldPosition).is(this.getBlockState().getBlock());
      boolean unloading = false;

      try {
         unloading = this.isChunkUnloaded();
      } catch (Throwable var7) {
      }

      if (blockGone && !unloading) {
         if (this.boundTrain != null) {
            HexieTrain.LOGGER.info("[HexieTrain][bind] 调度器被破坏 → 抹除虚拟列车与绑定");
            this.dissolve();
         }

         this.assemblyId = null;
         this.persistentTrainId = null;
      }

      ACTIVE.remove(this);
      if (this.level != null && !this.level.isClientSide) {
         for (WirelessChannel var6 : this.wireless) {
            ;
         }
      }

   }

   /**
    * 这一条频率（= 两个槽组成的一对）是否**被用户配置过**。
    *
    * <p>注意区分两件事：
    * <ul>
    *   <li>"空+空" <b>仍然是一条真实的频率</b> —— Frequency.EMPTY 是个真值，两个空物品照样组成
    *       一个合法的网络键，Create 会拿它当键用。所以它不是"没有频率"。</li>
    *   <li>但它不是**用户配置的那几条**。往一个不属于用户配置集合的频率上发信号，
    *       等于跟世界上所有同样双空的链接串在一起 —— 这才是错的。</li>
    * </ul>
    *
    * <p>所以判据是"用户有没有配这一条"，而不是"这个键存不存在"：
    * 两个槽都空 = 没配（见 refreshWireless：removeFromNetwork 之后不再 addToNetwork，
    * 于是 getTransmittedStrength 根本不会被调用，一个信号都不发）。
    * 只要有一个槽放了物品，就算这一条配过了 —— 第二个槽留空是 Create 允许的正常接法。
    */
   public boolean channelConfigured(SignalChannel channel) {
      int base = channel.index() * 2;
      return !this.frequencies[base].isEmpty() || !this.frequencies[base + 1].isEmpty();
   }

   public ItemStack slot(int index) {
      return index == 0 ? this.scheduleSlot : this.frequency(index - 1);
   }

   public void setSlot(int index, ItemStack stack) {
      if (index == 0) {
         this.refreshWireless();
         this.scheduleSlot = stack == null ? ItemStack.EMPTY : stack;
         this.setChanged();
      } else {
         this.setFrequency(index - 1, stack);
      }
   }

   public ItemStack scheduleSlot() {
      return this.scheduleSlot;
   }

   public String lastBindMessage() {
      return this.lastBindMessage;
   }

   public void setScheduleSlot(ItemStack stack) {
      this.scheduleSlot = stack == null ? ItemStack.EMPTY : stack.copyWithCount(1);
      if (this.level != null && !this.level.isClientSide) {
         ACTIVE.add(this);
         if (!this.scheduleSlot.isEmpty() && !this.manualMode && this.boundTrain == null) {
            this.bindTrainNow();
         }
      }

      this.refreshStopNames();
      this.setChanged();
      this.sendDataThrottled();
   }

   public void dissolve() {
      if (this.boundTrain != null) {
         if (this.boundTrain != null) {
            BY_TRAIN.remove(this.boundTrain);
         }

         if (this.level != null && !this.level.isClientSide) {
            Train train = (Train)Create.RAILWAYS.trains.get(this.boundTrain);
            if (train != null) {
               try {
                  train.navigation.cancelNavigation();
               } catch (Throwable var4) {
               }

               try {
                  train.leaveStation();
               } catch (Throwable var3) {
               }

               Create.RAILWAYS.removeTrain(this.boundTrain);
               TrainBinder.OUR_TRAINS.remove(this.boundTrain);
            }
         }

         if (this.level != null) {
         }

         this.boundTrain = null;
         this.lastDestination = null;
          // 注销虚拟列车时必须把牵引清零，否则上一次的油门会一直挂在无线通道上
          try {
             this.setOutput(SignalChannel.THROTTLE, 0);
             this.SetOutputBrake(0);
          } catch (Throwable var5) {
          }
      }
   }

   private void bindTrainNow() {
      if (this.level instanceof ServerLevel serverLevel) {
         if (this.assemblyId == null) {
            try {
               ServerSubLevel sub = SubLevelSuspend.containing(serverLevel, this.worldPosition);
               if (sub != null) {
                  this.assemblyId = sub.getUniqueId();
               }
            } catch (Throwable var4) {
            }
         }

         if (this.assemblyId != null) {
            TrainBinder.PENDING_TRAIN_ID = TrainBindings.resolve(serverLevel, this.assemblyId);
         }

         try {
            List<PhysicsBogeyBlockEntity> rosterList = this.rosterBogeys();
            TrainBinder.Result result = TrainBinder.bind(serverLevel, this.worldPosition, this.scheduleSlot, this.owner, rosterList);
            this.lastBindMessage = result.message();
            HexieTrain.LOGGER.info("[HexieTrain][presence] 建车 ok={} msg={}", result.ok(), result.message());
            if (result.ok()) {
               this.bindTrain(result.trainId());
               if (SubLevelSuspend.resume(serverLevel, this.worldPosition)) {
                  TrainBinder.PERSISTENT_LOADS.add(result.trainId());
               }
            }
         } catch (Throwable var3) {
            HexieTrain.LOGGER.error("[HexieTrain][presence] 建车异常 @{}", this.worldPosition, var3);
         }
      }
   }

   private void syncTrainPresence() {
      if (this.level != null && !this.level.isClientSide) {
         boolean shouldExist = !this.scheduleSlot.isEmpty() && this.scheduleSlot.getItem() instanceof ScheduleItem;
         if (!shouldExist) {
            if (this.boundTrain != null) {
               HexieTrain.LOGGER.info("[HexieTrain][presence] 注销虚拟列车（手动/无时刻表）");
               this.dissolve();
            }

            this.resetRuntimeState();
         } else if (this.boundTrain == null) {
            HexieTrain.LOGGER.info("[HexieTrain][presence] 重建虚拟列车（自动 + 有时刻表）");
            this.bindTrainNow();
         }
      }
   }

   /** 解绑态：所有输出归零，转向遥控条目清掉。不抱闸、不牵引 —— 完全撒手。 */
   private void releaseEverything() {
      try {
         this.setOutput(SignalChannel.THROTTLE, 0);
         this.setOutput(SignalChannel.BRAKE, 0);
      } catch (Throwable t) {
      }
      try {
         if (this.level instanceof ServerLevel sl) {
            // 切转速：真正驱车的是这个换向器的旋转输出，不是无线红石。不解开它就一直在转。
            DispatcherGearbox.engage(sl, this.worldPosition, DispatcherGearbox.Face.NONE);
            this.steering.releaseAll(sl, this.worldPosition, this.roster);
         }
      } catch (Throwable t) {
      }
   }

   private void resetRuntimeState() {
      this.legRemaining = -1.0;
      this.lastLegDest = null;
      this.lastLegEntry = -1;
      this.dwellingStation = null;
      this.lastDestName = "";
      this.lastEntryLogged = -1;

      try {
         this.setOutput(SignalChannel.BRAKE, 0);
      } catch (Throwable var2) {
      }
   }

   public int output(SignalChannel channel) {
      return this.outputs[channel.index()];
   }

   public ItemStack frequency(int slot) {
      return slot >= 0 && slot < FREQ_SLOTS ? this.frequencies[slot] : ItemStack.EMPTY;
   }

   private void loadOwnRecordOnce() {
      if (!this.recordLoaded) {
         this.recordLoaded = true;

         try {
            if (this.level instanceof ServerLevel sl && this.assemblyId != null) {
               CompoundTag tag = DispatcherStore.load(this.assemblyId);
               if (tag == null) {
                  return;
               }

               if (tag.hasUUID("Train")) {
                  this.boundTrain = tag.getUUID("Train");
                  TrainBinder.OUR_TRAINS.add(this.boundTrain);
                  BY_TRAIN.put(this.boundTrain, this);
               }

               this.myEntry = tag.getInt("Entry");
               this.manualMode = tag.getBoolean("Manual");
               if (tag.contains("MyDir")) {
                  this.myDir = tag.getFloat("MyDir");
               }

               if (tag.contains("MyDirEntry")) {
                  this.myDirEntry = tag.getInt("MyDirEntry");
               }

               if (tag.contains("WaitingEntry")) {
                  this.waitingEntry = tag.getInt("WaitingEntry");
               }

               if (tag.contains("Arrived")) {
                  this.arrivedFlag = tag.getBoolean("Arrived");
               }

               if (tag.contains("Mirror")) {
                  this.mirror.restore(tag.getCompound("Mirror"), this.myEntry, this.myEntry);
               }

               for (int i = 0; i < FREQ_SLOTS; i++) {
                  if (tag.contains("Freq" + i)) {
                     this.frequencies[i] = ItemStack.parseOptional(sl.registryAccess(), tag.getCompound("Freq" + i));
                     this.pushFrequency(i, this.frequencies[i]);
                  }
               }

               HexieTrain.LOGGER.info("[store] 已从独立文件恢复 {} → entry={} 频率位={}", new Object[]{this.assemblyId, this.myEntry, this.frequencies.length});
               this.refreshWireless();
               return;
            }
         } catch (Throwable var4) {
            HexieTrain.LOGGER.warn("[store] 恢复异常 {}", var4.toString());
         }
      }
   }

   public void forgetRecordOnBreak() {
      try {
         if (this.assemblyId != null) {
            DispatcherStore.delete(this.assemblyId);
            if (this.level instanceof ServerLevel sl) {
               TrainBindings.remove(sl, this.assemblyId);
            }

            HexieTrain.LOGGER.info("[store] 拆除 → 已删除独立存档与映射 {}", this.assemblyId);
         }
      } catch (Throwable var3) {
         HexieTrain.LOGGER.warn("[store] 拆除清理异常 {}", var3.toString());
      }
   }

   private void saveOwnRecord() {
      try {
         if (this.level instanceof ServerLevel sl && this.assemblyId != null) {
            CompoundTag tag = new CompoundTag();
            if (this.boundTrain != null) {
               tag.putUUID("Train", this.boundTrain);
            }

            tag.putInt("Entry", this.myEntry);
            tag.putBoolean("Manual", this.manualMode);
            tag.putFloat("MyDir", this.myDir);
            tag.putInt("MyDirEntry", this.myDirEntry);
            tag.putInt("WaitingEntry", this.waitingEntry);
            tag.putBoolean("Arrived", this.arrivedFlag);
            if (this.mirror.blob() != null) {
               tag.put("Mirror", this.mirror.blob().copy());
            }

            for (int i = 0; i < FREQ_SLOTS; i++) {
               if (!this.frequencies[i].isEmpty()) {
                  tag.put("Freq" + i, this.frequencies[i].save(sl.registryAccess()));
               }
            }

            DispatcherStore.save(this.assemblyId, tag);
            return;
         }
      } catch (Throwable var4) {
         HexieTrain.LOGGER.warn("[store] 保存记录异常 {}", var4.toString());
      }
   }

   public void setFrequency(int slot, ItemStack stack) {
      if (slot >= 0 && slot < FREQ_SLOTS) {
         this.frequencies[slot] = stack == null ? ItemStack.EMPTY : stack;
         this.pushFrequency(slot, stack);
         this.setChanged();
         this.saveOwnRecord();
         this.refreshWireless();
         this.sendData();
      }
   }

   public UUID boundTrain() {
      return this.boundTrain;
   }

   public void bindTrain(UUID id) {
      BY_TRAIN.put(id, this);
      this.boundTrain = id;
      this.lastDestination = null;
      this.setChanged();
      this.sendData();
   }

   public boolean linearizeBrake() {
      return this.linearizeBrake;
   }

   public void setLinearizeBrake(boolean value) {
      this.linearizeBrake = value;
      this.setChanged();
   }

   protected void write(CompoundTag tag, Provider registries, boolean clientPacket) {
      super.write(tag, registries, clientPacket);
      if (this.boundTrain != null) {
         tag.putUUID("BoundTrain", this.boundTrain);
      }

      if (this.mirror.blob() != null) {
         tag.put("MirrorBlob", this.mirror.blob().copy());
      }

      tag.putBoolean("ManualMode", this.manualMode);
      if (this.owner != null) {
         tag.putUUID("Owner", this.owner);
      }

      if (!this.scheduleSlot.isEmpty()) {
         tag.put("Schedule", this.sanitizedSchedule().save(registries));
      }

      ListTag names = new ListTag();

      for (String s : this.stopNames) {
         names.add(StringTag.valueOf(s));
      }

      tag.put("StopNames", names);
      tag.put("BogeyRoster", this.roster.save());
      // 车厢数只能服务端数：客户端看不到 Sable 子层。每次写包/存档重算一次（N 次 plot 查表，很便宜）。
      if (this.level != null && !this.level.isClientSide) {
         this.rosterCars = this.countRosterCars();
      }
      tag.putInt("RosterCars", this.rosterCars);
      tag.putBoolean("LinearizeBrake", this.linearizeBrake);

      // UI 遥测：这些只在服务端算，必须随包同步，否则客户端那份 BE 永远是默认值
      tag.putBoolean("ProbeOk", this.probeOk);
      tag.putBoolean("ProbeBack", this.probeBackwards);
      tag.putInt("ProbeEdges", this.probeEdges);
      tag.putDouble("ProbeMs", this.probeMillis);
      tag.putDouble("ProbeDist", this.cacheDistShown);
      // 面标定结果必须持久化：否则每次读档重标，车会在站里乱动
      tag.putInt("DirSign", this.dirSign);
      tag.putInt("DirFace", this.dirFace);
      if (this.dirBogey != null) tag.putLong("DirBogey", this.dirBogey.asLong());
      if (this.calTrain != null) tag.putUUID("CalTrain", this.calTrain);

      // FreqLayout：2 = 制动×2 + 节流阀×2（0.62.0 起）。缺省/0 = 旧的四通道八槽布局，读的时候要迁移。
      tag.putInt("FreqLayout", 2);
      for (int i = 0; i < FREQ_SLOTS; i++) {
         if (!this.frequencies[i].isEmpty()) {
            tag.put("Freq" + i, this.frequencies[i].save(registries));
         }
      }
   }

   protected void read(CompoundTag tag, Provider registries, boolean clientPacket) {
      super.read(tag, registries, clientPacket);
      if (tag.hasUUID("BoundTrain")) {
         this.boundTrain = tag.getUUID("BoundTrain");
      }

      // ---- 以下这些**只在服务端**做 ----
      // 客户端每收到一次同步包也会走这个 read()（实测 952 次 vs 服务端 2 次），
      // 以前这里无条件往 TrainBinder 的静态 map 里写、还打日志，纯属白烧。
      if (!clientPacket) {
         if (this.boundTrain != null) {
            TrainBinder.TRAIN_DISPATCHERS.putIfAbsent(this.boundTrain, this.worldPosition);
            TrainBinder.OUR_TRAINS.add(this.boundTrain);
            HexieTrain.LOGGER.info("[HexieTrain] 从存档恢复列车绑定 {}", this.boundTrain);
         }

         try {
            if (this.level != null) {
               ScheduleMirror.provider(this.level.registryAccess());
            }
            this.mirror.restore(tag.getCompound("MirrorBlob"), tag.getInt("MyEntry"), tag.getInt("MyEntry"));
         } catch (Throwable var7) {
         }
      }

      this.owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
      this.roster.load(tag.getCompound("BogeyRoster"));
      this.rosterCars = tag.getInt("RosterCars");
      this.stallTicks = 0;
      this.lastLeadPos = null;
      this.lastDestination = null;
      this.linearizeBrake = !tag.contains("LinearizeBrake") || tag.getBoolean("LinearizeBrake");

      this.probeOk = tag.getBoolean("ProbeOk");
      this.probeBackwards = tag.getBoolean("ProbeBack");
      this.probeEdges = tag.getInt("ProbeEdges");
      this.probeMillis = tag.getDouble("ProbeMs");
      this.cacheDistShown = tag.getDouble("ProbeDist");
      // 面标定结果必须持久化：否则每次读档重标，车会在站里乱动
      this.dirSign = tag.getInt("DirSign");
      this.dirFace = tag.getInt("DirFace") == 2 ? 2 : 1;
      // 标定的物理端必须存回来：它决定了"哪个面把车推向哪一端"，是整套方向逻辑的事实
      this.dirBogey = tag.contains("DirBogey") ? BlockPos.of(tag.getLong("DirBogey")) : null;
      // 有面没端 = 半个事实，等于没标定
      if (this.dirBogey == null) this.dirSign = 0;
      this.calPhase = this.dirSign != 0 ? 3 : 0;
      this.calTrain = tag.hasUUID("CalTrain") ? tag.getUUID("CalTrain") : null;
      this.scheduleSlot = tag.contains("Schedule") ? ItemStack.parseOptional(registries, tag.getCompound("Schedule")) : ItemStack.EMPTY;
      this.stopNames.clear();
      ListTag names = tag.getList("StopNames", 8);

      for (int i = 0; i < names.size(); i++) {
         this.stopNames.add(names.getString(i));
      }

      // 频率槽迁移。旧布局（FreqLayout 缺省）：0,1=制动 2,3=左道岔 4,5=右道岔 6,7=节流阀。
      // 0.62.0 起 LEFT/RIGHT 两个道岔红石通道整条删除，只剩 0,1=制动 2,3=节流阀。
      for (int i = 0; i < FREQ_SLOTS; i++) {
         this.frequencies[i] = ItemStack.EMPTY;
      }
      if (tag.getInt("FreqLayout") >= 2) {
         for (int i = 0; i < FREQ_SLOTS; i++) {
            String key = "Freq" + i;
            this.frequencies[i] = tag.contains(key) ? ItemStack.parseOptional(registries, tag.getCompound(key)) : ItemStack.EMPTY;
         }
      } else {
         int[] oldToNew = new int[]{0, 1, -1, -1, -1, -1, 2, 3};
         int dropped = 0;
         for (int i = 0; i < oldToNew.length; i++) {
            String key = "Freq" + i;
            ItemStack stack = tag.contains(key) ? ItemStack.parseOptional(registries, tag.getCompound(key)) : ItemStack.EMPTY;
            int dst = oldToNew[i];
            if (dst < 0) {
               if (!stack.isEmpty()) dropped++;
            } else if (dst < FREQ_SLOTS) {
               this.frequencies[dst] = stack;
            }
         }
         if (!clientPacket && dropped > 0) {
            HexieTrain.LOGGER.warn("[freq] 旧存档里有 {} 个「道岔红石」频率已丢弃（LEFT/RIGHT 通道已删除）", dropped);
         }
      }

      // 频率推送与红石链路重建同样只在服务端有意义
      if (!clientPacket) {
         for (int fi = 0; fi < FREQ_SLOTS; fi++) {
            this.pushFrequency(fi, this.frequencies[fi]);
         }
         this.refreshWireless();
      }
   }

   private void updateMeasuredSpeed() {
      if (this.level instanceof ServerLevel serverLevel) {
         Vector3d var5 = SubLevelSuspend.velocityAt(serverLevel, this.worldPosition);
         this.measuredVelocity.set(var5);
         double mag = var5.length();
         this.measuredSpeedMps = mag > 0.05 ? mag : 0.0;
      }
   }

   public double measuredSpeedMps() {
      return this.measuredSpeedMps;
   }

   public Vector3dc measuredVelocity() {
      return this.measuredVelocity;
   }

   public int measuredSpeedCenti() {
      return (int)Math.round(this.measuredSpeedMps * 100.0);
   }

   private double bodySpeedAlongTrackBps() {
      try {
         Train tr = (Train)Create.RAILWAYS.trains.get(this.boundTrain);
         if (tr != null && !tr.carriages.isEmpty()) {
            TravellingPoint lp = ((Carriage)tr.carriages.get(0)).getLeadingPoint();
            if (lp != null && lp.edge != null) {
               double len = Math.max(1.0E-6, lp.edge.getLength());
               Vec3 dir = lp.edge.getDirectionAt(Math.min(1.0, lp.position / len));
               Vector3dc v = this.measuredVelocity();
               boolean sameOrder = lp.node1 == lp.edge.node1;
               double dirSign = sameOrder ? 1.0 : -1.0;
               return dirSign * (v.x() * dir.x + v.y() * dir.y + v.z() * dir.z);
            } else {
               return 0.0;
            }
         } else {
            return 0.0;
         }
      } catch (Throwable var101) {
         return 0.0;
      }
   }

   public void setOwner(UUID playerId) {
      if (playerId != null && !playerId.equals(this.owner)) {
         this.owner = playerId;
         this.setChanged();
      }
   }

   public UUID owner() {
      return this.owner;
   }

   private double edgeLen(Train train) {
      try {
         return ((Carriage)train.carriages.get(0)).getLeadingPoint().edge.getLength();
      } catch (Throwable var3) {
         return -1.0;
      }
   }

   private double edgePos(Train train) {
      try {
         return ((Carriage)train.carriages.get(0)).getLeadingPoint().position;
      } catch (Throwable var3) {
         return -1.0;
      }
   }

   private String virtPos(Train train) {
      try {
         TravellingPoint lp = ((Carriage)train.carriages.get(0)).getLeadingPoint();
         Vec3 p = lp.getPosition(train.graph);
         return String.format("(%.1f,%.1f,%.1f)", p.x, p.y, p.z);
      } catch (Throwable var41) {
         return "?";
      }
   }

   private String statPos(GlobalStation st) {
      if (st == null) {
         return "?";
      } else {
         try {
            Vec3 a = ((TrackNodeLocation)st.edgeLocation.getFirst()).getLocation();
            Vec3 b = ((TrackNodeLocation)st.edgeLocation.getSecond()).getLocation();
            return String.format("(%.1f,%.1f,%.1f)", (a.x + b.x) / 2.0, (a.y + b.y) / 2.0, (a.z + b.z) / 2.0);
         } catch (Throwable var41) {
            return "?";
         }
      }
   }

   private double virtDist(Train train, GlobalStation st) {
      if (st == null) {
         return -1.0;
      } else {
         try {
            TravellingPoint lp = ((Carriage)train.carriages.get(0)).getLeadingPoint();
            Vec3 p = lp.getPosition(train.graph);
            Vec3 a = ((TrackNodeLocation)st.edgeLocation.getFirst()).getLocation();
            Vec3 b = ((TrackNodeLocation)st.edgeLocation.getSecond()).getLocation();
            double mx = (a.x + b.x) / 2.0;
            double my = (a.y + b.y) / 2.0;
            double mz = (a.z + b.z) / 2.0;
            double dx = p.x - mx;
            double dy = p.y - my;
            double dz = p.z - mz;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
         } catch (Throwable var191) {
            return -1.0;
         }
      }
   }

   private double physicalDistanceTo(GlobalStation st) {
      try {
         ServerLevel sl = (ServerLevel)this.level;
         ServerSubLevel body = SubLevelSuspend.containing(sl, this.worldPosition);
         if (body == null) {
            return 0.0;
         } else {
            Vector3d p = body.logicalPose().position();
            Vec3 n1 = ((TrackNodeLocation)st.edgeLocation.getFirst()).getLocation();
            Vec3 n2 = ((TrackNodeLocation)st.edgeLocation.getSecond()).getLocation();
            Vec3 mid = n1.add(n2).scale(0.5);
            double dx = p.x() - mid.x;
            double dz = p.z() - mid.z;
            return Math.sqrt(dx * dx + dz * dz);
         }
      } catch (Throwable var121) {
         return 0.0;
      }
   }



   private void SetOutputBrake(int value) {
      int before = this.output(SignalChannel.BRAKE);
      this.setOutput(SignalChannel.BRAKE, Math.max(0, Math.min(15, value)));
   }

   private void refreshKinetics() {
      if (this.level != null && !this.level.isClientSide && this.getBlockState().getBlock() instanceof TrainDispatcherBlock block) {
         this.level.blockUpdated(this.worldPosition, block);
      }
   }

   public int currentEntry() {
      try {
         return Math.max(0, this.myEntry);
      } catch (Throwable var2) {
         return 0;
      }
   }

   private boolean isPrimaryDispatcher() {
      if (this.boundTrain == null) {
         return false;
      } else {
         BlockPos primary = TrainBinder.TRAIN_DISPATCHERS.get(this.boundTrain);
         if (primary == null) {
            TrainBinder.TRAIN_DISPATCHERS.put(this.boundTrain, this.worldPosition);
            return true;
         } else {
            return primary != null && primary.equals(this.worldPosition);
         }
      }
   }

   public DispatcherState dispatcherState() {
      return this.dispatcherState;
   }

   private void setDispatcherState(DispatcherState state) {
      if (this.dispatcherState != state) {
         this.dispatcherState = state;
         this.sendData();
      }
   }

   private void ensureChannels() {
      for (int i = 0; i < this.wireless.length; i++) {
         if (this.wireless[i] == null) {
            SignalChannel ch = SignalChannel.VALUES[i];
            if (ch != null) {
               this.wireless[i] = new WirelessChannel(this, ch);
            }
         }
      }
   }

   private void refreshWireless() {
      this.ensureChannels();
      if (this.level != null && !this.level.isClientSide) {
         for (int wi = 0; wi < this.wireless.length; wi++) {
            if (this.wireless[wi] == null) {
               this.wireless[wi] = new WirelessChannel(this, SignalChannel.VALUES[wi]);
            }

            WirelessChannel ch = this.wireless[wi];
            if (ch != null) {
               try {
                  Create.REDSTONE_LINK_NETWORK_HANDLER.removeFromNetwork(this.level, ch);
                  // 先摘再按需挂：双空的一对不挂（见 channelConfigured 的注释）
                  if (this.channelConfigured(SignalChannel.VALUES[wi])) {
                     Create.REDSTONE_LINK_NETWORK_HANDLER.addToNetwork(this.level, ch);
                  }
               } catch (Throwable var4) {
               }
            }
         }
      }
   }

   private ItemStack sanitizedSchedule() {
      ItemStack stack = this.scheduleSlot.copy();
      if (stack.isEmpty()) {
         return stack;
      } else {
         try {
            if (stack.getItem() instanceof ScheduleItem scheduleItem) {
               DataComponentType type = scheduleItem.getComponentType();
               Object value = stack.get(type);
               if (value != null && !(value instanceof CompoundTag)) {
                  stack.remove(type);
                  HexieTrain.LOGGER.warn("[HexieTrain] 已清除损坏的时刻表组件（类型 {}），存档可继续", value.getClass().getName());
               }
            }
         } catch (Throwable var51) {
            HexieTrain.LOGGER.warn("[HexieTrain] 净化时刻表失败: {}", var51.toString());
         }

         return stack;
      }
   }

   public int getContainerSize() {
      return TrainDispatcherContainer.SIZE;
   }

   public boolean isEmpty() {
      for (int i = 0; i < this.getContainerSize(); i++) {
         if (!this.slot(i).isEmpty()) {
            return false;
         }
      }

      return true;
   }

   public ItemStack getItem(int index) {
      return this.slot(index);
   }

   /**
    * 把当前游标写进一个时刻表物品的 savedProgress（不动槽位）。
    *
    * <p>玩家取出时刻表时看到的目标必须是他当前要去的站，而不是"放进去时那一站"。
    */
   /** 取出时把当前游标盖进要交出去的那个物品。 */
   private void stampProgress(ItemStack stack) {
      this.stampInto(stack);
   }

   public ItemStack removeItem(int index, int amount) {
      ItemStack current = this.slot(index);
      if (current.isEmpty()) {
         return ItemStack.EMPTY;
      } else {
         ItemStack taken = current.split(amount);
         this.setItem(index, current);
         if (index == 0) this.stampProgress(taken);   // 带走的物品要有当前进度
         return taken;

      }
   }

   public ItemStack removeItemNoUpdate(int index) {
      ItemStack current = this.slot(index);
      this.setItem(index, ItemStack.EMPTY);
      if (index == 0) this.stampProgress(current);
      return current;

   }

   public void setItem(int index, ItemStack stack) {
      if (index == 0) {
         this.setScheduleSlot(stack);
      } else {
         this.setFrequency(index - 1, stack);
      }
   }

   public boolean stillValid(Player player) {
      return this.hasLevel()
         && player.distanceToSqr((double)this.worldPosition.getX() + 0.5, (double)this.worldPosition.getY() + 0.5, (double)this.worldPosition.getZ() + 0.5)
            <= 64.0;
   }

   public void clearContent() {
      this.setScheduleSlot(ItemStack.EMPTY);

      for (int i = 0; i < FREQ_SLOTS; i++) {
         this.setFrequency(i, ItemStack.EMPTY);
      }
   }
}
