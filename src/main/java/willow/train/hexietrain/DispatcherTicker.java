package willow.train.hexietrain;

import java.util.List;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.EventBusSubscriber.Bus;
import net.neoforged.neoforge.event.tick.ServerTickEvent.Post;

@EventBusSubscriber(
   modid = "hexie_auto_simuroute",
   bus = Bus.GAME
)
public final class DispatcherTicker {

   /**
    * 破坏拦截。
    *
    * <p>{@code Block.playerWillDestroy} 只是"通知"，return 拦不住方块被拆；
    * 真正能否决的是 {@link net.neoforged.neoforge.event.level.BlockEvent.BreakEvent}。
    * 有主且不是归属者 ⇒ setCanceled(true) + 警告，归归属者所有。
    */
   @net.neoforged.bus.api.SubscribeEvent
   public static void hexie$onBreak(net.neoforged.neoforge.event.level.BlockEvent.BreakEvent event) {
      try {
         if (!(event.getLevel() instanceof net.minecraft.server.level.ServerLevel sl)) return;
         if (!(event.getState().getBlock() instanceof TrainDispatcherBlock)) return;
         if (!(sl.getBlockEntity(event.getPos()) instanceof TrainDispatcherBlockEntity be)) return;
         net.minecraft.world.entity.player.Player p = event.getPlayer();
         if (be.hasOwner() && !be.isOwner(p)) {
            event.setCanceled(true);
            p.displayClientMessage(net.minecraft.network.chat.Component
               .literal(be.denyText("破坏")).withStyle(net.minecraft.ChatFormatting.RED), false);
            HexieTrain.LOGGER.info("[owner] 拦下破坏：{} 试图拆 {} 的调度器 @{}",
               new Object[]{p.getName().getString(), be.ownerName(), event.getPos().toShortString()});
         }
      } catch (Throwable ignored) {
      }
   }
   private DispatcherTicker() {
   }

   @SubscribeEvent
   public static void onServerTick(Post event) {
      List<TrainDispatcherBlockEntity> dispatchers = List.copyOf(TrainDispatcherBlockEntity.activeDispatchers());

      // ===== 阶段 1：全体先拍物理快照（只写自己车的点，不做任何决策）=====
      // 先全员、再全员，是为了让任何一辆车决策时看到的其他车都是本 tick 的最新位置。
      // 以前是"一辆车 snapshot+决策全做完，再轮到下一辆"，跨车判断恒定差一 tick，
      // 车越快差得越远（20 m/s 时 1 m）。多车是硬要求，这条时序必须钉死。
      for (TrainDispatcherBlockEntity be : dispatchers) {
         try {
            be.snapshotPhysical();
         } catch (Throwable t) {
            HexieTrain.LOGGER.error("[HexieTrain] 快照异常 @{}", be.getBlockPos(), t);
         }
      }

      // ===== 阶段 2：全体决策 =====
      for (TrainDispatcherBlockEntity be : dispatchers) {
         try {
            be.serverTick();
         } catch (Throwable var4) {
            HexieTrain.LOGGER.error("[HexieTrain] 调度台 tick 异常 @{}", be.getBlockPos(), var4);
         }
      }
      // 残影清扫：每 2 秒一次。我们自己的列车 id 只许有一个 Train 对象活着；
      // 多出来的那个会继续被 Create tick、继续按它冻住的 occupiedSignalBlocks 占信号区间，
      // 结果是【永久占着几段区间，堵住别人的车】—— 这不是"判定时排除自己"能解决的问题，必须摘掉。
      if (++ghostSweepTicks % GHOST_SWEEP_INTERVAL == 0) {
         try {
            TrainBinder.sweepGhostTrains();
         } catch (Throwable t) {
            HexieTrain.LOGGER.warn("[reap] 清扫异常 {}", t.toString());
         }
      }
   }

   /** 残影清扫周期（tick）：40 = 2 秒 */
   private static final int GHOST_SWEEP_INTERVAL = 40;
   private static int ghostSweepTicks = 0;
}
