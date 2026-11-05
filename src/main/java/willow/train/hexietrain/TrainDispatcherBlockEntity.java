package willow.train.hexietrain;

import com.simibubi.create.Create;
import com.simibubi.create.content.kinetics.transmission.SplitShaftBlockEntity;
import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler.Frequency;
import net.createmod.catnip.data.Couple;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.Create;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import com.simibubi.create.content.trains.schedule.Schedule;
import com.simibubi.create.content.trains.schedule.ScheduleEntry;
import com.simibubi.create.content.trains.schedule.ScheduleItem;
import com.simibubi.create.content.trains.graph.DiscoveredPath;
import com.simibubi.create.content.trains.station.GlobalStation;
import com.simibubi.create.content.trains.schedule.destination.DestinationInstruction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.MenuProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 列车调度台 —— 方块实体。
 *
 * <p>两种工作模式：
 * <ul>
 *   <li><b>手动模式</b>（未绑定列车）：左/右红石 → 正/反向转速，0~15 线性映射。</li>
 *   <li><b>自动模式</b>（绑定了列车）：读列车状态 → 制动曲线 → 三个通道输出，
 *       同时用反向传动逼停列车。</li>
 * </ul>
 *
 * <p>三个输出通道（每条都是一对 Create 无线红石频率）：
 * <ol>
 *   <li>刹车 —— 0~15，距离目标越近越强（已做平方根补偿）</li>
 *   <li>左转 —— 0~15</li>
 *   <li>右转 —— 0~15</li>
 * </ol>
 */
public class TrainDispatcherBlockEntity extends SplitShaftBlockEntity implements MenuProvider, net.minecraft.world.Container {

    /** 输出转速上限（RPM）。 */
    public static final float MAX_RPM = 256f;

    /** 应力容量（SU）。 */
    public static final float STRESS_CAPACITY = 512f;

    /** 每个通道两个频率槽（频率1 / 频率2），共 6 个。 */
    public static final int FREQ_SLOTS = SignalChannel.VALUES.length * 2;

    private final ItemStack[] frequencies = new ItemStack[FREQ_SLOTS];

    /** 三个无线发送通道，一一对应 SignalChannel。 */
    private final WirelessChannel[] wireless = new WirelessChannel[SignalChannel.VALUES.length];

    /** 各通道当前输出强度 0~15，给 UI 显示和无线发送用。 */
    private final int[] outputs = new int[SignalChannel.VALUES.length];




    /** 时刻表里的站点名列表（顺序 = 条目顺序）。 */
    private final java.util.List<String> stopNames = new java.util.ArrayList<>();

    /** 当前在长栏里选中的站点下标。 */

    /** 加减速灵敏度（格/秒²），直接作为制动曲线的最大减速度。 */

    /** 下一个目标（车站名或信号）名字，给 UI 的长栏用。 */
    private String nextTargetName = "";

    /**
     * 是否允许输出动力。
     *
     * <p>规则：必须已绑定时刻表，并且左右任一面有红石输入。
     * 其余情况一律不输出（也不会把传动杆连进来）。
     */
    public boolean isDriving() {
        // 有红石就接传动轴：手动模式（无时刻表）也要输出转速
        return leftSignal() > 0 || rightSignal() > 0;
    }

    /** 最近一次读到的到目标距离，给 UI 用。 */
    private double lastDistance = 0;

    /** 诊断日志计时。 */

    /** 简化自动驾驶的巡航速度（格/tick）。 */
    private static final double AUTO_SPEED = 0.15;

    /** 制动区内的最大接近速度（格/tick）。 */
    private static final double MAX_APPROACH_SPEED = 0.35;

    /** 进站：5 格内限速 3 m/s。 */
    private static final double SLOW_ZONE_BLOCKS = 5.0;
    private static final double SLOW_ZONE_SPEED_MPS = 3.0;

    /** 蠕行速度：保证车头点能跨过站点，否则永远触发不了到站。 */
    private static final double CREEP_SPEED_MPS = 0.3;
    private static final double STOP_ZONE = 3.0;   // 格：进入此距离切断扭矩

    /** 期望制动减速度（m/s²），用于估算"还要滑多远"。先给常量，实测后调。 */
    private static final double EXPECTED_BRAKE_DECEL = 1.0;

    /** 距车站多少格内允许用反向传动提供反向推力。 */
    private static final double REVERSE_ZONE_BLOCKS = 20.0;
    /** 判定"刹不住"的安全余量（格）。 */
    private static final double STOP_MARGIN_BLOCKS = 2.0;

    /** 本次行程是否还想到站：到站后置 false，离站足够远再置回 true。 */
    private int arrivedEntry = -1;

    /** 到站后等待时刻表发号施令：记下停站时的条目号。 */
    private int stoppedEntry = -1;
    private boolean waitingForSchedule = false;
    private int targetLogTicks = 0;
    private int lastEntrySeen = -1;

    /** 速度传感器：上一 tick 测得的世界速度矢量（m/s，含旋转贡献）。 */
    private final org.joml.Vector3d measuredVelocity = new org.joml.Vector3d();

    /** 速度传感器：速度大小（m/s），低于 0.05 归零（与航空学一致）。 */
    private double measuredSpeedMps = 0;

    /** 自动模式下传动要输出的方向：+1 正传、-1 反传、0 断开。 */
    private float autoDirection = 0f;
    /** 方块的"左"是否就是虚拟列车定义的前进方向（绑定/放置时一次判定，之后不变）。 */
    private Boolean leftIsForward = null;

    /** 输出传动比 0~1：视距内按加速度渐变到 1，视距外直接用二进制。 */
    private float outputRatio = 0f;

    /** 调度器自身的工作状态。 */
    private DispatcherState dispatcherState = DispatcherState.IDLE;
    private int lastRatioQuantum = -1;

    /** 沿轨道到车站的剩余距离缓存。 */
    private double routeDistance = -1;
    private int routeTicks = 0;
    private int brakeLogTicks = 0;
    private int derailLogTicks = 0;

    /** 所有已加载的调度台（由全局 ticker 驱动，不依赖方块实体自身 tick）。 */
    private static final Set<TrainDispatcherBlockEntity> ACTIVE = ConcurrentHashMap.newKeySet();
    /** 每列车只认一个驱动者实例（按对象身份，不按方块坐标——Sable 绘图区坐标与世界坐标不一致）。 */
    private static final java.util.Map<UUID, TrainDispatcherBlockEntity> TRAIN_OWNER = new ConcurrentHashMap<>();
    private int ownerWarnTicks = 0;
    /**
     * 本 BE 是否是这列车唯一的驱动者。
     *
     * <p>旧 owner 若已失效（被移除 / 没有 level），必须能被抢占，
     * 否则活着的实例永远拿不到驱动权，表现为"服务端完全沉默"。
     */
    private boolean isDriver() {
        if (boundTrain == null) return false;
        TrainDispatcherBlockEntity cur = TRAIN_OWNER.get(boundTrain);
        if (cur == this) return true;
        if (cur == null) {
            TrainDispatcherBlockEntity raced = TRAIN_OWNER.putIfAbsent(boundTrain, this);
            return raced == null || raced == this;
        }
        if (cur.isRemoved() || cur.getLevel() == null) {
            TRAIN_OWNER.put(boundTrain, this);
            HexieTrain.LOGGER.info("[HexieTrain][owner] 抢占失效驱动权 @{}", worldPosition);
            return true;
        }
        if (ownerWarnTicks++ % 200 == 0)
            HexieTrain.LOGGER.warn("[HexieTrain][owner] 本实例不是驱动者，被上游实例占用（{} tick）", ownerWarnTicks);
        return false;
    }


    public static Set<TrainDispatcherBlockEntity> activeDispatchers() {
        return ACTIVE;
    }

    /** tick 存活探针计时。 */

    /** 上一 tick 的车头世界坐标，用于"是否跨过车站"的精确判定。 */
    private net.minecraft.world.phys.Vec3 lastLeadPos = null;

    /** 时刻表卡在 PRE_TRANSIT 的计时。 */
    private int stallTicks = 0;
    private int rebindCooldown = 0;
    private int missingTicks = 0;
    private int presenceTicks = 0;
    private long lastSendTick = -1;
    private int diagTicks = 0;
    private String lastDestName = "";
    private int lastEntryLogged = -1;
    private double pendingLeg = -1;   // Create 刚规划出的新段长度（在被我们写脏之前捕获）
    private boolean leakSwept = false;

    /** true = 玩家操控中（暂停自动寻路）；false = 自动驾驶中。 */
    private boolean manualMode = false;

    /** 手动直驱的最大速度（格/tick）。1.0 = 20 格/秒。 */
    private static final double MANUAL_MAX_SPEED = 0.25;   // 格/tick → 5 格/秒

    /** 时刻表槽。非空且绑定成功 = 自动模式。 */
    private ItemStack scheduleSlot = ItemStack.EMPTY;

    /** 最近一次绑定结果，给 UI 显示。 */
    private String lastBindMessage = "";

    /** 绑定的列车；null = 手动模式。 */
    private UUID boundTrain;

    /** 放置这个方块的玩家 —— 列车拥有者。 */
    private UUID owner;

    /** 上一次的目的地对象，用来判断"目的地是否刚变"。 */
    private Object lastDestination;


    /** true = 对刹车通道做平方根补偿（抵消转向架的 Mth.square）。 */
    private boolean linearizeBrake = true;


    public TrainDispatcherBlockEntity(BlockPos pos, BlockState state) {
        this(HexieRegistries.TRAIN_DISPATCHER_BE.get(), pos, state);
    }

    public TrainDispatcherBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        for (int i = 0; i < FREQ_SLOTS; i++) {
            frequencies[i] = ItemStack.EMPTY;
        }
        for (SignalChannel ch : SignalChannel.VALUES) {
            wireless[ch.index()] = new WirelessChannel(this, ch);
        ACTIVE.add(this);   // 构造函数即注册：某些加载路径不经过 initialize()
        }
    }

    // ══════════════════════ 红石 → 转速（手动模式） ══════════════════════

    public int leftSignal() {
        return sideSignal(true);
    }

    public int rightSignal() {
        return sideSignal(false);
    }

    /**
     * 读取某一侧的红石强度。
     *
     * <p>两种来源都算：
     * <ol>
     *   <li>邻方块直接朝我们发射信号（getSignal）</li>
     *   <li>邻方块自己被充能（例如被拉杆从背面供电）——取它周围最强信号</li>
     * </ol>
     */
    private int sideSignal(boolean left) {
        if (level == null) return 0;
        // 垂直朝向也不能崩：统一走 Block 的安全方向表
        Direction facing = getBlockState().getValue(TrainDispatcherBlock.FACING);
        Direction dir = left ? getBlockState().getValue(TrainDispatcherBlock.FACING)
                            : getBlockState().getValue(TrainDispatcherBlock.FACING).getOpposite();
        int direct = level.getSignal(worldPosition, dir);
        int indirect = level.getBestNeighborSignal(worldPosition.relative(dir));
        return Math.max(direct, indirect);
    }

    private Direction horizontalFacing() {
        Direction facing = getBlockState().getValue(TrainDispatcherBlock.FACING);
        return facing.getAxis().isHorizontal() ? facing : null;
    }
    /**
     * 本方块不消耗应力：它只是传动链上的换向器，自身不做功。
     * 不覆盖的话 Create 会按默认 impact 值向网络索取 SU（实测：空转也吃应力）。
     */
    @Override
    public float calculateStressApplied() {
        this.lastStressApplied = 0f;
        return 0f;
    }

    /** 也不提供应力容量。 */
    @Override
    public float calculateAddedStressCapacity() {
        return 0f;
    }


    /** 传动比（照抄参考实现）。 */
    @Override
    public float getRotationSpeedModifier(Direction face) {
        if (hasSource()) {
            if (face == getSourceFacing()) return 1.0f;

            boolean left = getBlockState().getValue(TrainDispatcherBlock.LEFT_POWERED);
            boolean right = getBlockState().getValue(TrainDispatcherBlock.RIGHT_POWERED);
            if (left && right) return 0.0f;
            if (left) return 1.0f;
            if (right) return -1.0f;
        }
        return 0.0f;
    }

    // ══════════════════════ tick ══════════════════════

    @Override
    public void tick() {
        super.tick();
        // 逻辑不在这里跑：由 DispatcherTicker 全局驱动（方块不能当大脑）
    }


    /**
     * 玩家操控中：本方块就是一个定向齿轮换向器，仅此而已。
     *
     * <p>不做任何额外的事 —— 不推物理体、不设速度、不输出通道。
     * 传动比由 getRotationSpeedModifier 按左右红石直接决定，
     * 转速则由上游传动轴提供。
     */
    // ══════════ 【重写】调度器运行逻辑 ══════════

    /** 自动模式：一切由 Create 的导航决定方向与距离，我们只负责输出传动与刹车。 */
    /** 保证列车有"列车员"标志：DestinationInstruction.start 的第一道门槛。 */
    private void ensureConductors(Train train) {
        try {
            if (train == null) return;
            for (var c : train.carriages) {
                if (c.presentConductors == null) continue;
                c.presentConductors.replace(v -> Boolean.TRUE);
            }
        } catch (Throwable ignored) {}
    }







    /** 红石状态变化回调：手动模式下立刻重算转速，不用等下一 tick 的轮询。 */
    public void onRedstoneChanged() {
        if (boundTrain == null) {

        }
        setChanged();
    }

    // ══════════════════════ UI 数据 ══════════════════════


    // ══════════════════════ 站点选择 ══════════════════════

    /** 时刻表里的站点名（给界面长栏用）。 */
    public java.util.List<String> stopNames() {
        return stopNames;
    }

    /** 重新从时刻表里抽取站点名。 */
    public void refreshStopNames() {
        stopNames.clear();
        if (level != null && !scheduleSlot.isEmpty() && scheduleSlot.getItem() instanceof ScheduleItem) {
            try {
                Schedule schedule = ScheduleItem.getSchedule(level.registryAccess(), scheduleSlot);
                if (schedule != null && schedule.entries != null) {
                    for (ScheduleEntry entry : schedule.entries) {
                        if (entry != null && entry.instruction instanceof DestinationInstruction dest) {
                            String name = dest.getFilter();
                            stopNames.add(name == null || name.isBlank() ? "（未命名）" : name);
                        } else {
                            stopNames.add("（非车站指令）");
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        sendData();
    }


    // ══════════════════════ 驾驶模式 ══════════════════════

    public boolean manualMode() {
        return manualMode;
    }

    /** 切换自动驾驶 / 玩家操控。切回自动时会恢复寻路。 */
    public void setManualMode(boolean value) {
        manualMode = value;
        if (value) { SetOutputBrake(0); setOutput(SignalChannel.LEFT, 0); setOutput(SignalChannel.RIGHT, 0); refreshWireless(); }   // 交权：清掉我们之前留下的输出
        if (level != null && !level.isClientSide && boundTrain != null) {
            Train train = Create.RAILWAYS.trains.get(boundTrain);
            if (train != null) {
                try {
                    if (value) {
                        train.navigation.cancelNavigation();   // 只取消寻路目标，不冻结列车
                        train.runtime.paused = false;           // 关键：不能暂停，否则列车的点不再推进
                        // 播报：玩家接管
                    } else {
                        train.runtime.paused = false;          // 恢复自动巡航
                        // 切回自动驾驶：立刻试一次寻路，并把结果播报给车主
                        var stations9 = new java.util.ArrayList<com.simibubi.create.content.trains.station.GlobalStation>(
                                train.graph.getPoints(com.simibubi.create.content.trains.graph.EdgePointType.STATION));
                        var path9 = train.navigation.findPathTo(stations9, Double.MAX_VALUE);
                        if (path9 != null) {
                            try { train.status.successfulNavigation(); } catch (Throwable ignored) { }
                            HexieTrain.LOGGER.info("[HexieTrain][path] 导航成功");
                        } else {
                            String target9 = stations9.isEmpty() ? "" : stations9.get(0).name;
                            try { train.status.failedNavigationNoTarget(target9); } catch (Throwable ignored) { }
                            HexieTrain.LOGGER.info("[HexieTrain][path] 找不到可用路径（目标 {}）", target9);
                        }
                        train.manualTick = false;
                        train.targetSpeed = 0;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        setChanged();
        // 模式变了，传动比也变了：必须强制重建网络，否则 Create 仍用旧缓存
        refreshKinetics();
        sendData();
        syncTrainPresence();   // 模式变化 → 同步虚拟列车的有无
    }


    /** 下一个目标的显示名（车站名 / 前方信号）。 */
    public String nextTargetName() {
        return nextTargetName;
    }

    /** 到目标距离（格）；没有目标时为 0。 */
    public double distanceToTarget() {
        return lastDistance;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.hexietrain.train_dispatcher");
    }


    /** 由 DispatcherTicker 每 tick 调用。 */
    public void serverTick() {
        updateMeasuredSpeed();
        try {
            if (boundTrain != null && !Create.RAILWAYS.trains.containsKey(boundTrain)) {
                if (missingTicks % 200 == 0)
                    HexieTrain.LOGGER.warn("[HexieTrain][run] RAILWAYS 缺列车 {}（{} tick）", boundTrain, missingTicks);
                if (missingTicks++ > 100) { boundTrain = null; missingTicks = 0; }   // 5 秒仍无 → 允许重绑
                return;
            }
        } catch (Throwable t) { HexieTrain.LOGGER.warn("[HexieTrain][run] 自检异常 {}", t.toString()); }
        if (presenceTicks++ % 20 == 0) syncTrainPresence();   // 每 20 tick 校验一次
        if (boundTrain != null) tickAutomated(); else tickManual();
    }

    /** 自动模式：方向与距离都来自 Create 的导航，我们只输出传动与刹车。 */
    private void tickAutomated() {
        try {
            Train train = Create.RAILWAYS.trains.get(boundTrain);
            if (!isDriver()) return;   // 非驱动者什么都不做（不写状态、不刷日志）
            if (train == null) return;
            ensureConductors(train);

            // ── 飞行记录仪：一次测试收集全部状态 ──
            if (!isPrimaryDispatcher()) return;   // 只让主调度器打日志，副本不刷屏
            try {
                var nv = train.navigation;
                String dn = nv.destination == null ? "null" : nv.destination.name;
                if (train.runtime.currentEntry != lastEntryLogged) {
                    HexieTrain.LOGGER.info("[HexieTrain][evt] entry {} → {}", lastEntryLogged, train.runtime.currentEntry);
                    lastEntryLogged = train.runtime.currentEntry;
                    syncProgressToItem(train);
                }
                if (!dn.equals(lastDestName)) {
                    HexieTrain.LOGGER.info("[HexieTrain][evt] dest {} → {}", lastDestName, dn);
                    lastDestName = dn;
                    if (nv.destination != null) pendingLeg = Math.max(0.0, nv.distanceToDestination);   // ★ 抢先捕获
                }
                if (diagTicks++ % 20 == 0) {
                    HexieTrain.LOGGER.info("[HexieTrain][diag] st={} entry={} dest={} dDest={} behind={} spd={} vphys={} vtrack={} leg={} dir={} vpos={} spos={} vdist={} wc={}",
                            train.runtime.state, train.runtime.currentEntry, dn,
                            String.format("%.1f", nv.distanceToDestination), nv.destinationBehindTrain,
                            String.format("%.3f", train.speed),
                            String.format("%.2f", measuredSpeedMps()),
                            String.format("%.2f", bodySpeedAlongTrackBps()),
                            String.format("%.1f", legRemaining), autoDirection,
                            virtPos(train), statPos(nv.destination), virtDist(train, nv.destination), SubLevelSuspend.worldCenter((ServerLevel) level, worldPosition).toString()
                                    + " disp=" + worldPosition.toShortString() + " tid=" + (train.id.toString().substring(0,8)) + " edgeLen=" + String.format("%.1f", edgeLen(train)) + " edgePos=" + String.format("%.1f", edgePos(train)) + " bound=" + (boundTrain==null?"null":boundTrain.toString().substring(0,8)));
                }
            } catch (Throwable t) { HexieTrain.LOGGER.warn("[HexieTrain][diag] {}", t.toString()); }

            if (!isDriver()) return;   // 非认领者不得驱动
            // POST_TRANSIT 卡死兜底：条件列为空时 tickConditions 永不推进
            try {
                var rt0 = train.runtime;
                if (rt0.state == com.simibubi.create.content.trains.schedule.ScheduleRuntime.State.POST_TRANSIT
                        && rt0.schedule != null && rt0.currentEntry >= 0
                        && rt0.currentEntry < rt0.schedule.entries.size()) {
                    var c0 = rt0.schedule.entries.get(rt0.currentEntry).conditions;
                    if (c0.isEmpty()) c0.add(new java.util.ArrayList<>());
                }
            } catch (Throwable ignored) {}
            int left = leftSignal(), right = rightSignal();
            boolean redstonePresent = left > 0 || right > 0;

            // 玩家给红石 → 单向切手动（要恢复请点 UI 或重设列车）
            if (redstonePresent) {
                if (!manualMode) setManualMode(true);   // 内部会调 syncTrainPresence：注销虚拟列车、通道归零
                double dir = left > 0 ? 1.0 : -1.0;
                double mag = Math.max(left, right) / 15.0;
                train.manualTick = true;
                train.targetSpeed = dir * mag * MANUAL_MAX_SPEED;
                train.approachTargetSpeed(1.0f);
                return;
            }
            // 手动模式：blockstate 由 Block.neighborChanged 独占，我们绝不写（否则会抹掉红石状态）
            if (manualMode) return;   // 玩家操控：刹车/通道全部交给玩家，我们一律不写

            driveToStation(train);
        } catch (Throwable t) {
            HexieTrain.LOGGER.warn("[HexieTrain][auto] 异常: {}", t.toString());
        }
    }

    /** 未绑定：只做红石模式同步，不驱动任何东西。 */
    private void tickManual() {
        boolean manual = leftSignal() > 0 || rightSignal() > 0;
        if (manual != manualMode) {
            manualMode = manual;
            setChanged();
            sendData();
        }
        if (!manual) resetDirectionOutput();   // 空闲：默认不输出传动
    }

    /** 把传动输出归零（blockstate 两侧都不通电 = 不传递旋转）。 */
    private void resetDirectionOutput() {
        autoDirection = 0f;
        if (!(level instanceof ServerLevel sl)) return;
        try {
            BlockState st = getBlockState();
            if (!(st.getBlock() instanceof TrainDispatcherBlock block)) return;
            if (!st.getValue(TrainDispatcherBlock.LEFT_POWERED) && !st.getValue(TrainDispatcherBlock.RIGHT_POWERED)) return;
            sl.setBlock(worldPosition, st.setValue(TrainDispatcherBlock.LEFT_POWERED, false)
                    .setValue(TrainDispatcherBlock.RIGHT_POWERED, false), 3);
            block.detachKinetics(sl, worldPosition, true);
            HexieTrain.LOGGER.info("[HexieTrain][kinetics] 空闲输出归零 @{}", worldPosition);
        } catch (Throwable ignored) {}
    }


    /**
     * 一次判定：方块的"左"是否等于虚拟列车的前进方向。
     * 依据 Simurail 转向架：getMovementSpeed() 已是整条传动链累乘后的行驶速度，
     * 乘上 getDirection() 再投影到轨道切线，就是"这组输出让车沿轨道往哪走"。
     */
    private boolean leftPolarity() {
        if (leftIsForward != null) return leftIsForward;
        leftIsForward = true;
        try {
            Train train = Create.RAILWAYS.trains.get(boundTrain);
            if (train == null || train.carriages.isEmpty() || !(level instanceof ServerLevel sl)) return true;
            var lp = train.carriages.get(0).getLeadingPoint();
            if (lp == null || lp.edge == null) return true;
            var tan = lp.edge.getDirection(true);
            double best = 0;
            for (net.minecraft.core.BlockPos bp : net.minecraft.core.BlockPos.betweenClosed(
                    worldPosition.offset(-6, -4, -6), worldPosition.offset(6, 4, 6))) {
                var be = sl.getBlockEntity(bp);
                if (be == null || !be.getClass().getName().contains("PhysicsBogeyBlockEntity")) continue;
                try {
                    double ms = (double) be.getClass().getMethod("getMovementSpeed").invoke(be);
                    var dir = (org.joml.Vector3dc) be.getClass().getMethod("getDirection").invoke(be);
                    double dot = ms * (dir.x() * tan.x + dir.y() * tan.y + dir.z() * tan.z);
                    if (Math.abs(dot) > Math.abs(best)) best = dot;
                } catch (Throwable ignored) {}
            }
            if (Math.abs(best) > 1.0E-4) {
                leftIsForward = best > 0;
                HexieTrain.LOGGER.info("[HexieTrain][dir] dot={} 左是前进={}", String.format("%.3f", best), leftIsForward);
            } else {
                HexieTrain.LOGGER.info("[HexieTrain][dir] 转向架速度为 0，沿用默认 左是前进=true");
            }
        } catch (Throwable t) { HexieTrain.LOGGER.warn("[HexieTrain][dir] {}", t.toString()); }
        return leftIsForward;
    }

    /**
     * 输出传动方向：写 blockstate 的 LEFT/RIGHT_POWERED 并重建动力学网络。
     *
     * <p>不按"方向是否变化"提前返回，而是每 tick 校验 blockstate 是否与期望一致：
     * 只写一次的话，一旦那次写入被覆盖（网络未就绪/邻居更新/BE 重建），
     * 之后就永远不会再写，表现为"方向记录了但车不动"。
     */
    private void setAutoDirection(float dir) {
        autoDirection = dir;
        if (!(level instanceof net.minecraft.server.level.ServerLevel sl)) return;
        try {
            BlockState st = getBlockState();
            if (!(st.getBlock() instanceof TrainDispatcherBlock block)) return;
            boolean pol = leftPolarity();
            boolean forward = dir > 0.001f, reverse = dir < -0.001f;
            boolean left  = forward ? pol : (reverse ? !pol : false);
            boolean right = forward ? !pol : (reverse ? pol : false);
            if (st.getValue(TrainDispatcherBlock.LEFT_POWERED) == left
                    && st.getValue(TrainDispatcherBlock.RIGHT_POWERED) == right) return;   // 已经是期望状态
            sl.setBlock(worldPosition, st.setValue(TrainDispatcherBlock.LEFT_POWERED, left)
                    .setValue(TrainDispatcherBlock.RIGHT_POWERED, right), 3);
            block.detachKinetics(sl, worldPosition, true);
            HexieTrain.LOGGER.info("[HexieTrain][kinetics] 写输出 L={} R={} @{}", left, right, worldPosition);
        } catch (Throwable t) { HexieTrain.LOGGER.warn("[HexieTrain][kinetics] {}", t.toString()); }
    }




    /** 沿轨距离核减 + 物理到站。 */
    private double legRemaining = -1;
    private Object lastLegDest = null;
    private int lastLegEntry = -1;

    /**
     * 虚拟列车不自走（speed 恒 0）：位置只由 TP 决定，到站由我们按物理车位置触发。
     * 这样它永远不可能跑在物理车前面，也就不会抢物理车前方的闭塞区间。
     */
    /**
     * 物品里的时刻表若与列车 runtime 里的不一致，就重新应用。
     * 这里做差异检测，保证 UI 改了目标站后导航立刻跟着变。
     */
    private void syncScheduleFromItem(Train train) {
        if (level == null || level.isClientSide || scheduleSlot.isEmpty() || train == null) return;
        try {
            var itemSched = ScheduleItem.getSchedule(level.registryAccess(), scheduleSlot);
            if (itemSched == null) return;
            var rt = train.runtime;
            boolean same = false;
            if (rt.schedule != null) {
                same = rt.schedule.entries.size() == itemSched.entries.size()
                        && rt.schedule.cyclic == itemSched.cyclic
                        && rt.schedule.write(level.registryAccess()).equals(itemSched.write(level.registryAccess()));
            }
            if (same) return;
            itemSched.savedProgress = rt.schedule == null ? 0 : rt.currentEntry;
            rt.setSchedule(itemSched, false);
            rt.paused = false;
            legRemaining = -1; lastLegDest = null; lastLegEntry = -1; pendingLeg = -1;
            HexieTrain.LOGGER.info("[HexieTrain][sched] 时刻表已更新，重新应用（{} 条，cyclic={}）",
                    itemSched.entries.size(), itemSched.cyclic);
        } catch (Throwable t) { HexieTrain.LOGGER.warn("[HexieTrain][sched] {}", t.toString()); }
    }

    /**
     * 我们只做三件事：
     * <ol>
     *   <li>读导航方向 → 输出扭矩</li>
     *   <li>用物理位移核减自己的 legRemaining（只用于算刹车，不回写 Create）</li>
     *   <li>物理车到站 → 调 Create 的 arriveAt（这是 Create 唯一无法自己知道的事）</li>
     * </ol>
     * 绝不写 train.speed / navigation.distanceToDestination / runtime.state / currentPath / destination。
     */
    private void driveToStation(Train train) {
        try {
            if (!(level instanceof net.minecraft.server.level.ServerLevel)) return;
            if (train.derailed || train.invalid) return;


            var nav = train.navigation;
            // 判据：我当前所在的站 ≠ 导航目标站 → 无视一切停车指令，强制继续前进
            try {
                GlobalStation curSt = train.getCurrentStation();
                GlobalStation tgtSt = train.navigation.destination;
                if (curSt != null && tgtSt != null
                        && curSt != tgtSt
                        && !String.valueOf(curSt.name).equals(String.valueOf(tgtSt.name))) {
                    float dirAway = (float) (train.navigation.destinationBehindTrain ? -1.0 : 1.0);
                    setAutoDirection(dirAway);
                    SetOutputBrake(0);
                    HexieTrain.LOGGER.info("[HexieTrain][wrongStation] 当前在 {} 但目标 {} → 无视停车，继续前进",
                            curSt.name, tgtSt.name);
                    return;
                }
            } catch (Throwable ignored) {}

            // 停站中（POST_TRANSIT / PRE_TRANSIT）：车停在站里，踩住刹车，让 Create 自己等条件
            try {
                if (train.runtime.state != com.simibubi.create.content.trains.schedule.ScheduleRuntime.State.IN_TRANSIT) {
                    setAutoDirection(0f);
                    SetOutputBrake(15);
                    return;
                }
            } catch (Throwable ignored) {}
            if (nav == null || nav.destination == null) return;
            GlobalStation st = nav.destination;

            // 新段：用 Create 给的沿轨长度作为基准（只读一次）
            if (st != lastLegDest || train.runtime.currentEntry != lastLegEntry || legRemaining < 0) {
                legRemaining = Math.max(0.0, nav.distanceToDestination);
                lastLegDest = st;
                lastLegEntry = train.runtime.currentEntry;
                HexieTrain.LOGGER.info("[HexieTrain][leg] → {} 沿轨长度={}", st.name, String.format("%.1f", legRemaining));
            }

            // 用物理速度核减（只更新我们自己的值）
            double v = Math.abs(measuredSpeedMps());
            legRemaining = Math.max(0.0, legRemaining - v / 20.0);

            // 扭矩永远跟随导航；很近距离且仍在滑行时短暂反转辅助制动
            float navDir = (float) (nav.destinationBehindTrain ? -1.0 : 1.0);
            setAutoDirection(navDir);
            if (legRemaining < 1.0 && v > 0.5) setAutoDirection(-navDir);

            // 刹车：末端制导（越近越强）
            double stopZone = Math.max(3.0, v * 1.2);
            double brakeFrac = legRemaining >= stopZone ? 0.0 : 1.0 - legRemaining / stopZone;
            int brakeVal = (int) Math.round(15.0 * brakeFrac);
            if (legRemaining < 1.0) brakeVal = 15;
            SetOutputBrake(brakeVal);

            // 到站：物理车已经走完这段沿轨距离 → 告诉 Create
            if (legRemaining <= 0.05 && v < 0.5) {
                HexieTrain.LOGGER.info("[HexieTrain][arrive] {} 到站（物理车）", st.name);
                try {
                    train.arriveAt(st);
                    nav.destination = null;   // 照抄 Create：清目标，状态机才能推进
                } catch (Throwable t) { HexieTrain.LOGGER.warn("[HexieTrain][arrive] {}", t.toString()); }
                legRemaining = -1;
                lastLegDest = null;
                lastLegEntry = -1;
            }
        } catch (Throwable t) { HexieTrain.LOGGER.warn("[HexieTrain][auto] {}", t.toString()); }
    }



    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new TrainDispatcherMenu(containerId, playerInventory, this);
    }

    /** 最近玩家的距离（格）；没有玩家在线时返回极大值。 */
    private double nearestPlayerDistance() {
        if (!(level instanceof ServerLevel serverLevel)) return 0;
        // 注意：方块在 Sable 绘图区里，必须换算成世界坐标
        Vec3 center = SubLevelSuspend.worldCenter(serverLevel, worldPosition);
        double best = Double.MAX_VALUE;
        for (ServerPlayer player : serverLevel.players()) {
            double d = player.position().distanceTo(center);
            if (d < best) best = d;
        }
        return best;
    }



    /** 每 tick 最多同步一次，避免同步风暴（曾出现每秒 2.8 次 read、菜单疯狂重建）。 */
    private void sendDataThrottled() {
        long t = level == null ? 0 : level.getGameTime();
        if (t == lastSendTick) return;
        lastSendTick = t;
        sendData();
    }

    private void setOutput(SignalChannel channel, int value) {
        int v = Math.max(0, Math.min(15, value));
        if (outputs[channel.index()] == v) return;
        outputs[channel.index()] = v;
        sendDataThrottled();
        sendData();
    }

    // ══════════════════════ 无线红石 ══════════════════════

    /** 某通道的网络键：一对频率（频率1 / 频率2）。 */
    public Couple<Frequency> networkKey(SignalChannel channel) {
        int base = channel.index() * 2;
        return Couple.create(frequencyOf(frequencies[base]), frequencyOf(frequencies[base + 1]));
    }

    private static Frequency frequencyOf(ItemStack stack) {
        return stack == null || stack.isEmpty() ? Frequency.EMPTY : Frequency.of(stack);
    }

    @Override
    public void initialize() {
        super.initialize();
        ACTIVE.add(this);
        if (level != null && !level.isClientSide) {
            for (WirelessChannel ch : wireless) {
                Create.REDSTONE_LINK_NETWORK_HANDLER.addToNetwork(level, ch);
            }
        }
    }

    /** 频率变了：让网络重新挂一次。 */
    private void refreshNetwork() {
        if (level == null || level.isClientSide) return;
        for (WirelessChannel ch : wireless) {
            Create.REDSTONE_LINK_NETWORK_HANDLER.updateNetworkOf(level, ch);
        }
    }

    @Override
    public void remove() {
        ACTIVE.remove(this);
        TRAIN_OWNER.values().remove(this);   // 释放驱动权，别挡住后续实例
        if (level != null && !level.isClientSide) {
            for (WirelessChannel ch : wireless) {
                Create.REDSTONE_LINK_NETWORK_HANDLER.removeFromNetwork(level, ch);
            }
        }
        super.remove();
    }

    /** 某个通道是否配置了完整的频率对（可以真正发信号）。 */
    public boolean channelConfigured(SignalChannel channel) {
        int base = channel.index() * 2;
        return !frequencies[base].isEmpty() && !frequencies[base + 1].isEmpty();
    }

    // ══════════════════════ 时刻表 → 列车身份 ══════════════════════

    /** 统一的槽位读取：0 = 时刻表，1~6 = 频率槽。 */
    public ItemStack slot(int index) {
        if (index == 0) return scheduleSlot;
        return frequency(index - 1);
    }

    /** 统一的槽位写入。 */
    public void setSlot(int index, ItemStack stack) {
        if (index == 0) {
        refreshWireless();
            scheduleSlot = stack == null ? ItemStack.EMPTY : stack;
            setChanged();
        } else {
            setFrequency(index - 1, stack);
        }
    }

    public ItemStack scheduleSlot() {
        return scheduleSlot;
    }

    public String lastBindMessage() {
        return lastBindMessage;
    }

/**
 * 时刻表槽的唯一入口（TrainDispatcherContainer 直接调用这里）。
 *
 * <p>放入 → 立刻建车；清空 → 注销列车。
 */
    public void setScheduleSlot(ItemStack stack) {
        this.scheduleSlot = stack == null ? ItemStack.EMPTY : stack.copyWithCount(1);
        refreshStopNames();
        if (level != null && !level.isClientSide) {
            HexieTrain.LOGGER.info("[HexieTrain][schedule] 设置时刻表：empty={} manual={} bound={}",
                    scheduleSlot.isEmpty(), manualMode, boundTrain != null);
            if (!scheduleSlot.isEmpty()) {
                if (manualMode) { manualMode = false; HexieTrain.LOGGER.info("[HexieTrain][schedule] 放入时刻表 → 退出玩家操控"); }
                if (boundTrain == null) bindTrainNow();
            } else if (boundTrain != null) {
                dissolve();
            }
        }
        setChanged();
        sendDataThrottled();
    }


    /** 拆除列车身份：解除寻路 → 离站 → 从 RAILWAYS 移除。 */
    public void dissolve() {
        if (boundTrain == null) return;
        if (level != null && !level.isClientSide) {
            Train train = Create.RAILWAYS.trains.get(boundTrain);
            if (train != null) {
                try {
                    train.navigation.cancelNavigation();
                } catch (Throwable ignored) {
                }
                try {
                    train.leaveStation();
                } catch (Throwable ignored) {
                }
                Create.RAILWAYS.removeTrain(boundTrain);
                TrainBinder.OUR_TRAINS.remove(boundTrain);
            }
        }
        if (level != null) {
        }
        boundTrain = null;
        lastDestination = null;
    }

    /**
     * 单一入口：根据当前状态决定"虚拟列车应当存在还是不存在"。
     * 放入时刻表 / 切回自动 → 建车；取出时刻表 / 切手动 / 玩家给红石 → 注销并归零。
     */
    /** 真正建车的私有动作，只被 syncTrainPresence 调用。 */
    /**
     * 照抄 Create 的 ScheduleRuntime.returnSchedule（第 424-425 行）：
     * 把 currentEntry 写回时刻表物品的 savedProgress，使下次 setSchedule 能读到正确进度。
     */
    private void syncProgressToItem(Train train) {
        if (level == null || level.isClientSide) return;
        if (scheduleSlot.isEmpty() || train == null) return;
        try {
            var sched = train.runtime.schedule;
            if (sched == null) return;
            if (!(scheduleSlot.getItem() instanceof ScheduleItem si)) return;
            sched.savedProgress = train.runtime.currentEntry;
            @SuppressWarnings("unchecked")
            net.minecraft.core.component.DataComponentType<Object> type =
                    (net.minecraft.core.component.DataComponentType<Object>) si.getComponentType();
            scheduleSlot.set(type, sched.write(level.registryAccess()));
            setChanged();
        } catch (Throwable t) { HexieTrain.LOGGER.warn("[HexieTrain][progress] {}", t.toString()); }
    }

    private void bindTrainNow() {
        if (!(level instanceof ServerLevel serverLevel)) return;
        try {
            TrainBinder.Result result = TrainBinder.bind(serverLevel, worldPosition, scheduleSlot, owner);
            lastBindMessage = result.message();
            HexieTrain.LOGGER.info("[HexieTrain][presence] 建车 ok={} msg={}", result.ok(), result.message());
            if (result.ok()) {
                bindTrain(result.trainId());
                if (SubLevelSuspend.resume(serverLevel, worldPosition)) {
                    TrainBinder.PERSISTENT_LOADS.add(result.trainId());
                }
            }
        } catch (Throwable t) {
            HexieTrain.LOGGER.error("[HexieTrain][presence] 建车异常 @{}", worldPosition, t);
        }
    }

    /** 只有"槽里没有时刻表"才注销；其它任何情况都不重建、不注销。 */
    private void syncTrainPresence() {
        if (level == null || level.isClientSide) return;
        boolean hasSchedule = !scheduleSlot.isEmpty()
                && scheduleSlot.getItem() instanceof com.simibubi.create.content.trains.schedule.ScheduleItem;
        if (!hasSchedule) {
            if (boundTrain != null) {
                HexieTrain.LOGGER.info("[HexieTrain][presence] 时刻表已取出 → 注销虚拟列车");
                dissolve();
                resetRuntimeState();
                resetDirectionOutput();
            }
            return;
        }
        // 有时刻表但没绑定：交给 setScheduleSlot / bindTrainNow 处理，这里不主动重建
    }


    /** 把本调度器的运行时状态全部归零（换挡、通道、距离、到站锁）。 */
    private void resetRuntimeState() {
        legRemaining = -1;
        lastLegDest = null;
        lastLegEntry = -1;
        lastDestName = "";
        lastEntryLogged = -1;
        autoDirection = 0f;
        leftIsForward = null;
        try {
            setOutput(SignalChannel.BRAKE, 0);
            setOutput(SignalChannel.LEFT, 0);
            setOutput(SignalChannel.RIGHT, 0);
            refreshWireless();
        } catch (Throwable ignored) {}
    }


    // ══════════════════════ 对外接口 ══════════════════════

    public int output(SignalChannel channel) {
        return outputs[channel.index()];
    }

    public ItemStack frequency(int slot) {
        return slot >= 0 && slot < FREQ_SLOTS ? frequencies[slot] : ItemStack.EMPTY;
    }

    public void setFrequency(int slot, ItemStack stack) {
        if (slot < 0 || slot >= FREQ_SLOTS) return;
        frequencies[slot] = stack == null ? ItemStack.EMPTY : stack;
        refreshNetwork();
        setChanged();
        sendData();
    }

    public UUID boundTrain() {
        return boundTrain;
    }

    public void bindTrain(UUID id) {
        leftIsForward = null;   // 重新判定朝向
        boundTrain = id;
        lastDestination = null;
        setChanged();
        sendData();
    }


    public boolean linearizeBrake() {
        return linearizeBrake;
    }

    public void setLinearizeBrake(boolean value) {
        linearizeBrake = value;
        setChanged();
    }

    // ══════════════════════ 存档 ══════════════════════

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        if (boundTrain != null) tag.putUUID("BoundTrain", boundTrain);
        tag.putBoolean("ManualMode", manualMode);
        if (owner != null) tag.putUUID("Owner", owner);
        // 方块特性：玩家在 UI 里调的制动灵敏度
        if (!scheduleSlot.isEmpty()) tag.put("Schedule", sanitizedSchedule().save(registries));
        net.minecraft.nbt.ListTag names = new net.minecraft.nbt.ListTag();
        for (String s : stopNames) names.add(net.minecraft.nbt.StringTag.valueOf(s));
        tag.put("StopNames", names);
        tag.putBoolean("LinearizeBrake", linearizeBrake);
        for (int i = 0; i < FREQ_SLOTS; i++) {
            if (!frequencies[i].isEmpty()) {
                tag.put("Freq" + i, frequencies[i].save(registries));
            }
        }
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if (tag.hasUUID("BoundTrain")) boundTrain = tag.getUUID("BoundTrain");   // 缺失时保留已有绑定
        if (boundTrain != null) {
            // 重启后 OUR_TRAINS 是空的，必须重新登记，否则车厢会失去 mixin 保护
            TrainBinder.OUR_TRAINS.add(boundTrain);
            HexieTrain.LOGGER.info("[HexieTrain] 从存档恢复列车绑定 {}", boundTrain);
        }
        manualMode = tag.getBoolean("ManualMode");
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        // 方块特性：制动灵敏度（缺省 3.0 格/秒²）

        // 瞬态状态：读档后一律复位，等本 tick 重新判定
        stallTicks = 0;
        lastLeadPos = null;
        lastDestination = null;
        linearizeBrake = !tag.contains("LinearizeBrake") || tag.getBoolean("LinearizeBrake");

        scheduleSlot = tag.contains("Schedule")
                ? ItemStack.parseOptional(registries, tag.getCompound("Schedule"))
                : ItemStack.EMPTY;

        stopNames.clear();
        net.minecraft.nbt.ListTag names = tag.getList("StopNames", net.minecraft.nbt.Tag.TAG_STRING);
        for (int i = 0; i < names.size(); i++) {
            stopNames.add(names.getString(i));
        }

        for (int i = 0; i < FREQ_SLOTS; i++) {
            String key = "Freq" + i;
            frequencies[i] = tag.contains(key)
                    ? ItemStack.parseOptional(registries, tag.getCompound(key))
                    : ItemStack.EMPTY;
        }
    }


    /**
     * 物理体沿轨道的真实速度（格/秒）。
     *
     * <p>手动模式下物理体是被转速驱动的，影子车要跟着它走，
     * 否则两边的位置会越差越远。
     */
    /**
     * 更新速度传感器读数。
     *
     * <p>数据来源照抄 simulated 的速度传感器：同一局部点在 current/last 姿态下的世界位移 × 20，
     * 因此包含旋转贡献，等价于"结构上这一点实际怎么动"。
     *
     * <p>每 tick 调用一次，结果常驻在 {@link #measuredVelocity()} / {@link #measuredSpeedMps()}，
     * 供 UI 显示与后续控制运算使用。
     */
    private void updateMeasuredSpeed() {
        if (!(level instanceof net.minecraft.server.level.ServerLevel serverLevel)) return;
        org.joml.Vector3d v = SubLevelSuspend.velocityAt(serverLevel, worldPosition);
        measuredVelocity.set(v);
        double mag = v.length();
        measuredSpeedMps = mag > 0.05 ? mag : 0.0;      // 与航空学一致的死区
    }

    /** 速度传感器读数：速度大小（m/s）。 */
    public double measuredSpeedMps() {
        return measuredSpeedMps;
    }

    /** 速度传感器读数：世界速度矢量（m/s），调用方不要修改。 */
    public org.joml.Vector3dc measuredVelocity() {
        return measuredVelocity;
    }

    /** 速度传感器读数，放大 100 倍的整数（用于 ContainerData 同步）。 */
    public int measuredSpeedCenti() {
        return (int) Math.round(measuredSpeedMps * 100.0);
    }
    private double bodySpeedAlongTrackBps() {
        try {
            Train tr = Create.RAILWAYS.trains.get(boundTrain);
            if (tr == null || tr.carriages.isEmpty()) return 0;
            var lp = tr.carriages.get(0).getLeadingPoint();
            if (lp == null || lp.edge == null) return 0;

            double len = Math.max(1.0E-6, lp.edge.getLength());
            net.minecraft.world.phys.Vec3 dir = lp.edge.getDirectionAt(Math.min(1.0, lp.position / len));

            // 用速度传感器读数（姿态差分），而不是刚体质心线速度：
            // 后者不含旋转贡献，过弯时会偏小。
            var v = measuredVelocity();
            return v.x() * dir.x + v.y() * dir.y + v.z() * dir.z;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 记录操作者。 */
    public void setOwner(UUID playerId) {
        if (playerId == null || playerId.equals(owner)) return;
        owner = playerId;
        setChanged();
    }

    public UUID owner() {
        return owner;
    }

    // 【实验性代码】开始：沿轨距离核减 + 物理到站


    /** 把虚拟车头点对齐到车站（一次性，仅到站时）。 */
    /** 物理车当前位置到车站方块的水平距离（格）。 */
    /** 虚拟车头点的世界坐标。 */
    private double edgeLen(Train train) {
        try { return train.carriages.get(0).getLeadingPoint().edge.getLength(); } catch (Throwable t) { return -1; }
    }

    private double edgePos(Train train) {
        try { return train.carriages.get(0).getLeadingPoint().position; } catch (Throwable t) { return -1; }
    }

    private String virtPos(Train train) {
        try {
            var lp = train.carriages.get(0).getLeadingPoint();
            var p = lp.getPosition(train.graph);
            return String.format("(%.1f,%.1f,%.1f)", p.x, p.y, p.z);
        } catch (Throwable t) { return "?"; }
    }

    /** 车站的世界坐标（边两端中点）。 */
    private String statPos(GlobalStation st) {
        if (st == null) return "?";
        try {
            var a = st.edgeLocation.getFirst().getLocation();
            var b = st.edgeLocation.getSecond().getLocation();
            return String.format("(%.1f,%.1f,%.1f)", (a.x + b.x) / 2, (a.y + b.y) / 2, (a.z + b.z) / 2);
        } catch (Throwable t) { return "?"; }
    }

    /** 虚拟车头点到车站的距离。 */
    private double virtDist(Train train, GlobalStation st) {
        if (st == null) return -1;
        try {
            var lp = train.carriages.get(0).getLeadingPoint();
            var p = lp.getPosition(train.graph);
            var a = st.edgeLocation.getFirst().getLocation();
            var b = st.edgeLocation.getSecond().getLocation();
            double mx = (a.x + b.x) / 2, my = (a.y + b.y) / 2, mz = (a.z + b.z) / 2;
            double dx = p.x - mx, dy = p.y - my, dz = p.z - mz;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        } catch (Throwable t) { return -1; }
    }

    private double physicalDistanceTo(GlobalStation st) {
        try {
            var sl = (net.minecraft.server.level.ServerLevel) level;
            var body = SubLevelSuspend.containing(sl, worldPosition);
            if (body == null) return 0;
            var p = body.logicalPose().position();
            var n1 = st.edgeLocation.getFirst().getLocation();
            var n2 = st.edgeLocation.getSecond().getLocation();
            var mid = n1.add(n2).scale(0.5);
            double dx = p.x() - mid.x, dz = p.z() - mid.z;
            return Math.sqrt(dx * dx + dz * dz);
        } catch (Throwable t) { return 0; }
    }

    private void snapVirtualTo(Train train, GlobalStation st) {
        try {
            var graph = train.graph;
            if (graph == null || train.carriages.isEmpty()) return;
            var loc = st.edgeLocation;
            var n1 = graph.locateNode(loc.getFirst());
            var n2 = graph.locateNode(loc.getSecond());
            var e = graph.getConnectionsFrom(n1).get(n2);
            if (e == null) return;
            var lp = train.carriages.get(0).getLeadingPoint();
            lp.node1 = n1; lp.node2 = n2; lp.edge = e;
            lp.position = Math.max(0.0, st.getLocationOn(e) - 1.0);   // 停在站点前 1 格，避免立刻再次触发到站
        } catch (Throwable ignored) {}
    }
    // 【实验性代码】结束



    private void SetOutputBrake(int value) {
        int before = output(SignalChannel.BRAKE);
        setOutput(SignalChannel.BRAKE, Math.max(0, Math.min(15, value)));
        if (output(SignalChannel.BRAKE) != before) refreshWireless();   // 值变了才重新广播
    }


    /** 强制让 Create 重新评估本方的传动比。 */
    private void refreshKinetics() {
        if (level == null || level.isClientSide) return;
        if (getBlockState().getBlock() instanceof TrainDispatcherBlock block) {
            level.blockUpdated(worldPosition, block);   // 安全刷新，不再摘网络
        }
    }

    /** 当前正在执行的第几条站点。 */
    public int currentEntry() {
        try {
            Train tr = Create.RAILWAYS.trains.get(boundTrain);
            if (tr != null && tr.runtime != null) {
                return tr.runtime.currentEntry;
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }



    /** 本方块是否是这列车的主调度器（一列车只允许一个输出传动）。 */
    private boolean isPrimaryDispatcher() {
        if (boundTrain == null) return false;
        net.minecraft.core.BlockPos primary = TrainBinder.TRAIN_DISPATCHERS.get(boundTrain);
        return primary != null && primary.equals(worldPosition);
    }


    /** 当前实际速度（格/tick × 100），给 UI 显示用。 */

    /** 当前调度器工作状态。 */
    public DispatcherState dispatcherState() {
        return dispatcherState;
    }

    private void setDispatcherState(DispatcherState state) {
        if (dispatcherState == state) return;
        dispatcherState = state;
        sendData();
    }


    /** 频率变了：必须从网络摘掉再挂回去，否则仍用旧的网络键。 */
    private void refreshWireless() {
        if (level == null || level.isClientSide) return;
        for (WirelessChannel ch : wireless) {
            try {
                Create.REDSTONE_LINK_NETWORK_HANDLER.removeFromNetwork(level, ch);
                Create.REDSTONE_LINK_NETWORK_HANDLER.addToNetwork(level, ch);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 净化时刻表物品：旧版本曾把 Schedule 对象直接写进物品组件（应为 CompoundTag），
     * 那会导致存档序列化时 ClassCastException，进而整个存档崩掉。
     * 这里在保存前把非法组件剥掉，让存档自愈。
     */
    private ItemStack sanitizedSchedule() {
        ItemStack stack = scheduleSlot.copy();
        if (stack.isEmpty()) return stack;
        try {
            if (stack.getItem() instanceof ScheduleItem scheduleItem) {
                @SuppressWarnings("unchecked")
                net.minecraft.core.component.DataComponentType<Object> type =
                        (net.minecraft.core.component.DataComponentType<Object>) scheduleItem.getComponentType();
                Object value = stack.get(type);
                if (value != null && !(value instanceof CompoundTag)) {
                    stack.remove(type);
                    HexieTrain.LOGGER.warn("[HexieTrain] 已清除损坏的时刻表组件（类型 {}），存档可继续",
                            value.getClass().getName());
                }
            }
        } catch (Throwable t) {
            HexieTrain.LOGGER.warn("[HexieTrain] 净化时刻表失败: {}", t.toString());
        }
        return stack;
    }

    // ══════════════════ Container（让方块成为"能存一个时刻表的容器"） ══════════════════

    @Override
    public int getContainerSize() {
        return TrainDispatcherContainer.SIZE;
    }

    @Override
    public boolean isEmpty() {
        for (int i = 0; i < getContainerSize(); i++) {
            if (!slot(i).isEmpty()) return false;
        }
        return true;
    }

    @Override
    public ItemStack getItem(int index) {
        return slot(index);
    }
    /** 普通容器取出：不做任何业务动作（曾误把 returnSchedule 挂在这里，导致槽内时刻表被内部流程清空）。 */
    @Override
    public ItemStack removeItem(int index, int amount) {
        ItemStack current = slot(index);
        if (current.isEmpty()) return ItemStack.EMPTY;
        ItemStack taken = current.split(amount);
        setItem(index, current);
        return taken;
    }

    @Override
    public ItemStack removeItemNoUpdate(int index) {
        ItemStack current = slot(index);
        setItem(index, ItemStack.EMPTY);
        return current;
    }

    @Override
    public void setItem(int index, ItemStack stack) {
        if (index != 0) {
            setFrequency(index - 1, stack);
            return;
        }
        if (level != null && !level.isClientSide && boundTrain != null) {
            Train train = Create.RAILWAYS.trains.get(boundTrain);
            if (train != null && train.runtime.schedule != null) {
                HexieTrain.LOGGER.info("[HexieTrain][schedule] 车上已有时刻表，拒绝再放（请先取出）");
                return;
            }
        }
            HexieTrain.LOGGER.info("[HexieTrain][schedule] 放入时刻表：manual={} bound={}", manualMode, boundTrain != null);
            if (manualMode) { manualMode = false; HexieTrain.LOGGER.info("[HexieTrain][schedule] 放入时刻表 → 自动退出玩家操控"); }
            setScheduleSlot(stack);
            if (level != null && !level.isClientSide && boundTrain == null) bindTrainNow();   // 放入即建车
    }

    @Override
    public boolean stillValid(net.minecraft.world.entity.player.Player player) {
        return hasLevel() && player.distanceToSqr(
                worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5) <= 64.0;
    }

    @Override
    public void clearContent() {
        setScheduleSlot(ItemStack.EMPTY);
        for (int i = 0; i < FREQ_SLOTS; i++) {
            setFrequency(i, ItemStack.EMPTY);
        }
    }



}