package willow.train.hexietrain;

import com.crystaelix.simurail.api.track.TrackTypeOverrides;
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
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackMaterial;
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

   /**
    * 两个无线输出通道的开关。默认全开（保持 1.4.0 行为）。
    *
    * <p>关掉只影响<b>往外发</b>（{@link #transmittedStrength}），不影响 {@link #output}
    * —— UI 的 15 格条显示的是"需求值"，这个区分是有意的。
    */
   private final boolean[] wirelessEnabled = new boolean[]{true, true};

   /** 右上角设置栏的数据（上下限等）。落盘走 {@link DispatcherStore}。 */
   private final DispatcherSettings settings = new DispatcherSettings();
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

   // ---- 信号锁存（红灯 = 原地停车等待，不是"边刹边蹭"）----
   /**
    * 已经判红、正在等放行的那台信号机。null = 没锁。
    *
    * <p>为什么不每拍重新找「前方最近的信号」：车往前挪一点，最近的那台就会换成下一台
    * （或者车头已经越过它），于是这一拍判红、下一拍判绿 —— 2026-11-21 的日志里
    * {@code [sig]} 与 {@code [drive]} 正是隔拍交替，车是闪着刹车冲过红灯的。
    * 锁存之后「停车等放行」才真的是等。
    */
   private RailCache.Hit sigHold = null;
   /** 锁存的那台信号机本体。每一拍重新 observe 用它。 */
   private SignalBoundary sigHoldSignal = null;
   /** 锁存时这条 leg 的进入端节点。 */
   private TrackNode sigHoldEnter = null;
   /** 锁存已经等了多少拍（只给日志限流用）。 */
   private int sigHoldTicks = 0;
   /**
    * 已经打过"这个区间里到底有谁"的那个区间 id（只给日志限流用）。
    * 换区间才重打，所以每台新信号机最多多一行 {@code [sig][是谁]} 日志。
    */
   private UUID censusLoggedGroup = null;
   /** 本次锁存里"已经停稳"的日志是否打过（只给日志限流用）。 */
   private boolean sigStopLogged = false;
   /** 锁存期间连续判 CLEAR 的次数。够 SIG_CLEAR_DEBOUNCE 才真放行（防红石抖一拍就松闸蹭过去）。 */
   private int sigClearTicks = 0;
   /** 放行所需的连续 CLEAR 次数：4 次决策 = 20 tick = 1 秒。 */
   private static final int SIG_CLEAR_DEBOUNCE = 4;
   // ---- 建车失败退避（失败的真正原因由 [presence] 日志打出来）----
   /** 还要等几拍才允许再试建车。0 = 可以试。 */
   private int presenceRetry = 0;
   /** 上一次失败原因，只用来做「原因变了才打日志」的去重。 */
   private String presenceLastReason = null;
   /** 连续失败次数，只给日志限流用。 */
   private int presenceFails = 0;
   /** 上一次规划失败的原因，只用来做「原因变了才打日志」的去重（反向信号机会一直失败）。 */
   private String planFailNote = null;
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

   /** 阶段 4：把制动指令写进物理转向架。 */
   private final BogeyBraking bogeyBraking = new BogeyBraking();
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
   /**
    * 连续几拍"绑定的转向架解析不全"。1.2.0 加的 <b>debounce</b>：
    * 以前只要缺一根就立刻 {@code driveResult = null} + 冷却 10 拍，
    * 而实测 4→3 这种一过性缩水（某根转向架跨边那一拍）很常见，
    * 一次抖动就让车凭空多抱闸近 3 秒、还刷一条吓人的 WARN。
    * 现在先只抱闸等下一拍，连续 {@link #PLAN_SHRINK_CONFIRM} 拍都缺才认定是真的坏了。
    */
   private int planShrinkTicks = 0;
   /** 认定"解析不全"需要连续多少拍。 */
   private static final int PLAN_SHRINK_CONFIRM = 3;
   /**
   /** 绑定表垃圾回收周期（tick）。20 = 每秒一次，够快又不吵。 */
   private static final int ROSTER_SWEEP_INTERVAL = 20;
   /** 距下次回收还有几 tick。 */
   private int rosterSweepTicks = ROSTER_SWEEP_INTERVAL;
   /** 每段最多多少次决策（每次 5 tick）：400 ⇒ 2000 tick ⇒ 100 秒 */
   private static final int DRIVE_TICK_CAP = 400;
   /** 虚拟车占区同步周期（tick）：10 = 0.5 秒。多车共存全靠它，别调大。 */
   private static final int OCCUPANCY_SYNC_TICKS = 10;
   /** 信号锁存超时（决策次数）：240 × 0.25 s = 60 秒。超时就强行松开并报错，防死锁。 */
   private static final int SIG_HOLD_TIMEOUT = 240;
   /** 物理结构连续几次被判定为"不在轨道上"。 */
   private int offTrackTicks = 0;
   /** 脱轨车清占区日志限流。 */
   private int derailedLogTicks = 0;
   /** 驱不动后的停手拍数：>0 时只抱闸，每 40 拍轻放一次再试。 */
   private int stallHoldTicks = 0;
   /** 红信号限速日志限流。 */
   private int sigCapLogTicks = 0;
   /** 距下次占区同步还有几拍。 */
   private int occupancySyncTicks = 0;
   /** 信号前视台数：按弧长往前后最多检查几台信号机（绿灯不算完，继续看下一台）。 */
   private static final int SIGNAL_LOOKAHEAD = 5;
   /** 建车失败后的重试间隔（tick）：40 = 2 秒。以前是 20，等于每秒造一条失败日志。 */
   private static final int PRESENCE_RETRY_TICKS = 40;
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
   /** 归属：第一次用连接器点这个调度器的人。null = 无主（谁先点谁认主）。 */
   private UUID ownerUUID = null;
   /** 归属者名字。只存 UUID 的话离线查名字要走 GameProfileCache，异步且可能查不到。 */
   private String ownerName = "";
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

   /**
    * 距站三态（1.2.0，喂给 UI 的数据槽 13）。
    *
    * <p>以前"还没规划出来/寻路失败"和"规划好了正在等红灯"在界面上都是 {@code 距站：--}，
    * 看不出是哪一种，只能去翻日志。分开之后：
    * <ul>
    *   <li>{@code 0} 正常 —— 有数字就显示数字；{@code probeOk()==false} 时仍显示 {@code --}</li>
    *   <li>{@code 1} 寻路失败 —— 有 {@code driveResult} 但 {@code ok==false}</li>
    *   <li>{@code 2} 等待信号开放 —— 正锁存着某台信号机</li>
    * </ul>
    * 优先级：等灯 &gt; 寻路失败。因为等灯时 {@code driveResult} 通常仍是 ok 的，
    * 真正让人以为"卡住"的是等灯，得先说清。
    */
   public int uiDistanceState() {
      if (this.sigHold != null) {
         return TrainDispatcherMenu.DIST_STATE_WAIT_SIGNAL;
      }
      if (this.driveResult != null && !this.driveResult.ok) {
         return TrainDispatcherMenu.DIST_STATE_PLAN_FAILED;
      }
      return TrainDispatcherMenu.DIST_STATE_OK;
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

   /**
    * 未绑定 = 没有时刻表 + 不在手动 + 侧面没有任何红石输入。
    *
    * <p>这里读的是<b>缓存的</b> {@code extCounterClockwise/extClockwise}（每拍由
    * {@code updateExternalInput} 写入），不是当场的 {@code faceSignal()}：
    * 后者在邻居区块未就绪时会返回 0（见 {@code faceSignal} 的注释），
    * 一抖就把状态打回"未绑定"，界面跟着闪。
    */
   public boolean isUnbound() {
      return this.scheduleSlot.isEmpty() && !this.manualMode
         && this.extCounterClockwise == 0 && this.extClockwise == 0;
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
      // 解析出的根数必须等于绑定根数：少一根就说明有转向架没加载/已消失，
      // 拿剩下的去当"两端"会把判据从 N 根降成 1 根（2026-11-21 18:24 后端==领先端、跨度 0.00m 就是这么来的）。
      if (bs.size() != this.roster.size()) return;
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

   /**
    * 绑定表垃圾回收：转向架被破坏 / 拆走后自动解绑。
    *
    * <p>判据只能是"区块已加载 <b>且</b> 那个位置已经不是活着的转向架"。
    * 区块没加载时我们<b>不知道</b>它在不在，不能当依据 —— 否则车跑远、区块卸载，绑定就被清光。
    *
    * <p>以前这张表只进不出：方块没了也一直绑着那个坐标，成了谁也解不掉的僵尸条目。
    */
   private void sweepRoster() {
      if (this.level == null || this.roster.size() <= 0) return;
      List<BlockPos> dead = new ArrayList<>();
      for (BlockPos p : this.roster.positions()) {
         if (!this.level.hasChunkAt(p)) continue;                     // 不知道 ⇒ 保留
         if (this.level.getBlockEntity(p) instanceof PhysicsBogeyBlockEntity b && !b.isRemoved()) continue;
         dead.add(p);
      }
      if (dead.isEmpty()) return;
      for (BlockPos p : dead) this.roster.remove(p);
      HexieTrain.LOGGER.info("[roster] 转向架已消失 ⇒ 自动解绑 {} 根 {} ⇒ {}", new Object[]{
         dead.size(), describePositions(dead), this.roster.describe()});
      this.setChanged();
      this.notifyUpdate();
   }

   private static String describePositions(List<BlockPos> ps) {
      StringBuilder sb = new StringBuilder();
      for (BlockPos p : ps) {
         if (sb.length() > 0) sb.append(' ');
         sb.append(p.toShortString());
      }
      return sb.toString();
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

   public boolean hasOwner() { return this.ownerUUID != null; }
   public boolean isOwner(net.minecraft.world.entity.player.Player p) {
      return p != null && this.ownerUUID != null && this.ownerUUID.equals(p.getUUID());
   }
   public String ownerName() { return this.ownerName == null || this.ownerName.isEmpty() ? "?" : this.ownerName; }
   /** 只有无主时才认主，已有主一律不覆盖。 */
   public boolean claimOwner(net.minecraft.world.entity.player.Player p) {
      if (p == null || this.ownerUUID != null) return false;
      this.ownerUUID = p.getUUID();
      this.ownerName = p.getName().getString();
      this.setChanged();
      this.notifyUpdate();
      HexieTrain.LOGGER.info("[owner] 认主：调度器 {} 归属 {}（{}）", new Object[]{
         this.worldPosition.toShortString(), this.ownerName, this.ownerUUID});
      return true;
   }
   /** 非归属者的拒绝文案。 */
   public String denyText(String op) { return "这是 " + ownerName() + " 的调度器，你不能" + op; }

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
    * 一键清除绑定。<b>只作用于这一个调度器自己的绑定表</b> —— 表存在本 BE 的 NBT 里，
    * 天然按调度器隔离，别的调度器绑了多少根一根都不会动。
    *
    * <p><b>顺序不能反：</b>{@link #releaseEverything()} 必须拿着<b>还没被清空</b>的绑定表，
    * 才能逐个转向架把遥控转向条目清掉（{@code BogeySteering.releaseAll} 是遍历这张表的）。
    * 先清表再撒手，那些遥控条目就只能等 Simurail 自己过期（3 tick）。
    */
   public void clearBindings(String reason) {
      int before = this.roster.size();
      long vid = this.roster.vehicleId();
      // ① 先撒手：输出归零 + 换向器松开 + 逐个转向架清遥控条目
      this.releaseEverything();
      // ② 把这一段的导航状态全部作废（和层 0 解绑同一套字段，一处都不许留）
      this.driveResult = null;
      this.driveTarget = null;
      this.cacheDistShown = Double.NaN;
      this.legRemaining = -1.0;
      this.arrivedFlag = false;
      this.driveHalted = false;
      this.drivePhase = PH_IDLE;
      this.throttleCmd = 0;
      this.brakeCmd = 0;
      this.probeOk = false;
      this.navTailPos = null;
      this.navHeadPos = null;
      // 面标定的物理端可能是刚被清掉的那根转向架，一并作废（换表就要重标）
      this.invalidateDirection();
      this.setDispatcherState(DispatcherState.IDLE);
      // ③ 清表 + 车辆ID 作废
      this.roster.clearBindings();
      this.setChanged();
      this.notifyUpdate();
      HexieTrain.LOGGER.info("[roster] 一键清除绑定（{}）：转向架 {} 根 车辆ID={} ⇒ 已清空", new Object[]{
         reason, before, vid});
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

   /**
    * 阶段 1：<b>只拍快照，不做任何决策</b>。
    *
    * <p>把本车每一节车厢、每一根转向架的两个端点抄成物理轴<b>当 tick</b> 的位置
    * （{@link TrainBinder#reanchor}）。由 {@code DispatcherTicker} 先把<b>所有</b>调度器
    * 都拍完，才进入阶段 2 的 {@link #serverTick()}，于是任何一辆车做超速/停点/信号判断时，
    * 看到的都是其他车<b>本 tick</b> 的最新位置，而不是差一 tick 的旧位置。
    *
    * <p>为什么这一拍很重要：多车同图时，若 A 先算完决策 B 才挪位置，
    * B 的占区就比 A 的判断晚 20 分之 1 秒 —— 车越快错得越多（20 m/s 时 1 m）。
    * 我们的制动窗口本身留了 6 m 余量，1 m 不至于闯红灯，但"差一 tick"是系统性的、
    * 车越快越危险，而且它会让日志里的距离对不上，浪费排查时间。既然只花一次查表，
    * 就把它钉成结构上的"先全员快照、再全员决策"。
    *
    * <p>幂等：{@link #serverTick()} 内部的 reanchor 调用照旧保留，
    * 重复抄同一批点是纯拷贝，没有副作用。
    */
   public void snapshotPhysical() {
      try {
         // 注意：**不许**在这里按 manualMode 早退。
         // 位置镜像（虚拟车 ← 物理轴）与"谁在开车"无关：玩家手操时物理车照样在动，
         // 虚拟车必须照样跟；否则占区冻在上一处、别的车看它那段永远是绿的。
         // 1.2.0 实测：手操状态下虚拟车不跟实体车，就是这里 return 掉了
         // （serverTick 的手操分支也在 reanchor 之前就 return，两处同时把它挡住）。
         if (this.level == null || this.level.isClientSide) {
            return;
         }
         if (!this.level.hasChunkAt(this.worldPosition)) {
            return;
         }
         if (!(this.level instanceof ServerLevel serverLevel)) {
            return;
         }
         Train train = this.boundTrain == null ? null : (Train)Create.RAILWAYS.trains.get(this.boundTrain);
         if (train == null || train.carriages.isEmpty()) {
            return;
         }
         // 结构登记是内存态：世界重载后 boundTrain 从存档恢复、bind 不会再被调用，
         // 于是 STRUCTURE 是空的、reanchor 整次空转、虚拟车冻在上一个存档的位置上。
         // 这里按需补登记（节数对不上就不登记，绝不写半列车）。
         if (!TrainBinder.structureMatches(this.boundTrain, train.carriages.size())) {
            TrainBinder.registerStructureFrom(this.boundTrain, serverLevel, this.rosterBogeys());
         }
         TrainBinder.reanchor(train, serverLevel, this.worldPosition);
         // 占区也必须在这一阶段同步（以前它只在 serverTick 的非手操分支里，手操时就不更新了）。
         this.syncOccupancy(train);
         // 名字：物理结构的铭牌（simulated 的 NameplateBlock）写的是 Sable 的 SubLevel.name，
         // 这里每拍比一次、变了才抄到虚拟车的 Train.name 上。见 syncTrainName 的注释。
         this.syncTrainName();
      } catch (Throwable ignored) {
      }
   }

   /**
   /** 上一次注入到虚拟车里的名字（来自物理结构的铭牌），只用来判断"变了没"。 */
   private String lastTrainName = null;
   /**
    * 上一次<b>注入到哪一台车对象</b>上。
    *
    * <p>必须按<b>对象身份</b>判，不能只比名字：虚拟车重建时是个**新的 Train 对象**，
    * 名字回到构造默认的「未命名列车」，而 {@link #lastTrainName} 还等于铭牌名 ——
    * 只比名字就会误判"没变"而不重注入（1.2.0 实测踩过，见 syncTrainName 的注释）。
    */
   private Train lastNamedTrain = null;

   /** 当前列车名（null = 物理结构没命名）。 */
   public String trainName() {
      return this.lastTrainName;
   }

   /**
    * <b>把物理结构的名字同步给虚拟车。</b>
    *
    * <p>名字的<b>唯一来源</b>是 Sable 的 {@code SubLevel.name} —— 也就是航空学（simulated 模组）的
    * <b>铭牌方块</b>写进去的那个。它的 lang 说明写得很清楚：
    * 「所有铭牌将<b>共享并设定</b>该<b>物理结构</b>的名称」，
    * 对应 {@code NameplateBlockEntity.setName(String,boolean,Player)} → {@code SubLevel.setName}。
    *
    * <p>所以这里**不自己存名字、也不自己造输入方式**：物理车已经由铭牌命名了，
    * 虚拟车要做的只是把那个名字抄到 {@code Train.name} 上（Create 的界面读它，
    * 为 null 才显示 {@code create.train.unnamed}「未命名列车」）。
    *
    * <p>名字为空 ⇒ 把 {@code Train.name} 置空，让 Create 自己显示「未命名列车」，
    * 不编造默认名（"不要乱兜底"）。
    *
    * <p>每拍调用一次（在 {@code snapshotPhysical} 里），只有名字真的变了才写，代价是一个 String 比较。
    */
   private void syncTrainName() {
      try {
         if (!(this.level instanceof ServerLevel sl)) {
            return;
         }
         var sub = SubLevelSuspend.containing(sl, this.worldPosition);
         String name = sub == null ? null : sub.getName();
         if (name != null) {
            name = name.trim();
            if (name.isEmpty()) {
               name = null;
            }
         }

         Train train = this.boundTrain == null ? null : (Train)Create.RAILWAYS.trains.get(this.boundTrain);

         // 【1.2.0 修】以前这里只比"名字变了没"，于是**虚拟车重建之后**（新 Train 对象、
         // 名字回到构造默认的「未命名列车」）因为 lastTrainName 仍等于铭牌名而直接 return，
         // 新的 Train 就永远保持未命名 —— 用户实测：铭牌改成 111，TrainStatus 播报仍是
         // 「关于列车的信息：未命名列车」。
         // 所以判据必须是"**这台车对象**我注入过没"，对象身份变了就重注入。
         if (train != null && train == this.lastNamedTrain && java.util.Objects.equals(name, this.lastTrainName)) {
            return;
         }
         this.lastTrainName = name;

         if (train == null) {
            // 还没建车：只记下名字，等下一拍车出现时再注入（对象不同 ⇒ 会走到注入）
            this.lastNamedTrain = null;
            return;
         }

         this.lastNamedTrain = train;
         // 清除名字时**回落到 Create 自己的「未命名列车」**（translatable），
         // 不能写 null：TrainStatus 直接把这个 Component 当参数用，null 会变成 "null"。
         train.name = name == null
            ? net.minecraft.network.chat.Component.translatable("create.train.unnamed")
            : net.minecraft.network.chat.Component.literal(name);

         // 名字也放进 BlockEntity 的同步包，客户端才有东西可显示（Train 本身不同步）。
         this.sendData();

         HexieTrain.LOGGER.info("[name] 虚拟车名字 ← 物理结构铭牌「{}」（车={}）",
            name == null ? "（未命名）" : name, train.id.toString().substring(0, 8));
      } catch (Throwable ignored) {
      }
   }

   /**
    * 占区同步（多车共存的地基）—— 每 {@link #OCCUPANCY_SYNC_TICKS} 拍一次。    *
    * <p>我们的虚拟车从不走 {@code TravellingPoint.travel()}，所以 {@code occupiedSignalBlocks} /
    * {@code reservedSignalBlocks} 不会自己更新；不刷新就等于"车在跑、区间不变"，
    * 别的车看它那段永远是绿的 ⇒ 两辆车开进同一段。服务器上是多人多车，这条必须在。
    *
    * <p>1.2.0 从 {@code serverTick} 搬到这里：那段原来被 {@code !manualMode} 包着，
    * 玩家一接手就不再刷新。
    */
   private void syncOccupancy(Train tr) {
      if (tr == null || tr.carriages.isEmpty()) {
         return;
      }
      if (++this.occupancySyncTicks < OCCUPANCY_SYNC_TICKS) {
         return;
      }
      this.occupancySyncTicks = 0;

      if (tr.derailed || tr.invalid) {
         // 脱轨/失效的虚拟车**不许占区间**。
         // 为什么必须显式清：collectInitiallyOccupiedSignalBlocks() 第一句是
         //     if (trailingPoint.edge == null) return;    // 早退，clear 在它后面
         // 所以脱轨车不但不更新占区，还会把**建车那一刻的陈旧占区永远留着**，
         // 把那段区间永久锁红 ⇒ 别的车（哪怕是我们另一辆车）永远过不去。
         // 2026-11-22 00:08 实测：`占用车=[1094cc00]`（derailed=true, carriages=1）锁死另一辆。
         // 物理上真脱轨的车要么整个在轨道外，要么剩下的车厢由 Simurail 的转向架自己占区间，
         // 不需要这台虚拟车来代表它。
         try {
            if (tr.occupiedSignalBlocks != null) {
               tr.occupiedSignalBlocks.clear();
            }
            if (tr.reservedSignalBlocks != null) {
               tr.reservedSignalBlocks.clear();
            }
         } catch (Throwable ignored) {
         }
         if (this.derailedLogTicks++ % 200 == 0) {
            HexieTrain.LOGGER.warn("[sig] 虚拟车 {} 处于 derailed/invalid ⇒ 清空它的占区表（不许它锁死区间）", tr.id);
         }
      } else {
         tr.collectInitiallyOccupiedSignalBlocks();
      }
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
         // ===== 绑定表垃圾回收：转向架被破坏/拆走 ⇒ 自动解绑 =====
         // 以前表里只进不出，方块没了也一直绑着那个位置，成了僵尸条目。
         // 只删"区块已加载、那里确实已经不是转向架"的条目；区块没加载一律保留 ——
         // 那是"不知道"，不是"不存在"，拿它当依据跑远一点就会把绑定清光。
         if (--this.rosterSweepTicks <= 0) {
            this.rosterSweepTicks = ROSTER_SWEEP_INTERVAL;
            this.sweepRoster();
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

         // ===== 逐转向架制动（阶段 4）=====
         // 转向和制动在 Simurail 里是两张独立的表（setCurrentPath / setRemoteBrakeOverride），
         // 都拿 worldPosition 当 anchor，互不覆盖。
         // 注意：遥控制动条目只活 3 拍，BogeyBraking 内部每拍续期。
         try {
            BogeyRoster rosterNow = this.roster;
            if (!this.manualMode && !rosterNow.positions().isEmpty()
                  && this.settings.get(DispatcherSettings.BOGEY_BRAKE) == 1) {
               this.bogeyBraking.tick((ServerLevel)this.level, this.worldPosition, rosterNow,
                  this.outputMapped(SignalChannel.BRAKE));
            } else {
               this.bogeyBraking.releaseAll((ServerLevel)this.level, this.worldPosition, rosterNow);
            }
         } catch (Throwable t) {
            HexieTrain.LOGGER.warn("[bbrake] 子系统异常 {}", t.toString());
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

             // 1.2.0：这里的 reanchor + 占区同步已整体搬到 snapshotPhysical()/syncOccupancy()。
             // 原来这段被 !manualMode 包着 ⇒ 玩家一接手就不再镜像位置、也不再刷新占区。
             // 位置镜像与"谁在开车"无关，必须每拍都做，且只能有一个地方做。
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

                  // 1.2.0 删：手动模式不再写 train 的速度（见下方第二处的同名说明）。
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
            // 1.2.0 删：这里以前还写 train.manualTick / targetSpeed / approachTargetSpeed(...)，
            // 想借 Create 的 Navigation 开虚拟车。那与"位置只有一个写者"冲突，且 Carriage.travel
            // 已被拦，写了也是惰性死代码。真正驱动车的是上面的红石输出。
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
         // 绑定了几根就必须解析出几根。少一根时 pts 会缩水，RailSearch 一旦收到 1 个点
         // 就会走 pts.size()==1 的短路，①②③三条硬条件一条都不验（铁律 20）。宁可抱闸不猜。
         int resolvedPts = this.bogeyPoints().size();
         if (this.roster.size() >= 2 && resolvedPts != this.roster.size()) {
            // 少一根就【绝对不许】规划（铁律 20：pts 缩水会让三条硬条件全部失效）。
            // 但"某根转向架跨边那一拍解析不出"是一过性的，实测常以 4→3→4 出现。
            // 所以：先只抱闸等下一拍，连续 PLAN_SHRINK_CONFIRM 拍都缺才当成真故障。
            this.planShrinkTicks++;
            if (this.planShrinkTicks < PLAN_SHRINK_CONFIRM) {
               this.holdOutputs();
               return;
            }
            // 限流：确认的那一拍打一次，之后每 40 拍（10 秒）报一次进度，别每拍刷。
            if (this.planShrinkTicks == PLAN_SHRINK_CONFIRM || this.planShrinkTicks % 40 == 0) {
               HexieTrain.LOGGER.warn("[nav] 连续 {} 拍绑定 {} 根、只解析出 {} 根 ⇒ 拒绝规划（不猜）",
                  new Object[]{this.planShrinkTicks, this.roster.size(), resolvedPts});
            }
            this.driveResult = null;
            this.planCooldown = 10;
            this.holdOutputs();
            return;
         }
         this.planShrinkTicks = 0;
         this.driveResult = RailSearch.searchTrainWide(train.graph, train, st, target, 4096.0,
            this.bogeyPoints(), preferred);
         // ===== A2：反向信号机在【规划阶段】剪掉 =====
         // 在线路上判红只能"开到跟前再停"（1.1.11 实测：距离=15.1 m 才锁存，白跑 15 米），
         // 而且反向信号机永远等不到放行。所以这里直接把这条路判掉，一步都不许动。
         if (this.driveResult.ok && this.driveResult.route != null) {
            String bad = this.reverseSignalAhead(this.driveResult.route, pt);
            if (bad != null) {
               this.driveResult = RailSearch.Result.fail("反向信号机（从背面走到信号机上，结构上不许走）" + bad);
            }
         }
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
            // 同一条原因不重复刷屏（反向信号机这种会一直失败，每 2.5 秒一条就够吵）
            if (!this.driveResult.note.equals(this.planFailNote)) {
               this.planFailNote = this.driveResult.note;
               HexieTrain.LOGGER.warn("[drive] 规划失败，抱闸停住：{}", this.driveResult.note);
            }
            this.cacheDistShown = Double.NaN;
            this.phaseIdle();
            this.holdOutputs();
            return;
         }
         this.planFailNote = null;
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
      // ===== 位置跳变：被搬走/被传送 =====
      // 一次决策（5 tick = 0.25 s）最多走 15×0.25 = 3.75 m；但换端时领先端会在两根转向架之间切换，
      // s 会跳几米（1.1.17 日志里 -5.4 / -7.0 两次就是这么来的，不是传送）。所以门槛取 10 m：
      // 真人搬车/传送都是几十米起步，10 m 以下一律当正常走行。
      // 不处理会怎样：车停在红灯前被搬走，锁存对象还是那台信号机，sNow 又还在缓存路线上
      // ⇒ 它就这么"抱着旧路线+旧锁存"站着，永远不重新导航（用户实测）。
      if (Double.isFinite(this.lastRouteS) && Double.isFinite(sNow)
            && Math.abs(sNow - this.lastRouteS) > 10.0) {
         HexieTrain.LOGGER.warn("[pos] 位置跳变 s {} → {}（Δ={}）⇒ 作废路线与信号锁存，重新规划", new Object[]{
            String.format("%.1f", this.lastRouteS), String.format("%.1f", sNow),
            String.format("%.1f", sNow - this.lastRouteS)});
         this.releaseSigHold("位置跳变");
         this.driveResult = null;
         this.driveTarget = null;
         this.driveStopped = false;
         this.arrivedFlag = false;
         this.cacheDistShown = Double.NaN;
         this.lastRouteS = Double.NaN;
         this.calS0 = Double.NaN;
         this.calEdge = null;
         this.planCooldown = 0;
         this.driveTicks = 0;
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
      // 卡住守卫：给着油门却一点不动（被前面脱轨的车别住、被物理挡住…）⇒ 停手，别硬顶。
      // 场景：另一辆虚拟车 derailed=true 时它没有任何边，collectInitiallyOccupiedSignalBlocks() 直接 return，
      // 于是它**占不住任何区间**、信号全绿，我们就会一路油门顶上去（2026-11-22 00:01 实测 throttle=15 v=0）。
      // 卡住期间：真的抱闸停住，不是每 40 拍又顶一次。
      // 硬锁是允许的（用户明确：物理车实际占着轨道，顶也顶不过去），所以这里就老老实实停着，
      // 每 40 拍轻放一次试探，仍然不动就继续停。
      if (this.stallHoldTicks > 0) {
         this.stallHoldTicks--;
         this.throttleCmd = 0;
         this.drivePhase = PH_BRAKE;
         this.brakeCmd = 15;
         this.applyPhase(d, v);
         if (this.stallHoldTicks % 20 == 0) {
            HexieTrain.LOGGER.info("[drive] 驱不动停手中，还剩 {} 拍再试探 d={}", this.stallHoldTicks,
               String.format("%.1f", d));
         }
         return;
      }
      if (this.throttleCmd > 0 && v < 0.05) {
         if (++this.stallTicks > 40) {
            this.stallTicks = 0;
            this.stallHoldTicks = 40;
            HexieTrain.LOGGER.warn("[drive] 驱不动（油门={} 速度<0.05 持续 40 次决策）⇒ 抱闸停手，40 拍后再试探 d={}",
               this.throttleCmd, String.format("%.1f", d));
            this.throttleCmd = 0;
            this.drivePhase = PH_BRAKE;
            this.holdOutputs();
            return;
         }
      } else {
         this.stallTicks = 0;
      }
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

      // ===== 信号：锁定式停车（SignalBoundary 是唯一可知渠道）=====
      // 到站不再看边点：解耦/虚拟车进站那套已删，没有任何消费者需要 Create 的边点回调，
      // 到站改成我们自己判断 —— 弧长距站 d + 物理速度 + 自己的制动代码。
      //
      // 判红判据：从缓存路线里取「前方最近的信号机」，用弧长差看距离 —— 规划那一刻就知道了，多远都看得见。
      // 以前用 travel 探针只往前看 0.6~3 米：红灯要等到车头贴脸才看得见，急刹一下车头已经越过信号机，
      // 越过之后信号落到身后 ⇒ 探针又变绿 ⇒ 加速开走（2026-11-21 实测就是这么冲过去红灯的）。
      // 改缓存弧长之后距离不再是问题，但还剩一个：每拍重新找「最近的」信号会换台，
      // 于是这一拍判红、下一拍判绿（日志里 [sig] 与 [drive] 隔拍交替，车是闪着刹车蹭过红灯的）。
      // ⇒ 判红一次就【锁存】住那一台，直到它真的放行，或者我们确实越过了它。
      // 制动减速度取 1.0 m/s² 是保守值（宁可早停），不是从 Create 读的。
      if (this.sigHold != null && !this.sigHoldStillRelevant(route, sNow)) {
         boolean passed = route != null && Double.isFinite(sNow)
            && route.legOf(this.sigHold.edge) != null && sNow > this.sigHold.s + 0.6;
         this.releaseSigHold(passed ? "已越过该信号机 —— 制动距离不足，冲过红灯了" : "已换段/该信号机不在本段上");
      }
      if (this.sigHold != null) {
         this.sigHoldTicks++;
         // 锁存超时兜死锁：红灯必须停，但"永远等不到"会把整条线堵死（1.1.17 实测 900+ 拍）。
         // 60 秒还红就强行松开并大声报错 —— 这是"兜底"里唯一允许的一种：**兜的是死锁，不是闯红灯**，
         // 因为松闸是有记录的、有原因的、需要人来看的。
         if (this.sigHoldTicks >= SIG_HOLD_TIMEOUT) {
            // 60 秒还红，就把"到底是谁占着这段"整个打出来。光有
            // "强制红=false 状态=RED" 是判不出"别人占"还是"自己占"的 —— 而这两件事
            // 的处置完全相反（该等 / 不该等），所以这里必须看到 SignalEdgeGroup.trains
            // 和整张图的车辆普查。见 TrainCensus 的类注释。
            HexieTrain.LOGGER.warn("[sig] 锁存超时 {} 拍（60 秒）仍判红 ⇒ 强制松开继续走；请检查：{}",
               this.sigHoldTicks, SignalProbe.lastBlockDetail);
            try {
               Train self = this.boundTrain == null ? null : (Train)Create.RAILWAYS.trains.get(this.boundTrain);
               UUID groupId = this.sigHoldSignal == null || this.sigHoldEnter == null
                  ? null : this.sigHoldSignal.getGroup(this.sigHoldEnter);
               HexieTrain.LOGGER.warn("[sig][是谁] {}", TrainCensus.group(groupId, self));
               HexieTrain.LOGGER.warn("[sig][全部车] {}", TrainCensus.trains(self));            } catch (Throwable t) {
               HexieTrain.LOGGER.warn("[sig][是谁] 普查失败 {}", t.toString());
            }
            this.releaseSigHold("超时强制松开（死锁兜底）");
         }
      }
      if (this.sigHold != null) {
         // 放行必须【连续】SIG_CLEAR_DEBOUNCE 次决策都判 CLEAR。
         // 只松闸一拍会出什么？1.1.11 实测：15.3 m 处锁存 → 松闸 → 11.4 m 又锁 → 松闸 → 8.1 m 再锁，
         // 每次松闸车就往前蹭 1~3 米，蹭几次就整个过红灯了。红石/信号块的上电状态本来就可能抖一拍。
         if (this.probeHeldSignal(train) == SignalProbe.State.CLEAR) {
            this.sigClearTicks++;
            if (this.sigClearTicks >= SIG_CLEAR_DEBOUNCE) {
               this.releaseSigHold("连续 " + SIG_CLEAR_DEBOUNCE + " 次决策 CLEAR");
            }
         } else {
            this.sigClearTicks = 0;
         }
      }
      if (this.sigHold != null) {
         // ===== 停车点 = 信号机前方 0.5 m，不是"一锁存就全力抱闸" =====
         // 锁存触发点故意留了 +6 m 余量（`dSig <= brakeDist + 6`，吃决策节拍与执行延迟），
         // 但那个余量只能用来**开始减速**，不能变成停车位置。
         // 以前这里无条件 `brakeCmd = 15`，实测在离信号机 6.2 m 处就停死了
         // （[sig] 前方信号 距离=6.2 刹车需要=0.5 ⇒ 锁存，然后 brake=15 直接刹停）。
         // 现在改成和到站同一套思路：只有超过"能停在 dSig-0.5 处"的允许速度才刹，
         // 否则滑行。于是它贴着曲线靠近，最后停在信号机前约半米。
         double dSigNow = Double.isFinite(sNow) ? Math.max(0.0, this.sigHold.s - sNow) : 0.0;
         // 用**保守**减速度 0.5（实测 0.53~0.67 的下界）：宁可早一点开始刹、最后停在 1 m 处，
         // 也不能因为按 0.6 算而刹不住、冲过信号机。这和上面算 brakeDist 用的是同一个数。
         double vAllowSig = Math.sqrt(2.0 * 0.5 * Math.max(0.0, dSigNow - 0.5));
         this.throttleCmd = 0;
         if (v <= 0.05 || dSigNow <= 0.5 || v > vAllowSig) {
            this.drivePhase = PH_BRAKE;
            this.brakeCmd = 15;
         } else {
            // 还在允许速度之内：滑行，让它自己按曲线往下走
            this.drivePhase = PH_COAST;
            this.brakeCmd = 0;
         }
         this.applyPhase(d, v);
         // 真正停下来的那一拍报一次实际停车距离，方便核对"离信号机多远就停住了"
         if (v <= 0.05 && !this.sigStopLogged) {
            this.sigStopLogged = true;
            HexieTrain.LOGGER.info("[sig] 已在信号机前 {} m 停稳（目标是 0.5 m）{}",
               String.format("%.2f", dSigNow), this.sigHoldSignal == null ? "" : " 区间=" + SignalProbe.shortId(this.sigHoldSignal.getId()));
         }
         // 红灯等待不是「停滞」：不推进每段决策预算，否则等久了会被看门狗判死锁死。
         this.driveTicks--;
         if (this.sigHoldTicks % 20 == 1 || this.sigHoldTicks % 200 == 0) {
            HexieTrain.LOGGER.info("[sig] 红灯停车等待中 已等{}拍 d={} v={} {} {}", new Object[]{
               this.sigHoldTicks, String.format("%.1f", d), String.format("%.2f", v),
               SignalProbe.lastBlockDetail,
               this.sigClearTicks > 0 ? ("【已转绿 " + this.sigClearTicks + "/" + SIG_CLEAR_DEBOUNCE + "】") : ""});
         }
         return;
      }

      // 没锁存才去找前方信号机。
      //
      // 判定窗口 = 制动距离 + 余量，减速度必须用【实测值】：1.1.10 日志里
      // v 6.17→6.04 走 1.50 m、5.87→5.72 走 1.50 m … 逐对算出来 a≈0.53~0.67 m/s²。
      // 以前这里按 1.0 写，窗口只有实际需要的 55%：15 m/s 时本该 225 m 就开始刹，
      // 却在 112 m 才刹 —— 根本停不下来 ⇒ 看见红灯也照样开过去。
      // 取 0.5 是保守值（宁可早刹），余量 6 m 吃掉决策节拍与执行延迟。
      final double signalBrakeDecel = 0.5;   // m/s²，实测 0.53~0.67 的保守值
      double brakeDist = v * v / (2.0 * signalBrakeDecel);
      // 只看"最近那一台"是不够的：如果最近那台是绿的，它后面那台红灯就被整台无视，
      // 等绿灯过去、红灯成为"最近的"时往往已经在制动距离以内 —— 2026-11-21 就是这么闯的安山红灯。
      // 所以按弧长顺序往前看若干台，第一个判红的立刻锁存。
      java.util.List<RailCache.Hit> aheadSigs = new java.util.ArrayList<>(4);
      for (RailCache.Hit h : route.points) {
         if (!h.station && h.point instanceof SignalBoundary && h.s >= sNow) {
            aheadSigs.add(h);
         }
      }
      aheadSigs.sort((x, y) -> Double.compare(x.s, y.s));
      // 信号扫描遥测：每 20 次决策（5 秒）把"我们看到的最近一台信号机"打出来。
      // 有这行才能回答"到底看没看信号机"，不用再靠猜。
      if (this.driveTicks % 20 == 0 && !aheadSigs.isEmpty()) {
         RailCache.Hit nearest = aheadSigs.get(0);
         HexieTrain.LOGGER.info("[sigscan] 最近信号 距离={} {} 判定={}", new Object[]{
            String.format("%.1f", nearest.s - sNow), SignalProbe.lastObserveDetail,
            this.probeHit(nearest, route, train)});
      }
      int sigChecked = 0;
      double vSigCap = Double.MAX_VALUE;
      String sigCapNote = null;
      RailCache.Hit sigCapHit = null;
      for (RailCache.Hit sigHit : aheadSigs) {
         if (++sigChecked > SIGNAL_LOOKAHEAD) {
            break;
         }
         double dSig = sigHit.s - sNow;
         // 关键：**多远都要看**。以前这里 `if (dSig > brakeDist + 6) break;`，
         // v=0 时 brakeDist=0 ⇒ 只在 6 米内才看一眼 ⇒ 车停着等于瞎的，
         // 给满油门往前推、到跟前才发现红灯（用户实测：throttle=15、v=0、[sig] 一条没有）。
         // 现在：先判它红不红；红就按距离分两种处理 ——
         //   已经进制动距离 ⇒ 立刻锁存抱闸
         //   还很远         ⇒ 把它变成限速上限（保证永远停得住），继续开
         if (this.probeHit(sigHit, route, train) == SignalProbe.State.BLOCKED) {
            if (dSig > brakeDist + 6.0) {
               vSigCap = Math.min(vSigCap, Math.sqrt(2.0 * 0.5 * Math.max(0.0, dSig - 1.0)));
               sigCapNote = String.format("%.1f", dSig);
               sigCapHit = sigHit;
               continue;
            }
            this.holdSignal(sigHit, route);
            this.drivePhase = PH_BRAKE;
            this.throttleCmd = 0;
            this.brakeCmd = 15;
            this.applyPhase(d, v);
            this.driveTicks--;
            double need = brakeDist;
            String tag = dSig < need - 0.5 ? "【刹不住：还差 " + String.format("%.0f", need - dSig) + " m】" : "";
            HexieTrain.LOGGER.info("[sig] 前方信号 距离={} 刹车需要={}（前方第 {} 台）⇒ 锁存，停车等放行 {}{}", new Object[]{
               String.format("%.1f", dSig), String.format("%.1f", need), sigChecked,
               SignalProbe.lastBlockDetail, tag});
            return;
         }
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
      // 目标速度曲线：vT = sqrt(2*a*(d-0.5))。
      // 原来的 max(0.4, …) 下限在 d→0 时仍然要 0.4 m/s ⇒ 速度环里"停住"这件事根本不存在，
      // 每次刹到 0 都会被判成"低于目标速度"又被给油，这也是往复的一份。
      // 进到到站容差里就直接把目标速度打成 0。
      double vTraw = Math.sqrt(2.0 * A_BRAKE * Math.max(0.0, d - 0.5));
      double vT = d <= ARRIVE_TOL ? 0.0 : Math.max(0.4, Math.min(V_CRUISE_MPS, vTraw));
      // 前方有红信号、但还没进制动距离 ⇒ 用它压住目标速度，保证"永远停得住"。
      // 这一条是"看信号机"真正生效的地方：不是到跟前才刹，而是从一开始就不许快到刹不住。
      if (Double.isFinite(vSigCap) && vSigCap < vT) {
         vT = Math.max(0.4, vSigCap);
         if (this.sigCapLogTicks++ % 40 == 0) {
            HexieTrain.LOGGER.info("[sig] 前方红信号 {} m ⇒ 限速 {}/s（{})", new Object[]{
               sigCapNote, String.format("%.2f", vSigCap), SignalProbe.lastBlockDetail});
            // 压速是因为"前方红"，那就得说清这个红是谁的 —— 和锁存那条用同一个限流，
            // 所以同一台信号机最多多一行，正常跑不会刷屏。
            this.logCensusFor(sigCapHit, route);
         }
      } else {
         this.sigCapLogTicks = 0;
      }
      double dv = vT - v;
      // ===== 滑车死区（|dv| ≤ DV_BAND）=====
      // DV_BAND 这个常量早就声明了，但全文件从来没被引过 —— 滑车就是丢在这。
      // 原来的判据是：dv > 0 就"行驶中"给油，只有 -BRK_BAND < dv ≤ 0 才算滑车，
      // 死区只在负侧、宽 0.15。站前 dv 正负各半地翻，就变成"行驶中 ↔ 急刹"往复，
      // 物理车上是一震一震，日志里则是 v 反复被推到 0 又被给油。
      // 现在恢复成对称死区：|dv| ≤ DV_BAND 一律滑车，不给油也不刹。

      // 制动底线：老代码的距离路，防速度环跟不上
      double stopZone = Math.max(3.0, v * 1.2);
      int brakeFloor = d >= stopZone ? 0 : (int) Math.round(15.0 * (1.0 - Math.max(0.0, d) / stopZone));
      if (d < 1.0) brakeFloor = 15;

      int wantThrottle;
      if (dv > DV_BAND) {
         // 低于目标速度、且超出死区才推。油门量也从"超出死区的那部分"算，
         // 免得刚过死区就给满油门把自己又推过头。
         this.drivePhase = PH_RUN;
         this.brakeCmd = 0;
         wantThrottle = (int) Math.round(15.0 * Math.min(1.0, (dv - DV_BAND) / ACC_BAND));
      } else if (dv > -DV_BAND) {
         // 滑车：不输出传动也不输出刹车，让车自己滚
         this.drivePhase = PH_COAST;
         wantThrottle = 0;
         this.brakeCmd = 0;
      } else {
         this.drivePhase = PH_BRAKE;
         wantThrottle = 0;
         this.brakeCmd = Math.max((int) Math.round(Math.min(15.0, (-dv - DV_BAND) / DV_FULL * 15.0)), brakeFloor);
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

   /**
    * 判一台信号机。入口节点 = 这条 leg 的行进进入端（legs 是按行进方向排的）。
    *
    * <p>语义（1.1.8 定下的，别改回去）：只有 BLOCKED 才算红灯。
    * UNKNOWN 的含义是「我们这个进入侧没有信号机」= 反向信号机，结构上不该走 ——
    * 但那是【规划阶段】该剪掉的（§14.4 的 A2），在线路上把它当红灯会直接锁死：
    * 1.1.7 就是这么在 292 次 UNKNOWN 之后被看门狗判死的。
    *
    * <p>判不了（observe 返回 null）⇒ 按红灯停：宁停不闯。
    */
   private SignalProbe.State probeHit(RailCache.Hit h, RailCache.Route route, Train train) {
      try {
         if (h == null || !(h.point instanceof SignalBoundary sb) || route == null) {
            return SignalProbe.State.CLEAR;
         }
         TrackNode enter = this.enterNodeOf(h, route);
         SignalProbe.Obs obs = SignalProbe.observe(sb, enter, train, route);
         if (obs == null) {
            HexieTrain.LOGGER.info("[sig] 判不了（enter=null）⇒ 按红灯 d={}", String.format("%.1f", h.s));
            return SignalProbe.State.BLOCKED;
         }
         return obs.state();
      } catch (Throwable t) {
         return SignalProbe.State.BLOCKED;
      }
   }

   /** 锁存中的那台信号机还开着吗。判不了就继续停着（不是"就当绿灯"）。 */
   private SignalProbe.State probeHeldSignal(Train train) {
      try {
         if (this.sigHoldSignal == null) {
            return SignalProbe.State.CLEAR;
         }
         SignalProbe.Obs obs = SignalProbe.observe(this.sigHoldSignal, this.sigHoldEnter, train, this.driveResult == null ? null : this.driveResult.route);
         return obs == null ? SignalProbe.State.BLOCKED : obs.state();
      } catch (Throwable t) {
         return SignalProbe.State.BLOCKED;
      }
   }

   /**
    * A2：这条路上有没有「从这一侧进不去」的信号机（反向信号机）。
    *
    * <p>判据用 Create 自己的 {@code SignalBoundary.canNavigateVia(node)}：
    * <pre>
    *   canNavigateVia(node) = !blockEntities.get(isPrimary(node)).isEmpty()
    * </pre>
    * 也就是"这一侧到底有没有信号方块"。没有 ⇒ 车是从背面走到信号机上 ⇒ 结构上不许走。
    * 这跟 {@code SignalProbe.observe} 里那条「反向信号」分支是同一个判据，两边不会打架。
    *
    * <p>为什么必须在规划阶段剪：在线上判红只能"开到跟前再停"（1.1.11 实测 距离=15.1 m 才锁存，
    * 白跑 15 米），而且反向信号机<b>永远不会放行</b>，等到天荒地老。
    *
    * @param head 车头点；只检查它<b>前面</b>的信号机（已经越过的不算，否则会把自己锁死）
    * @return null = 这条路可行；非 null = 不可行原因（s + 区间）
    */
   private String reverseSignalAhead(RailCache.Route route, TravellingPoint head) {
      if (route == null) {
         return null;
      }
      double limit = Double.isFinite(route.stationS) ? route.stationS : route.total;
      double from = 0.0;
      if (head != null && head.edge != null) {
         double sHead = route.sOf(head.edge, head.position);
         if (Double.isFinite(sHead)) {
            from = sHead + 0.05;
         }
      }
      for (RailCache.Hit h : route.points) {
         if (h.station || !(h.point instanceof SignalBoundary sb)) {
            continue;
         }
         if (h.s < from || h.s > limit) {
            continue;
         }
         TrackNode node = this.enterNodeOf(h, route);
         if (node == null) {
            continue;
         }
         boolean navigable;
         try {
            navigable = sb.canNavigateVia(node);
         } catch (Throwable t) {
            continue;
         }
         if (!navigable) {
            return " s=" + String.format("%.1f", h.s) + " 区间=" + SignalProbe.shortId(sb.getId());
         }
      }
      return null;
   }

   /**
    * 信号机要的那个节点 = <b>车头正驶向的那个节点</b>（边的远端），不是出发端。
    *
    * <p>为什么是远端：Create 自己的 {@code Train.frontSignalListener} 用的是
    * {@code pair.getSecond().getSecond()}，而 {@code TravellingPoint} 造这个 couple 时是
    * {@code Couple.create(node1, node2)} 再按行进方向 {@code swap()} —— 所以
    * {@code getSecond()} 就是"前方那个节点"。Simurail 的 {@code PhysicsBogeyAxle:260} 同样用
    * {@code nodes.getSecond()}。
    *
    * <p>曾经这里传的是出发端（1.1.7 换缓存弧长时写反了），于是
    * {@code SignalBoundary.isPrimary(node) = (node == edgeLocation.getSecond())} 判的是<b>另一侧</b>：
    * 读的是对面那张 {@code blockEntities} 表 ⇒ 单面信号机变成"反向信号"永久 BLOCKED，
    * 双面信号机则可能读到对面的红石上电状态 ⇒ 假红灯。现象就是车停在信号机前再也不动。
    */
   private TrackNode enterNodeOf(RailCache.Hit h, RailCache.Route route) {
      RailCache.Leg leg = h == null || route == null ? null : route.legOf(h.edge);
      return leg == null ? null : (leg.reversed ? leg.edge.node1 : leg.edge.node2);
   }

   /**
    * 建车之后打一次全量车辆普查（只读）。
    *
    * <p>要回答的问题是：**这张轨道图上现在到底有几辆车？** 多出来的那辆是干什么的？
    * 孤儿虚拟车（老版本 {@code UUID.randomUUID()} 建出来、没进 {@code OUR_TRAINS} 的那种）
    * 会一直停在原地、一直把它的区间顶成 RED，而它自己不会写任何日志 ——
    * 没有这一行就永远看不见它。
    */
   private void logTrainCensus() {
      Train self = this.boundTrain == null ? null : (Train)Create.RAILWAYS.trains.get(this.boundTrain);
      HexieTrain.LOGGER.info("[sig][全部车] {}", TrainCensus.trains(self));
   }

   private void holdSignal(RailCache.Hit h, RailCache.Route route) {
      this.sigHold = h;
      this.sigHoldSignal = h != null && h.point instanceof SignalBoundary sb ? sb : null;
      this.sigHoldEnter = this.enterNodeOf(h, route);
      this.sigHoldTicks = 0;
      this.sigClearTicks = 0;
      this.sigStopLogged = false;
      this.logCensusFor(h, route);
   }

   /**
    * 把"这台信号机守的那个区间里到底有谁"打一次。
    *
    * <p>为什么必须打：Create 的 RED 分不清"别人占着"和"我自己占着"，
    * 而这两件事的处置完全相反（该等 / 不该等）。明细里只有
    * {@code 强制红=false 状态=RED} 是分不出来的，必须把
    * {@code SignalEdgeGroup.trains} 原样打出来（见 {@link TrainCensus}）。
    *
    * <p>限流：同一个区间只打一次，换区间才再打，所以每台新信号机最多多一行日志。
    */
   private void logCensusFor(RailCache.Hit h, RailCache.Route route) {
      try {
         if (h == null || !(h.point instanceof SignalBoundary sb)) {
            return;
         }
         TrackNode enter = this.enterNodeOf(h, route);
         if (enter == null) {
            return;
         }
         UUID groupId = sb.getGroup(enter);
         if (groupId == null || groupId.equals(this.censusLoggedGroup)) {
            return;
         }
         this.censusLoggedGroup = groupId;
         Train self = this.boundTrain == null ? null : (Train)Create.RAILWAYS.trains.get(this.boundTrain);
         HexieTrain.LOGGER.info("[sig][是谁] {}", TrainCensus.group(groupId, self));
         if (TrainCensus.onlyOurselves(groupId, self)) {
            // 这一行是判定 (A)/(B) 的直接证据：
            // 区间里只有本车 ⇒ Create 的 RED 是"被自己占"，不是"前面有车"。
            // 仍然照旧停车（红灯必须停，不在这里放行），只是把原因说清楚。
            HexieTrain.LOGGER.warn("[sig][自占] 区间 {} 里只有本车：这个 RED 是我们自己占出来的，不是前面有车",
               SignalProbe.shortId(groupId));
         }
      } catch (Throwable t) {
         HexieTrain.LOGGER.warn("[sig][是谁] 普查失败 {}", t.toString());
      }
   }

   /**
    * 锁存还有效吗：车头还没越过它，而且它还在当前这段路线上。
    * 越过了就必须作废 —— 否则锁存会变成永久死锁（1.1.7 的教训）。
    */
   private boolean sigHoldStillRelevant(RailCache.Route route, double sNow) {
      if (this.sigHold == null) {
         return false;
      }
      if (route == null || !Double.isFinite(sNow)) {
         return false;
      }
      if (route.legOf(this.sigHold.edge) == null) {
         return false;
      }
      return sNow <= this.sigHold.s + 0.6;
   }

   private void releaseSigHold(String why) {
      if (this.sigHold != null) {
         HexieTrain.LOGGER.info("[sig] 解除锁存（{}），等了 {} 拍；观察={}", new Object[]{
            why, this.sigHoldTicks, SignalProbe.lastObserveDetail});
      }
      this.sigHold = null;
      this.sigHoldSignal = null;
      this.sigHoldEnter = null;
      this.sigHoldTicks = 0;
      this.sigClearTicks = 0;
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

         // ===== 红石有输入 ⇒ 进入玩家操控模式 =====
         // 这段原来只写在 tickAutomated() 里。而入口是：
         //     autoAllowed = !manualMode && !isUnbound() && !scheduleSlot.isEmpty();
         //     if (autoAllowed && boundTrain != null) tickAutomated(); else tickManualGearbox();
         // ⇒ 没有时刻表时永远走本方法，manualMode 从来不会置位，
         //    isUnbound() 于是恒为 true，界面一直显示"未绑定"，红石也抢不到控制权。
         // 手动/无时刻表这条路上必须同样能进手动。
         if (mag > 0 && !this.manualMode) {
            this.manualMode = true;
            this.setChanged();
            this.sendData();
            HexieTrain.LOGGER.info("[manual] 侧面红石输入 {} ⇒ 进入玩家操控模式 target={} entry={}",
               mag, this.myEntry, this.scheduleSlot.isEmpty(), this.boundTrain != null);
         }

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
            }

            // 不管 map 里还有没有它：purge 会把 map / movingTrains / waitingTrains 三处一起摘干净。
            // 以前这里 `if (train != null)` 才 removeTrain，map 已经没了就直接跳过 ——
            // 于是 list 里的残影永远留着，继续占信号区间堵别人的车。
            TrainBinder.unregister(this.boundTrain);
         }

         if (this.level != null) {
         }

         this.boundTrain = null;
         this.lastDestination = null;
         // 锁存也跟着解绑一起作废
         this.releaseSigHold("注销虚拟列车");
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
            this.notePresenceResult(result.ok(), result.message());
            if (result.ok()) {
               this.bindTrain(result.trainId());
               // 建车成功立刻把物理结构的名字同步过来（新建的 Train 对象 name 是 null，
               // 不注入就又会显示「未命名列车」）。
               try {
                  this.syncTrainName();
               } catch (Throwable ignored) {
               }
               // 建车成功立刻做一次全量车辆普查：这是"这张图上到底有几辆车"的唯一实测依据。
               // 老版本留下的孤儿虚拟车（randomUUID 建、OUR_TRAINS 是内存态重启就丢、
               // sweepGhostTrains 只清 OUR_TRAINS 里的 id）会永久占着区间、把每个信号都顶成 RED，
               // 而它不会出现在任何别的日志里 —— 所以这一行必须有。
               try {
                  this.logTrainCensus();
               } catch (Throwable ignored) {
               }
               if (SubLevelSuspend.resume(serverLevel, this.worldPosition)) {
                  TrainBinder.PERSISTENT_LOADS.add(result.trainId());
               }
            }
         } catch (Throwable var3) {
            // 以前这里只打坐标不打异常，等于"知道炸了但不知道为什么"，白等一整天。
            HexieTrain.LOGGER.error("[HexieTrain][presence] 建车异常 @" + this.worldPosition, var3);
            this.notePresenceResult(false, "异常：" + var3.getClass().getSimpleName() + ": " + var3.getMessage());
         }
      }
   }

   /**
    * 建车成功/失败的记账：失败要退避，日志要限流。
    *
    * <p>以前失败之后每 20 tick（1 秒）重试一次、每次都打两行 —— 2026-11-21 那个存档里
    * 同一条「物理结构不在轨道上（或跨图）」刷了 176 遍，把真问题埋了。
    * 现在：失败后等 2 秒再试，并且只在<b>原因变了</b>的时候打日志（外加每 30 次报一次进度）。
    */
   private void notePresenceResult(boolean ok, String reason) {
      if (ok) {
         if (this.presenceFails > 0) {
            HexieTrain.LOGGER.info("[HexieTrain][presence] 建车恢复了（之前连续失败 {} 次，最后一次原因={}）", this.presenceFails, this.presenceLastReason);
         } else {
            HexieTrain.LOGGER.info("[HexieTrain][presence] 建车 ok msg={}", reason);
         }
         this.presenceFails = 0;
         this.presenceLastReason = null;
         this.presenceRetry = 0;
         return;
      }

      this.presenceFails++;
      boolean changed = !java.util.Objects.equals(reason, this.presenceLastReason);
      this.presenceLastReason = reason;
      this.presenceRetry = PRESENCE_RETRY_TICKS;
      if (changed || this.presenceFails % 30 == 1) {
         HexieTrain.LOGGER.info("[HexieTrain][presence] 建车 ok=false msg={}（连续第 {} 次，{} 拍后重试）", new Object[]{
            reason, this.presenceFails, PRESENCE_RETRY_TICKS});
      }
   }

   /**
    * 物理结构还在轨道上吗：随便找一根绑定转向架，看它的轴有没有边。
    *
    * <p>为什么要问这个：Create 的 {@code Train} <b>只能存在于轨道图的边上</b>
    * （每节车厢就是一个 {@code TravellingPoint(edge, position)}），世界上不存在"轨道外的列车坐标"。
    * 所以车整个掉到轨道外时，虚拟车既没法"跟着 TP 到场外"，Create 也不一定会销毁它
    * （Create 只自动销毁 {@code invalid=true} 的；{@code derailed=true} 的它会一直留着）。
    * 唯一的正确做法就是：**我们自己把虚拟车注销掉**，别让它继续代表一辆不在轨道上的车。
    * 等物理结构回到轨道上，{@code syncTrainPresence} 里的 bind 会重新建车。
    */
   private boolean physicalOnTrack() {
      try {
         for (PhysicsBogeyBlockEntity b : this.rosterBogeys()) {
            try {
               TravellingPoint p = b.getAxle(true).getTrackPoint();
               if (p != null && p.edge != null) {
                  return true;
               }
            } catch (Throwable ignored) {
            }
         }
      } catch (Throwable ignored) {
      }
      return false;
   }

   /**
    * 车"不在轨道上"时把每一根绑定转向架的**轨型账**打出来。
    *
    * <p>为什么盯轨型：{@code PhysicsBogeyAxle} 判断"这根轴在不在轨道上"走的是
    * {@code TrackSegmentHelper.findBlockTrackSegment}，里面的筛子是
    * <pre>
    *   TrackType type = TrackTypeOverrides.getTrackType(block.getMaterial());
    *   if (!validTypes.contains(type) &amp;&amp; !TrackTypeOverrides.isUniversal(type)) continue;
    * </pre>
    * 而 {@code validTypes} 来自转向架自己的 {@code BogeySubtype.trackTypes()}。
    * 和谐把 {@code hexie:standard} 注册成**独立轨型**（{@code HeXieSimurailCompat.registerTrackType()}，
    * 参数 width=1.509375 / height=0.75），并把该轨型的默认转向架设成 CW2
    * （{@code BogeyType.setDefault(HEXIE_STANDARD, false, CW2.type())}）。
    * 所以绑在车上的转向架只要没接受这个轨型，它在这条轨上就永远找不到轨道，
    * 我们这边的表现就是"物理结构不在轨道上"。
    *
    * <p><b>外轨超高不在这条判据上</b>：超高只存在于和谐自己的
    * {@code TrackRailSurface} / {@code TrackGeometry.Sample.cantRadians} /
    * {@code VehiclePose.rollRadians}（车体姿态）里，不写进 Create 的轨道图；
    * 而 Create 的 {@code ITrackBlock.getUpNormal / getElevationAtCenter} 和谐也**没有覆盖**。
    */
   private void logTrackTypeDiagnosis() {
      if (!(this.level instanceof ServerLevel sl)) {
         return;
      }
      try {
         for (BlockPos pos : this.roster.positions()) {
            if (!sl.hasChunkAt(pos)) {
               HexieTrain.LOGGER.info("[presence][轨型] {} 区块未加载，跳过", pos.toShortString());
               continue;
            }
            if (!(sl.getBlockEntity(pos) instanceof PhysicsBogeyBlockEntity b)) {
               HexieTrain.LOGGER.info("[presence][轨型] {} 不是物理转向架（可能已被拆）", pos.toShortString());
               continue;
            }

            String edges;
            try {
               TravellingPoint f = b.getAxle(true).getTrackPoint();
               TravellingPoint r = b.getAxle(false).getTrackPoint();
               edges = (f == null ? "null" : (f.edge == null ? "无轨" : "有轨"))
                  + "/" + (r == null ? "null" : (r.edge == null ? "无轨" : "有轨"));
            } catch (Throwable t) {
               edges = "异常:" + t.getClass().getSimpleName();
            }

            String accepted;
            String typeName;
            try {
               accepted = trackTypeNames(b.getOptions().type.trackTypes());
               typeName = String.valueOf(b.getOptions().type);
            } catch (Throwable t) {
               accepted = "异常:" + t.getClass().getSimpleName();
               typeName = "?";
            }

            TrackMaterial material = this.materialUnder(sl, pos);
            String here = material == null ? "脚下没找到轨道方块" : describeMaterial(material);

            HexieTrain.LOGGER.info(
               "[presence][轨型] {} 轴={} 转向架={} 接受=[{}] 脚下={}",
               pos.toShortString(), edges, typeName, accepted, here
            );
         }
      } catch (Throwable t) {
         HexieTrain.LOGGER.warn("[presence][轨型] 诊断异常 {}", t.toString());
      }
   }

   /** 在转向架位置附近找脚下的轨道方块（转向架在轨面之上，斜弯上不一定正好下方）。 */
   private TrackMaterial materialUnder(ServerLevel level, BlockPos pos) {
      BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
      for (int dy = 0; dy >= -2; dy--) {
         for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
               p.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
               try {
                  if (!level.isLoaded(p)) {
                     continue;
                  }
                  if (level.getBlockState(p).getBlock() instanceof ITrackBlock tb) {
                     return tb.getMaterial();
                  }
               } catch (Throwable ignored) {
               }
            }
         }
      }
      return null;
   }

   private static String describeMaterial(TrackMaterial material) {
      try {
         TrackMaterial.TrackType type = TrackTypeOverrides.getTrackType(material);
         String universal = type != null && TrackTypeOverrides.isUniversal(type) ? "(通用)" : "";
         return material.id + " 轨型=" + (type == null ? "null" : type.id) + universal;
      } catch (Throwable t) {
         return material.id + " 轨型读取异常:" + t.getClass().getSimpleName();
      }
   }

   private static String trackTypeNames(Set<TrackMaterial.TrackType> set) {
      if (set == null || set.isEmpty()) {
         return "空";
      }
      StringBuilder sb = new StringBuilder();
      for (TrackMaterial.TrackType t : set) {
         if (sb.length() > 0) {
            sb.append(", ");
         }
         sb.append(t == null ? "null" : String.valueOf(t.id));
      }
      return sb.toString();
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
         } else if (this.boundTrain != null && !this.physicalOnTrack()) {
            // 车整个在轨道外：虚拟车代表不了它（Train 只能活在轨道边上），
            // 而且 derailed=true 的虚拟车 Create 不会自动销毁 ⇒ 它会一直占着一段区间。
            // 连续两次（约 2 秒）确认才动手，避免跨边那一拍的抖动把车误注销。
            if (this.offTrackTicks == 0) {
               // 第一次丢轨就把"轨型账"打出来：丢轨的绝大多数原因是转向架接受的轨型
               // 里没有脚下这条轨的轨型（和谐是独立轨型 hexie:standard），不是几何。
               this.logTrackTypeDiagnosis();
            }
            if (++this.offTrackTicks >= 2) {
               HexieTrain.LOGGER.warn("[presence] 物理结构不在轨道上（连续 {} 次）⇒ 注销虚拟列车 {}", this.offTrackTicks, this.boundTrain);
               this.offTrackTicks = 0;
               this.dissolve();
               this.resetRuntimeState();
               this.presenceRetry = PRESENCE_RETRY_TICKS;   // bind 也会失败，别立刻重建
            }
         } else if (this.boundTrain == null) {
            this.offTrackTicks = 0;
            if (this.presenceRetry > 0) {
               this.presenceRetry--;
               return;
            }
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
            this.bogeyBraking.releaseAll(sl, this.worldPosition, this.roster);
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
      // 信号锁存属于"这一段路"的状态：换段/解绑一律作废，绝不留到下一段去堵自己。
      this.sigHold = null;
      this.sigHoldSignal = null;
      this.sigHoldEnter = null;
      this.sigHoldTicks = 0;

      try {
         this.setOutput(SignalChannel.BRAKE, 0);
      } catch (Throwable var2) {
      }
   }

   public int output(SignalChannel channel) {
      return this.outputs[channel.index()];
   }

   /** 这个通道的开关状态。 */
   public boolean wirelessEnabled(SignalChannel channel) {
      return channel != null && this.wirelessEnabled[channel.index()];
   }

   /**
    * <b>真正发到红石网络上的强度</b>：开关关掉就是 0。
    *
    * <p>{@link WirelessChannel#getTransmittedStrength()} 调这里。
    * 不动 {@link #output} 是为了让 UI 还能显示需求值。
    */
   public int transmittedStrength(SignalChannel channel) {
      return this.wirelessEnabled(channel) ? this.outputMapped(channel) : 0;
   }

   /**
    * <b>实际输出值</b>：把需求 {@code 0..15} 线性映射到该通道设置的 {@code [下限, 上限]}。
    *
    * <p>为什么不直接改 {@link #output}：{@code output()} 是<b>需求值</b>，还被状态机读着
    * （比如 "brake 通道 > 0 说明正在制动"）。如果让 output() 返回映射后的值，
    * 当下限被设成非 0 时它就会永远 > 0，把状态机带偏。所以这里只改"出去的那个数"。
    *
    * <p>上下限反了（min &gt; max）就自己换过来，宁可按区间算也不输出越界值。
    * 默认 0..15 时直接原样返回，等于没有这一步。
    */
   public int outputMapped(SignalChannel channel) {
      if (channel == null) {
         return 0;
      }
      int v = this.outputs[channel.index()];
      int lo;
      int hi;
      if (channel == SignalChannel.BRAKE) {
         lo = this.settings.get(DispatcherSettings.BRAKE_MIN);
         hi = this.settings.get(DispatcherSettings.BRAKE_MAX);
      } else {
         lo = this.settings.get(DispatcherSettings.THROTTLE_MIN);
         hi = this.settings.get(DispatcherSettings.THROTTLE_MAX);
      }
      if (lo > hi) {
         int tmp = lo;
         lo = hi;
         hi = tmp;
      }
      if (lo <= 0 && hi >= 15) {
         return v;
      }
      int mapped = Math.round(lo + (hi - lo) * (v / 15.0F));
      return Math.max(0, Math.min(15, mapped));
   }

   /**
    * 翻转某个通道的无线输出开关。
    *
    * <p>必须 {@code pushWireless}：Create 的 RedstoneLinkNetworkHandler 没有 tick，
    * 只有 updateNetworkOf 被调时才会重读 {@code getTransmittedStrength} 并广播。
    * 不推这一下，外面会继续读到关掉前的旧值。
    */
   public void toggleWireless(SignalChannel channel) {
      if (channel == null) {
         return;
      }
      int i = channel.index();
      this.wirelessEnabled[i] = !this.wirelessEnabled[i];
      this.pushWireless(channel);
      this.saveOwnRecord();
      this.sendData();
      HexieTrain.LOGGER.info("[wireless] {} 通道 {}（需求={} 实发={}）",
         channel.name(), this.wirelessEnabled[i] ? "开" : "关",
         this.output(channel), this.transmittedStrength(channel));
   }

   /** 设置表（右上角设置栏读写它）。 */
   public DispatcherSettings settings() {
      return this.settings;
   }

   /**
    * 步进一个设置项并立刻落盘。
    *
    * <p>设置项只影响输出映射（阶段 3 接上），不影响保存时机，所以这里直接存。
    */
   public void adjustSetting(int index, int delta) {
      if (this.settings.adjust(index, delta)) {
         // 上下限变了 ⇒ 实际输出跟着变 ⇒ 必须让红石网络重读，否则外面还拿旧强度。
         // （Create 的 RedstoneLinkNetworkHandler 没有 tick，只能靠 updateNetworkOf 推。）
         this.pushWireless(SignalChannel.BRAKE);
         this.pushWireless(SignalChannel.THROTTLE);
         this.saveOwnRecord();
         this.sendData();
         HexieTrain.LOGGER.info("[settings] {} → {} ｜ 实发 制动={} 节流={}", this.settings.describe(),
            this.outputMapped(SignalChannel.BRAKE), this.outputMapped(SignalChannel.THROTTLE));
      }
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
               if (tag.contains("Settings")) {
                  this.settings.load(tag.getCompound("Settings"));
               }
               if (tag.contains("WirelessBrake")) {
                  this.wirelessEnabled[SignalChannel.BRAKE.index()] = tag.getBoolean("WirelessBrake");
               }
               if (tag.contains("WirelessThrottle")) {
                  this.wirelessEnabled[SignalChannel.THROTTLE.index()] = tag.getBoolean("WirelessThrottle");
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

            tag.put("Settings", this.settings.save());
            tag.putBoolean("WirelessBrake", this.wirelessEnabled[SignalChannel.BRAKE.index()]);
            tag.putBoolean("WirelessThrottle", this.wirelessEnabled[SignalChannel.THROTTLE.index()]);

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
      // 客户端那份 Train 永远不会被 Create 同步（Train 没有任何 send/sync），
      // 所以名字靠这个同步包带过去，由 CarriageNameMixin 给实体显示用。
      if (this.lastTrainName != null && !this.lastTrainName.isEmpty()) {
         tag.putString("HexieTrainName", this.lastTrainName);
      }
      // 玩家用铭牌注入的列车名（null = 没设过）。必须落盘：
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
      if (this.ownerUUID != null) tag.putUUID("Owner", this.ownerUUID);
      if (this.ownerName != null) tag.putString("OwnerName", this.ownerName);
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
      if (tag.contains("HexieTrainName")) {
         this.lastTrainName = tag.getString("HexieTrainName");
         if (clientPacket && this.boundTrain != null) {
            // 客户端把名字登记起来，供 CarriageNameMixin 给车厢实体显示
            TrainBinder.putClientName(this.boundTrain, this.lastTrainName);
         }
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
      this.ownerUUID = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
      this.ownerName = tag.getString("OwnerName");
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
