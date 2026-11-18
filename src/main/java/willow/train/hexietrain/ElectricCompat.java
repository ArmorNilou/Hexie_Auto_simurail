package willow.train.hexietrain;

import com.george_vi.electroenergetics.content.electric_motor.ElectricMotorBlockEntity;
import com.george_vi.electroenergetics.mixin_interfaces.IPantographList;
import com.simibubi.create.content.trains.entity.Carriage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class ElectricCompat {
   private static final int SCAN_H = 16;
   private static final int SCAN_V = 8;

   private ElectricCompat() {
   }

   public static boolean attach(ServerLevel level, BlockPos anchor, Carriage carriage) {
      try {
         if (carriage instanceof IPantographList list) {
            boolean hasMotor = scanForMotor(level, anchor);
            if (hasMotor) {
               list.electroEnergetics$setElectricMotor(true);
               HexieTrain.LOGGER.info("[HexieTrain][ee] 已向电力学登记电机 @{}", anchor);
            }

            return hasMotor;
         } else {
            return false;
         }
      } catch (Throwable var51) {
         HexieTrain.LOGGER.warn("[HexieTrain][ee] 电力学兼容失败: {}", var51.toString());
         return false;
      }
   }

   private static boolean scanForMotor(ServerLevel level, BlockPos center) {
      MutableBlockPos cursor = new MutableBlockPos();

      for (int dx = -16; dx <= 16; dx++) {
         for (int dy = -8; dy <= 8; dy++) {
            for (int dz = -16; dz <= 16; dz++) {
               cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
               BlockEntity be = level.getBlockEntity(cursor);
               if (be instanceof ElectricMotorBlockEntity) {
                  return true;
               }
            }
         }
      }

      return false;
   }
}
