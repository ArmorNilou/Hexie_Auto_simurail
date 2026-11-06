package willow.train.hexietrain;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

/**
 * 物理结构 ↔ 虚拟列车的持久绑定，存放在世界存档目录：
 * <pre>{@code <世界>/hexie_train_bindings.txt}</pre>
 * 每行：{@code <结构UUID>=<列车UUID>}
 *
 * <p>一个结构只对应一个列车 UUID；破坏调度器时由调用方删除对应行。
 */
public final class TrainBindings {

    private TrainBindings() {}

    private static Path file(ServerLevel level) {
        java.nio.file.Path dir = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve("saves");
        return dir.resolve("hexie_train_bindings.txt");
    }

    private static Map<UUID, UUID> load(ServerLevel level) {
        Map<UUID, UUID> map = new LinkedHashMap<>();
        try {
            Path f = file(level);
            if (!Files.exists(f)) return map;
            for (String line : Files.readAllLines(f)) {
                int i = line.indexOf('=');
                if (i <= 0) continue;
                try {
                    map.put(UUID.fromString(line.substring(0, i).trim()),
                            UUID.fromString(line.substring(i + 1).trim()));
                } catch (IllegalArgumentException ignored) {}
            }
        } catch (Throwable ignored) {}
        return map;
    }

    private static void save(ServerLevel level, Map<UUID, UUID> map) {
        try {
            List<String> lines = new ArrayList<>();
            map.forEach((a, t) -> lines.add(a + "=" + t));
            Files.write(file(level), lines);
        } catch (Throwable t) {
            HexieTrain.LOGGER.warn("[HexieTrain][bind] 写绑定文件失败: {}", t.toString());
        }
    }

    /** 取该结构的列车 UUID；没有就新建一条并落盘。 */
    public static UUID resolve(ServerLevel level, UUID structure) {
        if (level == null || structure == null) return null;
        Map<UUID, UUID> map = load(level);
        UUID train = map.get(structure);
        if (train == null) {
            train = UUID.randomUUID();
            map.put(structure, train);
            save(level, map);
            HexieTrain.LOGGER.info("[HexieTrain][bind] 新建绑定 {} → {} （{}）", structure, train, file(level));
        }
        return train;
    }

    /** 破坏调度器时删除该结构的绑定。 */
    public static void remove(ServerLevel level, UUID structure) {
        if (level == null || structure == null) return;
        Map<UUID, UUID> map = load(level);
        if (map.remove(structure) != null) {
            save(level, map);
            HexieTrain.LOGGER.info("[HexieTrain][bind] 已从绑定文件删除结构 {}", structure);
        }
    }
}