package willow.train.hexietrain;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

public final class DispatcherStore {
   private static Path dir;

   private DispatcherStore() {
   }

   public static void init(MinecraftServer server) {
      try {
         Path base = null;

         try {
            Method m = MinecraftServer.class.getMethod("getWorldPath", LevelResource.class);
            base = (Path)m.invoke(server, LevelResource.ROOT);
         } catch (Throwable var3) {
         }

         if (base == null) {
            base = Path.of(".");
         }

         dir = base.resolve("hexietrain_dispatchers");
         Files.createDirectories(dir);
         HexieTrain.LOGGER.info("[store] 独立存档目录 → {}", dir);
      } catch (Throwable var4) {
         HexieTrain.LOGGER.warn("[store] 初始化目录失败 {}", var4.toString());
      }
   }

   private static Path file(UUID assemblyId) {
      return dir != null && assemblyId != null ? dir.resolve(assemblyId + ".nbt") : null;
   }

   public static CompoundTag load(UUID assemblyId) {
      Path p = file(assemblyId);
      if (p != null && Files.exists(p)) {
         try {
            return NbtIo.readCompressed(p, NbtAccounter.unlimitedHeap());
         } catch (Throwable var3) {
            HexieTrain.LOGGER.warn("[store] 读取失败 {} → {}", assemblyId, var3.toString());
            return null;
         }
      } else {
         return null;
      }
   }

   public static void save(UUID assemblyId, CompoundTag tag) {
      Path p = file(assemblyId);
      if (p != null && tag != null) {
         try {
            NbtIo.writeCompressed(tag, p);
         } catch (Throwable var4) {
            HexieTrain.LOGGER.warn("[store] 写入失败 {} → {}", assemblyId, var4.toString());
         }
      }
   }

   public static void delete(UUID assemblyId) {
      Path p = file(assemblyId);
      if (p != null) {
         try {
            if (Files.deleteIfExists(p)) {
               HexieTrain.LOGGER.info("[store] 已删除独立存档 {}", assemblyId);
            }
         } catch (Throwable var3) {
         }
      }
   }
}
