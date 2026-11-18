package willow.train.hexietrain;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 第 3 块：物理火车调度器 = 定向齿轮换向器。
 *
 * 真值表直接抄自 TrainDispatcherBlockEntity.getRotationSpeedModifier(Direction)：
 *
 * <pre>
 *   if (face == getSourceFacing())      return  1.0F;   // 输入面恒 +1，跟左右无关
 *   if (COUNTERCLOCKWISE_POWERED &amp;&amp; CLOCKWISE_POWERED)  return  0.0F;   // 都激活 → 不输出
 *   if (COUNTERCLOCKWISE_POWERED)                   return  1.0F;   // 只激活左 → +1
 *   if (CLOCKWISE_POWERED)                  return -1.0F;   // 只激活右 → -1
 *   /* 都不激活 *&#47;                      return  0.0F;   // 不激活 → 不输出
 * </pre>
 *
 * 结论：想让这个换向器输出，必须**自行把左/右红石激活到目标状态**；
 * 不激活就一点转速都不给。所谓"驱动它"就是这一件事，没有第二个开关。
 *
 * 因为邻居红石会被 getPoweredState() 每帧重算覆盖，自行激活时必须同时登记
 * DRIVE_OVERRIDE，让 getPoweredState 原样返回、不再按邻居重算。
 * 释放（release）时取消登记，并立刻按邻居红石重算一次。
 *
 * 本类只负责"说清楚这块方块怎么输出"，不决定往哪边走（那是步骤③）。
 */
public final class DispatcherGearbox {

   /** 换向器的三种状态。 */
   public enum Face {
      NONE, COUNTERCLOCKWISE, CLOCKWISE;

      public static Face ofCode(int side) {
         return side == 1 ? COUNTERCLOCKWISE : (side == 2 ? CLOCKWISE : NONE);
      }

      public int code() {
         return this == COUNTERCLOCKWISE ? 1 : (this == CLOCKWISE ? 2 : 0);
      }

      public String label() {
         return this == COUNTERCLOCKWISE ? "逆时针激活(+1)" : (this == CLOCKWISE ? "顺时针激活(-1)" : "不激活(0)");
      }
   }

   public static final float FORWARD = 1.0F;
   public static final float REVERSE = -1.0F;
   public static final float NEUTRAL = 0.0F;

   private DispatcherGearbox() {}

   // ================================================================ 纯函数

   /** 真值表本体（不含输入面那条分支）。 */
   public static float modifierOf(boolean leftPowered, boolean rightPowered) {
      if (leftPowered && rightPowered) return NEUTRAL;
      if (leftPowered) return FORWARD;
      if (rightPowered) return REVERSE;
      return NEUTRAL;
   }

   /** 读当前方块状态对应的传动修正。 */
   public static float modifierOf(BlockState state) {
      if (state == null) return NEUTRAL;
      try {
         boolean ccw = state.getValue(TrainDispatcherBlock.COUNTERCLOCKWISE_POWERED);
         boolean cw = state.getValue(TrainDispatcherBlock.CLOCKWISE_POWERED);
         return modifierOf(ccw, cw);
      } catch (Throwable t) {
         return NEUTRAL;
      }
   }

   /** 当前方块处在哪一面。 */
   public static Face faceOf(BlockState state) {
      if (state == null) return Face.NONE;
      try {
         boolean ccw = state.getValue(TrainDispatcherBlock.COUNTERCLOCKWISE_POWERED);
         boolean cw = state.getValue(TrainDispatcherBlock.CLOCKWISE_POWERED);
         if (ccw && cw) return Face.NONE;   // 都激活 = 不输出
         if (ccw) return Face.COUNTERCLOCKWISE;
         if (cw) return Face.CLOCKWISE;
         return Face.NONE;
      } catch (Throwable t) {
         return Face.NONE;
      }
   }

   /** 此刻这块方块到底给不给转速。 */
   public static boolean outputs(BlockState state) {
      return modifierOf(state) != NEUTRAL;
   }

   /** 反过来：想输出某个方向，应该激活哪一面。 */
   public static Face faceFor(float modifier) {
      if (modifier > 0.0F) return Face.COUNTERCLOCKWISE;
      if (modifier < 0.0F) return Face.CLOCKWISE;
      return Face.NONE;
   }

   public static Face flip(Face f) {
      return f == Face.COUNTERCLOCKWISE ? Face.CLOCKWISE : (f == Face.CLOCKWISE ? Face.COUNTERCLOCKWISE : Face.NONE);
   }

   // ================================================================ 动作

   /**
    * 自行激活左右红石。
    * 这是驱动换向器的**唯一**手段：把 COUNTERCLOCKWISE_POWERED / CLOCKWISE_POWERED 写成目标值，
    * 再让动力学网络重新传播一次（detachKinetics 内部是 detach + attach）。
    *
    * @return 实际生效的面（方块不对或出错时返回 NONE）
    */
   public static Face engage(ServerLevel level, BlockPos pos, Face face) {
      if (level == null || pos == null || face == null) return Face.NONE;
      try {
         BlockState state = level.getBlockState(pos);
         if (!(state.getBlock() instanceof TrainDispatcherBlock block)) return Face.NONE;

         boolean ccw = face == Face.COUNTERCLOCKWISE;
         boolean cw = face == Face.CLOCKWISE;

         // 先登记覆盖，否则邻居红石一变就把我们写进去的状态重算掉
         TrainDispatcherBlock.setDriveOverride(pos, face != Face.NONE);

         boolean curCcw = state.getValue(TrainDispatcherBlock.COUNTERCLOCKWISE_POWERED);
         boolean curCw = state.getValue(TrainDispatcherBlock.CLOCKWISE_POWERED);
         if (curCcw == ccw && curCw == cw) {
            return face;
         }

         BlockState next = (BlockState) ((BlockState) state
            .setValue(TrainDispatcherBlock.COUNTERCLOCKWISE_POWERED, ccw))
            .setValue(TrainDispatcherBlock.CLOCKWISE_POWERED, cw);
         level.setBlock(pos, next, 3);

         try {
            block.detachKinetics(level, pos, true);
         } catch (Throwable t) {
         }
         return face;
      } catch (Throwable t) {
         HexieTrain.LOGGER.warn("[gear] engage({}) 失败 {}", face, t.toString());
         return Face.NONE;
      }
   }

   /** 交还控制权：取消覆盖，并立刻按邻居红石重算一次。 */
   public static void release(ServerLevel level, BlockPos pos) {
      if (level == null || pos == null) return;
      try {
         TrainDispatcherBlock.setDriveOverride(pos, false);
         BlockState state = level.getBlockState(pos);
         if (!(state.getBlock() instanceof TrainDispatcherBlock block)) return;
         BlockState powered = block.getPoweredState(level, state, pos);
         if (powered != null && powered != state) {
            level.setBlock(pos, powered, 3);
            try {
               block.detachKinetics(level, pos, true);
            } catch (Throwable t) {
            }
         }
      } catch (Throwable t) {
         HexieTrain.LOGGER.warn("[gear] release 失败 {}", t.toString());
      }
   }

   public static Face engaged(ServerLevel level, BlockPos pos) {
      if (level == null || pos == null) return Face.NONE;
      return faceOf(level.getBlockState(pos));
   }
}
