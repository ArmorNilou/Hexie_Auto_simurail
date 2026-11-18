package willow.train.hexietrain;

import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 转向架连接器。
 *
 * <p>用法：
 * <pre>
 *   潜行右键调度器   → 选中它（只出屏幕提示，不刷聊天栏）
 *   右键转向架       → 把这个转向架登记进选中的调度器
 *   潜行右键转向架   → 从选中的调度器里解绑
 *   右键另一个调度器 → 切换目标
 * </pre>
 *
 * <p>为什么选中要潜行：调度器方块的"打开界面"优先级最高，普通右键只会开 UI。
 *
 * <p><b>不碰 Simurail 的 connect()</b>：绑定表只存在我们自己的方块里，转向架那边什么都不写，
 * 所以随时可以解绑/清空，不会留下任何物理副作用。
 */
public class BogeyConnectorItem extends Item {

   /** 选中调度器位置存在物品自己的组件里，跟着物品走，跨维度/跨存档不会串。 */
   private static final String KEY_DISPATCHER = "HexieDispatcher";
   /** 选中时把车辆ID也记进物品：屏幕提示要显示它，而客户端不一定拿得到调度器方块。 */
   private static final String KEY_VEHICLE_ID = "HexieVehicleId";

   public BogeyConnectorItem(Item.Properties properties) {
      super(properties);
   }

   @Override
   public InteractionResult useOn(UseOnContext ctx) {
      Level level = ctx.getLevel();
      BlockPos clicked = ctx.getClickedPos();
      Player player = ctx.getPlayer();
      ItemStack stack = ctx.getItemInHand();

      // 选调度器这件事客户端也显示提示，但真正的写入只在服务端做
      if (level.isClientSide) {
         return InteractionResult.SUCCESS;
      }

      boolean sneak = player != null && player.isShiftKeyDown();
      BlockEntity be = level.getBlockEntity(clicked);

      // ---------- 情况 1：点了调度器 → 记住它，切换目标 ----------
      if (be instanceof TrainDispatcherBlockEntity dispatcher) {
         CompoundTag data = customTag(stack);
         data.putLong(KEY_DISPATCHER, clicked.asLong());
         dispatcher.roster().assignVehicleIdIfNeeded();
         data.putLong(KEY_VEHICLE_ID, dispatcher.roster().vehicleId());
         stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
         dispatcher.setChanged();
         // 一句聊天都不发：选中信息由 client/ConnectorHudRenderer 画在快捷栏上方。
         HexieTrain.LOGGER.info("[roster] 选中调度器 {} 车辆ID={} 已绑={}",
            clicked.toShortString(), dispatcher.roster().vehicleId(), dispatcher.roster().size());
         return InteractionResult.SUCCESS;
      }

      // ---------- 情况 2：点了转向架 → 登记 / 解绑 ----------
      if (be instanceof PhysicsBogeyBlockEntity) {
         BlockPos dispPos = selectedDispatcher(stack);
         if (dispPos == null) {
            msg(player, "先潜行右键一个物理火车调度器，再回来点转向架", ChatFormatting.RED);
            return InteractionResult.SUCCESS;
         }
         if (!(level.getBlockEntity(dispPos) instanceof TrainDispatcherBlockEntity dispatcher)) {
            msg(player, "之前选中的调度器不在了（" + dispPos.toShortString() + "），请重新选一个", ChatFormatting.RED);
            return InteractionResult.SUCCESS;
         }
         dispatcher.roster().assignVehicleIdIfNeeded();

         if (sneak) {
            boolean removed = dispatcher.roster().remove(clicked);
            dispatcher.setChanged();
            dispatcher.notifyUpdate();     // 让客户端那份 roster 立刻跟上（HUD/界面要用）
            syncVehicleId(stack, dispatcher);
            if (removed) {
               msg(player, "已解绑 " + clicked.toShortString() + "（还剩 " + dispatcher.roster().size() + " 个）",
                  ChatFormatting.GREEN);
               HexieTrain.LOGGER.info("[roster] {} 解绑转向架 {} → {}", dispPos.toShortString(),
                  clicked.toShortString(), dispatcher.roster().describe());
            } else {
               msg(player, "这个转向架本来就不在表里（共 " + dispatcher.roster().size() + " 个）", ChatFormatting.YELLOW);
            }
            return InteractionResult.SUCCESS;
         }

         boolean added = dispatcher.roster().add(clicked);
         dispatcher.setChanged();
         dispatcher.notifyUpdate();
         syncVehicleId(stack, dispatcher);
         if (added) {
            msg(player, "已绑定 " + clicked.toShortString() + "（共 " + dispatcher.roster().size() + " 个）",
               ChatFormatting.GREEN);
            HexieTrain.LOGGER.info("[roster] {} 绑定转向架 {} → {}", dispPos.toShortString(),
               clicked.toShortString(), dispatcher.roster().describe());
         } else {
            msg(player, "这个转向架已经在表里了（共 " + dispatcher.roster().size() + " 个）", ChatFormatting.YELLOW);
         }
         return InteractionResult.SUCCESS;
      }

      msg(player, "这个方块既不是调度器也不是物理转向架", ChatFormatting.RED);
      return InteractionResult.SUCCESS;
   }

   /** 物品里那份车辆ID跟不上（换了调度器/刚刚才分配）时补一次。 */
   private static void syncVehicleId(ItemStack stack, TrainDispatcherBlockEntity dispatcher) {
      long vid = dispatcher.roster().vehicleId();
      CompoundTag data = customTag(stack);
      if (!data.contains(KEY_VEHICLE_ID) || data.getLong(KEY_VEHICLE_ID) != vid) {
         data.putLong(KEY_VEHICLE_ID, vid);
         stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
      }
   }

   private static CompoundTag customTag(ItemStack stack) {
      return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
   }

   private static void msg(Player player, String text, ChatFormatting color) {
      if (player != null) {
         player.displayClientMessage(Component.literal(text).withStyle(color), false);
      }
   }

   /** 当前选中的调度器位置（没有就返回 null）。客户端渲染高亮框 / 屏幕提示都要用。 */
   public static BlockPos selectedDispatcher(ItemStack stack) {
      if (stack == null || stack.isEmpty() || !stack.has(DataComponents.CUSTOM_DATA)) {
         return null;
      }
      CompoundTag data = customTag(stack);
      if (!data.contains(KEY_DISPATCHER)) {
         return null;
      }
      long packed = data.getLong(KEY_DISPATCHER);
      return packed == 0L ? null : BlockPos.of(packed);
   }

   /** 当前选中调度器的车辆ID；没有或还没分配返回 −1。 */
   public static long selectedVehicleId(ItemStack stack) {
      if (stack == null || stack.isEmpty() || !stack.has(DataComponents.CUSTOM_DATA)) {
         return -1L;
      }
      CompoundTag data = customTag(stack);
      return data.contains(KEY_VEHICLE_ID) ? data.getLong(KEY_VEHICLE_ID) : -1L;
   }
}