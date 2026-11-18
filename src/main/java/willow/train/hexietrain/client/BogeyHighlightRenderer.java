package willow.train.hexietrain.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.ryanhcode.sable.Sable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import willow.train.hexietrain.BogeyConnectorItem;
import willow.train.hexietrain.TrainDispatcherBlockEntity;

/**
 * 绑定状态高亮框（学机械动力的标定样式）。
 *
 * <p>手持转向架连接器时：
 * <ul>
 *   <li><b>绿色</b>：当前选中的调度器 + 它绑定表里的每一个转向架</li>
 *   <li><b>黄色</b>：准星指着、但还没绑进这个调度器的方块</li>
 * </ul>
 *
 * <p>关键点：调度器和转向架都活在 Sable 子层里，{@code getBlockPos()} 给的是 plot 坐标
 * （两千万那个量级），直接画会画在看不见的地方。所以先用
 * {@code Sable.HELPER.projectOutOfSubLevel(level, pos)} 换算到世界坐标再画。
 */
@EventBusSubscriber(modid = "hexie_auto_simuroute", bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class BogeyHighlightRenderer {

   private static final float GREEN_R = 0.35F;
   private static final float GREEN_G = 0.90F;
   private static final float GREEN_B = 0.45F;
   private static final float YELLOW_R = 0.95F;
   private static final float YELLOW_G = 0.78F;
   private static final float YELLOW_B = 0.20F;
   private static final float ALPHA = 0.85F;
   /** 略微向外膨胀，避免和方块棱边完全重合（Create 的描边也这么干）。 */
   private static final double INFLATE = 0.002D;

   private BogeyHighlightRenderer() {
   }

   @SubscribeEvent
   public static void onRenderLevel(RenderLevelStageEvent event) {
      if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
         return;
      }
      Minecraft mc = Minecraft.getInstance();
      if (mc.level == null || mc.player == null) {
         return;
      }

      ItemStack held = mc.player.getMainHandItem();
      if (!(held.getItem() instanceof BogeyConnectorItem)) {
         held = mc.player.getOffhandItem();
         if (!(held.getItem() instanceof BogeyConnectorItem)) {
            return;
         }
      }

      PoseStack pose = event.getPoseStack();
      Vec3 cam = event.getCamera().getPosition();
      MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
      VertexConsumer lines = buffers.getBuffer(RenderType.lines());

      pose.pushPose();
      pose.translate(-cam.x, -cam.y, -cam.z);

      boolean drewGreen = false;

      // ---------- 绿色：选中的调度器 + 它表里的转向架 ----------
      BlockPos selected = BogeyConnectorItem.selectedDispatcher(held);
      TrainDispatcherBlockEntity dispatcher = null;
      if (selected != null && mc.level.getBlockEntity(selected) instanceof TrainDispatcherBlockEntity be) {
         dispatcher = be;
         draw(pose, lines, event, selected, GREEN_R, GREEN_G, GREEN_B);
         drewGreen = true;
         for (BlockPos bogeyPos : be.roster().positions()) {
            draw(pose, lines, event, bogeyPos, GREEN_R, GREEN_G, GREEN_B);
         }
      }

      // ---------- 黄色：准星指着但没绑上的 ----------
      HitResult hit = mc.hitResult;
      if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
         BlockPos look = bhr.getBlockPos();
         boolean isBogey = mc.level.getBlockEntity(look) instanceof com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;
         boolean isDispatcher = mc.level.getBlockEntity(look) instanceof TrainDispatcherBlockEntity;
         boolean alreadyGreen = dispatcher != null
            && (look.equals(selected) || dispatcher.roster().positions().contains(look));
         if ((isBogey || isDispatcher) && !alreadyGreen) {
            draw(pose, lines, event, look, YELLOW_R, YELLOW_G, YELLOW_B);
         }
      }

      pose.popPose();
      if (drewGreen) {
         buffers.endBatch(RenderType.lines());
      }
   }

   /** 把子层里的方块位置换算成世界坐标，画一个 1×1×1 的描边盒。 */
   private static void draw(PoseStack pose, VertexConsumer lines, RenderLevelStageEvent event,
                            BlockPos pos, float r, float g, float b) {
      try {
         Vec3 world = Sable.HELPER.projectOutOfSubLevel(Minecraft.getInstance().level, Vec3.atCenterOf(pos));
         AABB box = new AABB(
            world.x - 0.5D - INFLATE, world.y - 0.5D - INFLATE, world.z - 0.5D - INFLATE,
            world.x + 0.5D + INFLATE, world.y + 0.5D + INFLATE, world.z + 0.5D + INFLATE);
         LevelRenderer.renderLineBox(pose, lines, box, r, g, b, ALPHA);
      } catch (Throwable t) {
         // 单个方块算不出来不该影响整帧
      }
   }
}