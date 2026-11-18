package willow.train.hexietrain.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import willow.train.hexietrain.BogeyConnectorItem;
import willow.train.hexietrain.HexieRegistries;
import willow.train.hexietrain.TrainDispatcherBlockEntity;

/**
 * 手持「转向架连接器」且已选中一个调度器时，在快捷栏上方画一行黄色小标题：
 *
 * <pre>
 *   已选中物理火车调度器（ID=0），已绑定转向架=2
 * </pre>
 *
 * <p>为什么不用聊天栏：选中/绑定是高频操作，聊天栏一刷就是一大坨，把真正要看的信息顶掉。
 *
 * <p>数据来源：车辆ID 读物品组件（服务端选中时写进去的，永远拿得到）；
 * 已绑定数量读<b>客户端那份</b> {@link TrainDispatcherBlockEntity} —— roster 存在方块实体 NBT 里，
 * 随 Create 的同步包一起过来，所以是真实值，不是猜的。方块没同步到就显示 {@code 已绑定转向架=?}。
 */
@EventBusSubscriber(modid = "hexie_auto_simuroute", value = Dist.CLIENT)
public final class ConnectorHudRenderer {

   /** 黄色（和原版 YELLOW 一致）。 */
   private static final int C_YELLOW = 0xFFFF55;

   private ConnectorHudRenderer() {
   }

   @SubscribeEvent
   public static void onRenderGui(RenderGuiEvent.Post event) {
      Minecraft mc = Minecraft.getInstance();
      if (mc.player == null || mc.level == null || mc.screen != null) {
         return;
      }

      ItemStack stack = heldConnector(mc);
      if (stack.isEmpty()) {
         return;
      }

      BlockPos pos = BogeyConnectorItem.selectedDispatcher(stack);
      if (pos == null) {
         return;
      }

      long vid = BogeyConnectorItem.selectedVehicleId(stack);
      String idText = vid >= 0L ? Long.toString(vid) : "?";
      int count = boundCount(mc, pos);
      String countText = count >= 0 ? Integer.toString(count) : "?";
      String text = "已选中物理火车调度器（ID=" + idText + "），已绑定转向架=" + countText;

      GuiGraphics g = event.getGuiGraphics();
      int w = mc.font.width(text);
      int x = (g.guiWidth() - w) / 2;
      int y = g.guiHeight() - 34;      // 快捷栏正上方
      g.fill(x - 4, y - 3, x + w + 4, y + 10, 0x66000000);
      g.drawString(mc.font, text, x, y, C_YELLOW, true);
   }

   /** 主手或副手拿着连接器。 */
   private static ItemStack heldConnector(Minecraft mc) {
      ItemStack main = mc.player.getMainHandItem();
      if (isConnector(main)) {
         return main;
      }
      ItemStack off = mc.player.getOffhandItem();
      return isConnector(off) ? off : ItemStack.EMPTY;
   }

   private static boolean isConnector(ItemStack stack) {
      return !stack.isEmpty() && stack.is(HexieRegistries.BOGEY_CONNECTOR.get());
   }

   /** 客户端那份调度器里的已绑数量；拿不到返回 −1。 */
   private static int boundCount(Minecraft mc, BlockPos pos) {
      if (mc.level == null || !mc.level.hasChunkAt(pos)) {
         return -1;
      }
      return mc.level.getBlockEntity(pos) instanceof TrainDispatcherBlockEntity be ? be.roster().size() : -1;
   }
}