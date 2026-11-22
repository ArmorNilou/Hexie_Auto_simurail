package willow.train.hexietrain;

import com.simibubi.create.AllItems;
import com.simibubi.create.content.kinetics.RotationPropagator;
import com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.RotatedPillarKineticBlock;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.BlockBehaviour.Properties;
import net.minecraft.world.level.block.state.StateDefinition.Builder;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.extensions.IPlayerExtension;

public class TrainDispatcherBlock extends DirectionalAxisKineticBlock implements IBE<TrainDispatcherBlockEntity> {
   /** 自动驱动接管面状态的方块位置集合（按方块区分，避免多台调度器串台） */
   private static final java.util.Set<BlockPos> DRIVE_OVERRIDE_POS = java.util.concurrent.ConcurrentHashMap.newKeySet();

   public static void setDriveOverride(BlockPos pos, boolean on) {
      if (pos == null) return;
      if (on) DRIVE_OVERRIDE_POS.add(pos.immutable()); else DRIVE_OVERRIDE_POS.remove(pos);
   }

   public static boolean driveOverride(BlockPos pos) { return pos != null && DRIVE_OVERRIDE_POS.contains(pos); }
   /**
    * 面的命名，2026-11-17 01:36 实测定死，别再改：
    *
    * <pre>
    *   getCounterClockwiseDirection(state) = FACING 本身                     那一面 → 叫"逆时针"（原 left）
    *   getClockwiseDirection(state)        = FACING.getOpposite()（相对面）  那一面 → 叫"顺时针"（原 right）
    * </pre>
    *
    * 依据：[probe] 里 bsL=true / bsR=false，现场在换向器上激活的正是这一面，用户指定称它为"逆时针"。
    *
    * <p><b>注意</b>：blockstate 的属性字符串仍然是 "left_powered" / "right_powered" —— 那是存档里的
    * 键名，改了会让老存档的方块状态失配。所以只改 Java 标识符，字符串保持不动。
    *
    * <p>这两个 boolean 是**驱动面**（换向器接哪一面）的状态，不是红石输入，也不发红石。
    * 道岔转向**已经不是**红石了：逐转向架由 BogeySteering 直接写 Simurail 遥控值（0.62.0 起）。
    */
   public static final BooleanProperty COUNTERCLOCKWISE_POWERED = BooleanProperty.create("left_powered");
   public static final BooleanProperty CLOCKWISE_POWERED = BooleanProperty.create("right_powered");

   public TrainDispatcherBlock(Properties properties) {
      super(properties);
      this.registerDefaultState((BlockState)((BlockState)this.defaultBlockState().setValue(COUNTERCLOCKWISE_POWERED, false)).setValue(CLOCKWISE_POWERED, false));
   }

   protected void createBlockStateDefinition(Builder<Block, BlockState> builder) {
      builder.add(new Property[]{COUNTERCLOCKWISE_POWERED});
      builder.add(new Property[]{CLOCKWISE_POWERED});
      super.createBlockStateDefinition(builder);
   }

   public BlockState getStateForPlacement(BlockPlaceContext context) {
      Direction nearest = context.getNearestLookingDirection();
      boolean shift = context.getPlayer() != null && context.getPlayer().isShiftKeyDown();
      Axis preferredAxis = RotatedPillarKineticBlock.getPreferredAxis(context);
      boolean axisAlongFirst = false;
      Direction facing;
      if (preferredAxis != null && preferredAxis != nearest.getAxis() && !shift) {
         facing = nearest;
         if (preferredAxis == Axis.X) {
            axisAlongFirst = true;
         } else if (preferredAxis == Axis.Y && nearest.getAxis() == Axis.X) {
            axisAlongFirst = true;
         }
      } else if (nearest.getAxis().isHorizontal()) {
         facing = nearest.getCounterClockWise();
         if (nearest.getAxis() == Axis.X) {
            axisAlongFirst = true;
         }
      } else {
         Direction horizontal = context.getHorizontalDirection();
         facing = horizontal.getCounterClockWise();
         if (horizontal.getAxis() == Axis.Z) {
            axisAlongFirst = true;
         }
      }

      if (shift) {
         facing = facing.getOpposite();
      }

      BlockState state = (BlockState)((BlockState)this.defaultBlockState().setValue(FACING, facing)).setValue(AXIS_ALONG_FIRST_COORDINATE, axisAlongFirst);
      return this.getPoweredState(context.getLevel(), state, context.getClickedPos());
   }

   public Direction getCounterClockwiseDirection(BlockState state) {
      return (Direction)state.getValue(FACING);
   }

   public Direction getClockwiseDirection(BlockState state) {
      return this.getCounterClockwiseDirection(state).getOpposite();
   }

   public BlockState getPoweredState(Level level, BlockState state, BlockPos pos) {
      if (driveOverride(pos)) {
         return state;
      } else {
         Direction ccw = this.getCounterClockwiseDirection(state);
         Direction cw = this.getClockwiseDirection(state);
          BlockPos lp = pos.relative(ccw);
          BlockPos rp = pos.relative(cw);
          // 邻居方块在未加载区块时绝不能让 level 去加载（C2ME 下 getChunk 的 join() 会把服务器
          // 线程等死，2026-11-17 01:14 进不去世界就是这一类）。判不了就保持现状，不猜。
          if (!level.hasChunkAt(lp) || !level.hasChunkAt(rp)) {
             return state;
          }
          int counterClockwiseSignal = level.getSignal(lp, ccw);
          int clockwiseSignal = level.getSignal(rp, cw);
         return (BlockState)((BlockState)state.setValue(COUNTERCLOCKWISE_POWERED, counterClockwiseSignal > 0)).setValue(CLOCKWISE_POWERED, clockwiseSignal > 0);
      }
   }

   public void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock, BlockPos neighborPos, boolean movedByPiston) {
      if (!level.isClientSide) {
         if (level.getBlockEntity(pos) instanceof TrainDispatcherBlockEntity be) {
            // 必须走和 getPoweredState 同一套定义（FACING 及其相对面），否则红石读的是另外两面；
            // 而且 FACING 竖直时 getClockWise() 会抛异常。
            Direction toCcw = this.getCounterClockwiseDirection(state);
            Direction toCw = this.getClockwiseDirection(state);
            BlockPos lp2 = pos.relative(toCcw);
            BlockPos rp2 = pos.relative(toCw);
            // 邻居未加载就不读：level.getSignal 会强制加载区块，C2ME 下 join() 等自己会挂死
            if (level.hasChunkAt(lp2) && level.hasChunkAt(rp2)) {
               be.updateExternalInput(level.getSignal(lp2, toCcw), level.getSignal(rp2, toCw));
            }
         }

         boolean wasCcw = (Boolean)state.getValue(COUNTERCLOCKWISE_POWERED);
         boolean wasCw = (Boolean)state.getValue(CLOCKWISE_POWERED);
         BlockState newState = this.getPoweredState(level, state, pos);
         if (wasCcw != (Boolean)newState.getValue(COUNTERCLOCKWISE_POWERED) || wasCw != (Boolean)newState.getValue(CLOCKWISE_POWERED)) {
            level.setBlock(pos, newState, 3);
            this.detachKinetics(level, pos, true);
         }
      }
   }

   public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
      if (level.getBlockEntity(pos) instanceof KineticBlockEntity kbe) {
         RotationPropagator.handleAdded(level, pos, kbe);
      }
   }

   public InteractionResult onWrenched(BlockState state, UseOnContext context) {
      InteractionResult result = super.onWrenched(state, context);
      if (result.consumesAction() && !context.getLevel().isClientSide) {
         this.detachKinetics(context.getLevel(), context.getClickedPos(), true);
      }

      return result;
   }

   public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
      Level level = context.getLevel();
      if (!level.isClientSide) {
         level.destroyBlock(context.getClickedPos(), true, context.getPlayer());
      }

      return InteractionResult.SUCCESS;
   }

   /**
    * 空手右键 ⇒ 开界面（命名走 {@link TrainNaming} 的事件，不在这里）。
    */
   public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
      if (!AllItems.WRENCH.isIn(player.getMainHandItem()) && !AllItems.WRENCH.isIn(player.getOffhandItem())) {
         if (!level.isClientSide && level.getBlockEntity(pos) instanceof TrainDispatcherBlockEntity be) {
            if (player instanceof IPlayerExtension ext) {
               ext.openMenu(be, pos);
            } else {
               player.openMenu(be);
            }
         }

         return InteractionResult.sidedSuccess(level.isClientSide);
      } else {
         return InteractionResult.PASS;
      }
   }

   public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
      try {
         if (!level.isClientSide && level.getBlockEntity(pos) instanceof TrainDispatcherBlockEntity be) {
            try {
               be.forgetRecordOnBreak();
            } catch (Throwable var7) {
            }

            be.dropRebuiltSchedule();
            be.dissolve();
            // 调度器被破坏 ⇒ 只清这一个调度器自己的绑定表（表在 BE 的 NBT 里，按调度器隔离）。
            // 顺带把换向器和每根转向架的遥控条目一起松开：方块没了就没人 tick 它，
            // 换向器会一直保持 engage 的那个面，车就没人管地继续被推着走。
            be.clearBindings("调度器被破坏");

            for (int i = 1; i < TrainDispatcherContainer.SIZE; i++) {
               be.setSlot(i, ItemStack.EMPTY);
            }

            HexieTrain.LOGGER.info("[HexieTrain] 调度台被破坏 @{}，内容物已掉落", pos);
         }
      } catch (Throwable var81) {
         HexieTrain.LOGGER.error("[HexieTrain] 破坏回调异常（已忽略，保证方块能被拆掉）", var81);
      }

      return super.playerWillDestroy(level, pos, state, player);
   }

   public Class<TrainDispatcherBlockEntity> getBlockEntityClass() {
      return TrainDispatcherBlockEntity.class;
   }

   public BlockEntityType<? extends TrainDispatcherBlockEntity> getBlockEntityType() {
      return (BlockEntityType<? extends TrainDispatcherBlockEntity>)HexieRegistries.TRAIN_DISPATCHER_BE.get();
   }

   public void detachKinetics(Level level, BlockPos pos, boolean immediate) {
      if (level.getBlockEntity(pos) instanceof KineticBlockEntity kbe) {
         try {
            kbe.detachKinetics();
            kbe.attachKinetics();
         } catch (Throwable var6) {
            HexieTrain.LOGGER.warn("[HexieTrain][kinetics] 重算失败: {}", var6.toString());
         }
      }
   }
}
