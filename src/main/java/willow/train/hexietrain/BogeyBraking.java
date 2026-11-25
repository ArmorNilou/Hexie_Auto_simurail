package willow.train.hexietrain;

import java.util.HashMap;
import java.util.Map;

import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * 把调度器的制动指令写进<b>物理转向架</b>（阶段 4）。
 *
 * <h2>接口语义（从 Simurail 源码读出来的三条，缺一条就会出事）</h2>
 * <ol>
 *   <li><b>条目只活 3 拍。</b> {@code getRemoteBrakeStrength()} 里
 *       {@code if(currentTime - pair.leftLong() > 2) i.remove();} —— 所以只要值非 0，
 *       <b>必须每 tick 重写一次</b>，否则第 4 拍自动失效、车会突然松闸。</li>
 *   <li><b>发 0 等于删条目。</b> {@code setRemoteBrakeOverride(pos, 0)} 走的是
 *       {@code remoteBrakeOverrides.remove(pos)}，不是"写一个 0"。所以松闸要发一次 0，
 *       而不是每拍发一个很小的正数。</li>
 *   <li><b>接收端会平方。</b> {@code getRemoteBrakeStrength() = square(clamp(v/15,0,1))}。
 *       想让车拿到制动力 b，发出去的应该是 {@code 15*sqrt(b)}，否则 8/15 只会得到 0.28 制动力，
 *       远小于预期的 0.53。</li>
 * </ol>
 *
 * <h2>一条硬约束</h2>
 * {@code getBrakeStrength()} 是 {@code Floats.max(本地红石, 遥控, CC 覆盖, 导航制动)}。
 * 所以<b>远程制动只能让车更刹，不能让车更松</b>。要真的能松开，必须同时把本地红石和 CC 覆盖按住 ——
 * 这不是本类能解决的。
 *
 * <p>本类<b>不碰转向</b>：转向走 {@link BogeySteering} 的 {@code setCurrentPath}，
 * 制动走 {@code setRemoteBrakeOverride}，两者在 Simurail 里是两张独立的表，互不覆盖。
 */
public final class BogeyBraking {

   /** 每个转向架上一次写的值。用来避免"已经是 0 还每拍发 0"这种无意义操作。 */
   private final Map<BlockPos, Integer> lastWritten = new HashMap<>();

   // 制动力曲线<b>不在这里另造</b>。
   // 调度器侧本来就有：brakeCmd = round((-dv) / DV_FULL * 15)（TrainDispatcherBlockEntity:2423），
   // 外加 brakeFloor / 迟滞 / 站台与信号的分支。这里再套一层爬升 = 在已有曲线上又加一段延迟，
   // 短的制动脉冲会被吃掉、长的脉冲才爬得上去，物理车上正好表现成"钝一下—滑—再钝一下"。
   // 所以本类只做三件事：按拍续期、发 0 松闸、开方抵掉接收端平方。曲线从上游继承。

   /** 打日志限流：每个位置只报一次"第一次生效"。 */
   private final Map<BlockPos, Integer> firstApplied = new HashMap<>();

   /**
    * 把手闸指令写下去。{@code demand} 是 0..15 的<b>需求值</b>（已按设置里的上下限映射过）。
    *
    * <p>每 tick 调用。非 0 会每拍刷新续期；归零只在"上一次不是 0"时发一次。
    */
   public void tick(ServerLevel level, BlockPos anchor, BogeyRoster roster, int demand) {
      if (level == null || anchor == null || roster == null) {
         return;
      }
      // demand 就是上游那条曲线的当前值（0..15），原样下发，不再二次平滑。
      int shown = Math.max(0, Math.min(15, demand));
      int value = encode(shown);
      for (BlockPos pos : roster.positions()) {
         try {
            Integer last = this.lastWritten.get(pos);
            boolean wasZero = last == null || last == 0;
            if (value == 0 && !wasZero) {
               // 松闸：发一次 0（= 删条目），之后不再发
               this.write(level, pos, anchor, 0);
               this.lastWritten.put(pos, 0);
               HexieTrain.LOGGER.info("[bbrake] {} 松闸（删掉遥控制动条目）", pos.toShortString());
            } else if (value > 0) {
               // 非 0 必须每拍重写：条目 3 拍过期
               this.write(level, pos, anchor, value);
               this.lastWritten.put(pos, value);
               Integer seen = this.firstApplied.get(pos);
               if (seen == null || seen != shown) {
                  this.firstApplied.put(pos, shown);
                  HexieTrain.LOGGER.info("[bbrake] {} 遥控制动 爬升中={}/15 目标={}/15 ⇒ 发 {}/15（接收端平方后≈{}）",
                     pos.toShortString(), shown, demand, value,
                     String.format("%.2f", (value / 15.0) * (value / 15.0)));
               }
            }
         } catch (Throwable t) {
            HexieTrain.LOGGER.warn("[bbrake] {} 写遥控制动失败 {}", pos.toShortString(), t.toString());
         }
      }
   }

   /** 退出导航 / 手操 / 拆车：把所有绑定转向架的遥控制动条目清掉。 */
   public void releaseAll(ServerLevel level, BlockPos anchor, BogeyRoster roster) {
      if (level == null || anchor == null || roster == null) {
         return;
      }
      for (BlockPos pos : roster.positions()) {
         Integer last = this.lastWritten.get(pos);
         if (last != null && last == 0) {
            continue;
         }
         try {
            this.write(level, pos, anchor, 0);
            this.lastWritten.put(pos, 0);
         } catch (Throwable t) {
            HexieTrain.LOGGER.warn("[bbrake] {} 清遥控制动失败 {}", pos.toShortString(), t.toString());
         }
      }
   }

   private void write(ServerLevel level, BlockPos pos, BlockPos anchor, int value) {
      if (!level.hasChunkAt(pos)) {
         return;   // 区块没加载：不猜（同 BogeyBinding 的死锁教训）
      }
      if (!(level.getBlockEntity(pos) instanceof PhysicsBogeyBlockEntity bogey)) {
         return;
      }
      bogey.setRemoteBrakeOverride(anchor, value);
   }

   /**
    * 0..15 的需求值 → 遥控强度 0..15。
    *
    * <p>接收端会平方，所以这里先开方抵掉：想让车拿到 {@code demand/15} 的制动力，
    * 要发 {@code 15*sqrt(demand/15)}。
    */
   public static int encode(int demand) {
      if (demand <= 0) {
         return 0;
      }
      double b = Math.min(1.0, demand / 15.0);
      return (int)Math.round(15.0 * Math.sqrt(b));
   }
}
