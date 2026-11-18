package willow.train.hexietrain;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.loading.FMLPaths;

public final class TrainBindings {
   private TrainBindings() {
   }

   private static Path worldDir(ServerLevel level) {
      Path saves = FMLPaths.GAMEDIR.get().resolve("saves");

      try {
         Method m = level.getServer().getClass().getMethod("getWorldPath", Class.forName("net.minecraft.world.level.storage.LevelResource"));
         Object root = Class.forName("net.minecraft.world.level.storage.LevelResource").getMethod("valueOf", String.class).invoke(null, "ROOT");
         Object p = m.invoke(level.getServer(), root);
         if (p instanceof Path) {
            return (Path)p;
         }
      } catch (Throwable var6) {
      }

      try {
         Object wd = level.getServer().getClass().getMethod("getWorldData").invoke(level.getServer());
         if (wd.getClass().getMethod("getLevelName").invoke(wd) instanceof String s && !s.isBlank()) {
            return saves.resolve(s);
         }
      } catch (Throwable var5) {
      }

      return saves.resolve("hexie");
   }

   private static Path file(ServerLevel level) {
      return worldDir(level).resolve("hexie_train_bindings.txt");
   }

   private static Map<String, String> load(ServerLevel level) {
      Map<String, String> map = new LinkedHashMap<>();

      try {
         Path f = file(level);
         if (!Files.exists(f)) {
            return map;
         }

         for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            String s = line.trim();
            if (!s.isEmpty() && !s.startsWith("#")) {
               int i = s.indexOf(61);
               if (i > 0) {
                  map.put(s.substring(0, i).trim(), s.substring(i + 1).trim());
               }
            }
         }
      } catch (Throwable var7) {
      }

      return map;
   }

   private static void save(ServerLevel level, Map<String, String> map) {
      try {
         List<String> lines = new ArrayList<>();
         lines.add("# hexie train bindings & state");
         map.forEach((k, v) -> lines.add(k + "=" + v));
         Files.write(file(level), lines, StandardCharsets.UTF_8);
      } catch (Throwable var31) {
         HexieTrain.LOGGER.warn("[HexieTrain][bind] 写绑定文件失败: {}", var31.toString());
      }
   }

   public static UUID resolve(ServerLevel level, UUID structure) {
      if (level != null && structure != null) {
         Map<String, String> map = load(level);
         String key = "bind." + structure;
         String v = map.get(key);
         if (v != null) {
            try {
               return UUID.fromString(v);
            } catch (IllegalArgumentException var6) {
            }
         }

         UUID train = UUID.randomUUID();
         map.put(key, train.toString());
         save(level, map);
         HexieTrain.LOGGER.info("[HexieTrain][bind] 新建绑定 {} → {} （{}）", new Object[]{structure, train, file(level)});
         return train;
      } else {
         return null;
      }
   }

   public static void remove(ServerLevel level, UUID structure) {
      if (level != null && structure != null) {
         Map<String, String> map = load(level);
         String prefix = "state." + structure;
         boolean changed = map.keySet().removeIf(k -> k.equals("bind." + structure) || k.startsWith(prefix));
         if (changed) {
            save(level, map);
            HexieTrain.LOGGER.info("[HexieTrain][bind] 已从文件删除结构 {}", structure);
         }
      }
   }

   public static void writeState(ServerLevel level, UUID structure, double speed, double x, double y, double z, String mode, String dest, double leg) {
      if (level != null && structure != null) {
         Map<String, String> map = load(level);
         String p = "state." + structure + ".";
         map.put(p + "speed", String.format("%.2f", speed));
         map.put(p + "pos", String.format("%.1f, %.1f, %.1f", x, y, z));
         map.put(p + "mode", mode);
         map.put(p + "dest", dest == null ? "-" : dest);
         map.put(p + "leg", String.format("%.1f", leg));
         save(level, map);
      }
   }
}
