package willow.train.hexietrain;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 物理结构 ↔ 虚拟列车的持久绑定。
 *
 * <p>独立世界存档文件：{@code <世界>/data/hexie_train_bindings.dat}
 * 一个子结构 UUID 只对应一个列车 UUID，建成即写死；
 * 移除方块、拆散结构、退出重进都不会丢，只有显式重置才清除。
 *
 * <p>关键收益：trainId 恒定 → Create 的 RAILWAYS 存档能按同一 id 恢复
 * 时刻表与 currentEntry，游标不再因重建归零。
 */
public class TrainBindingData extends SavedData {

    public static final String NAME = "hexie_train_bindings";

    private final Map<UUID, UUID> assemblyToTrain = new HashMap<>();

    public UUID trainOf(UUID assemblyId) {
        return assemblyId == null ? null : assemblyToTrain.get(assemblyId);
    }

    public void bind(UUID assemblyId, UUID trainId) {
        if (assemblyId == null || trainId == null) return;
        assemblyToTrain.put(assemblyId, trainId);
        setDirty();
    }

    public void unbind(UUID assemblyId) {
        if (assemblyId != null && assemblyToTrain.remove(assemblyId) != null) setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (var e : assemblyToTrain.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putUUID("Assembly", e.getKey());
            c.putUUID("Train", e.getValue());
            list.add(c);
        }
        tag.put("Bindings", list);
        return tag;
    }

    public static TrainBindingData load(CompoundTag tag, HolderLookup.Provider registries) {
        TrainBindingData data = new TrainBindingData();
        ListTag list = tag.getList("Bindings", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag c = list.getCompound(i);
            if (c.hasUUID("Assembly") && c.hasUUID("Train"))
                data.assemblyToTrain.put(c.getUUID("Assembly"), c.getUUID("Train"));
        }
        return data;
    }

    public static TrainBindingData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(TrainBindingData::new,
                        (t, r) -> load(t, r), null),
                NAME);
    }
}