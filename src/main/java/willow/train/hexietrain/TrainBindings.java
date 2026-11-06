package willow.train.hexietrain;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.level.ServerLevel;

/**
 * 物理结构 ↔ 虚拟列车的持久绑定与实时状态，文件落在**单个世界存档目录**内：
 * <pre>{@code <世界>/hexie_train_bindings.txt}</pre>
 *
 * <p>格式（每行 key=value，节之间空行）：
 * <pre>
 * # 绑定
 * bind.<结构UUID>=<列车UUID>
 *
 * # 实时状态（节流：每 20 tick 写一次）
 * state.<结构UUID>.speed=6.70
 * state.<结构UUID>.pos=123.5, 64.0, -88.2
 * state.<结构UUID>.mode=AUTO|MANUAL|IDLE
 * state.<结构UUID>.dest=站名
 * state.<结构UUID>.leg=42.3
 * </pre>
 */
public final class TrainBindings {

    private TrainBindings() {}

    /** 世界存档根目录：反射取 getWorldPath(ROOT)，失败则退回 saves 根。 */
    private static Path worldDir(ServerLevel level) {
        try {
            var m = level.getServer().getClass().getMethod("getWorldPath",
                    Class.forName("net.minecraft.world.level.storage.LevelResource"));
            Object root = Class.forName("net.minecraft.world.level.storage.LevelResource")
                    .getMethod("valueOf", String.class).invoke(null, "ROOT");
            Object p = m.invoke(level.getServer(), root);
            if (p instanceof Path path) return path;
        } catch (Throwable ignored) {}
        java.nio.file.Path saves = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve("saves");
        try {
            Object wd = level.getServer().getClass().getMethod("getWorldData").invoke(level.getServer());
            String name = (String) wd.getClass().getMethod("getLevelName").invoke(wd);
            if (name != null && !name.isBlank()) return saves.resolve(name);
        } catch (Throwable ignored) {}
        return saves;
    }

    private static Path file(ServerLevel level) {
        return worldDir(level).resolve("hexie_train_bindings.txt");
    }

    private static Map<String, String> load(ServerLevel level) {
        Map<String, String> map = new LinkedHashMap<>();
        try {
            Path f = file(level);
            if (!Files.exists(f)) return map;
            for (String line : Files.readAllLines(f)) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) continue;
                int i = s.indexOf('=');
                if (i > 0) map.put(s.substring(0, i).trim(), s.substring(i + 1).trim());
            }
        } catch (Throwable ignored) {}
        return map;
    }

    private static void save(ServerLevel level, Map<String, String> map) {
        try {
            List<String> lines = new ArrayList<>();
            lines.add("# hexie train bindings & state");
            map.forEach((k, v) -> lines.add(k + "=" + v));
            Files.write(file(level), lines);
        } catch (Throwable t) {
            HexieTrain.LOGGER.warn("[HexieTrain][bind] 写绑定文件失败: {}", t.toString());
        }
    }

    /** 取该结构的列车 UUID；没有就新建一条并落盘。 */
    public static UUID resolve(ServerLevel level, UUID structure) {
        if (level == null || structure == null) return null;
        Map<String, String> map = load(level);
        String key = "bind." + structure;
        String v = map.get(key);
        if (v != null) {
            try { return UUID.fromString(v); } catch (IllegalArgumentException ignored) {}
        }
        UUID train = UUID.randomUUID();
        map.put(key, train.toString());
        save(level, map);
        HexieTrain.LOGGER.info("[HexieTrain][bind] 新建绑定 {} → {} （{}）", structure, train, file(level));
        return train;
    }

    /** 破坏调度器：删除该结构的绑定与状态。 */
    public static void remove(ServerLevel level, UUID structure) {
        if (level == null || structure == null) return;
        Map<String, String> map = load(level);
        String prefix = "state." + structure;
        boolean changed = map.keySet().removeIf(k -> k.equals("bind." + structure) || k.startsWith(prefix));
        if (changed) { save(level, map); HexieTrain.LOGGER.info("[HexieTrain][bind] 已从文件删除结构 {}", structure); }
    }

    /** 写入实时状态（调用方负责节流，例如每 20 tick 一次）。 */
    public static void writeState(ServerLevel level, UUID structure, double speed, double x, double y, double z,
                                  String mode, String dest, double leg) {
        if (level == null || structure == null) return;
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