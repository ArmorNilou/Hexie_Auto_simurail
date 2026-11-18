package willow.train.hexietrain;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

public final class SubLevelSuspend {
   public static final SubLevelLoadingTicketType<BlockPos> PROXY_TICKET = SubLevelLoadingTicketType.create(
      ResourceLocation.fromNamespaceAndPath("hexie_auto_simuroute", "proxy_loading"), BlockPos.CODEC
   );

   private SubLevelSuspend() {
   }

   public static void init() {
      HexieTrain.LOGGER.info("[HexieTrain] 子结构票据类型已注册: {}", PROXY_TICKET.name());
   }

   public static ServerSubLevel containing(ServerLevel level, BlockPos pos) {
      try {
         return Sable.HELPER.getContaining(level, pos) instanceof ServerSubLevel server ? server : null;
      } catch (Throwable var41) {
         return null;
      }
   }

   public static boolean resume(ServerLevel level, BlockPos dispatcherPos) {
      ServerSubLevel sub = containing(level, dispatcherPos);
      if (sub == null) {
         return false;
      } else {
         try {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            return container == null ? false : container.addForceLoadTicket(sub, PROXY_TICKET, dispatcherPos.immutable());
         } catch (Throwable var41) {
            HexieTrain.LOGGER.warn("[HexieTrain][ticket] 挂票据失败: {}", var41.toString());
            return false;
         }
      }
   }

   public static boolean suspend(ServerLevel level, BlockPos dispatcherPos) {
      ServerSubLevel sub = containing(level, dispatcherPos);
      if (sub == null) {
         return false;
      } else {
         try {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            return container == null ? false : container.removeForceLoadTicket(sub, PROXY_TICKET, dispatcherPos.immutable());
         } catch (Throwable var41) {
            HexieTrain.LOGGER.warn("[HexieTrain][ticket] 撤票据失败: {}", var41.toString());
            return false;
         }
      }
   }

   public static Vec3 worldCenter(ServerLevel level, BlockPos dispatcherPos) {
      ServerSubLevel sub = containing(level, dispatcherPos);
      if (sub == null) {
         return Vec3.atCenterOf(dispatcherPos);
      } else {
         try {
            Vector3d p = sub.logicalPose().position();
            return new Vec3(p.x, p.y, p.z);
         } catch (Throwable var41) {
            return Vec3.atCenterOf(dispatcherPos);
         }
      }
   }

   public static Vector3d velocityAt(ServerLevel level, BlockPos localPos) {
      ServerSubLevel sub = containing(level, localPos);
      if (sub == null) {
         return new Vector3d();
      } else {
         try {
            Vector3d local = JOMLConversion.toJOML(localPos.getCenter());
            return sub.logicalPose().transformPosition(local, new Vector3d()).sub(sub.lastPose().transformPosition(local, new Vector3d())).mul(20.0);
         } catch (Throwable var41) {
            return new Vector3d();
         }
      }
   }
}
