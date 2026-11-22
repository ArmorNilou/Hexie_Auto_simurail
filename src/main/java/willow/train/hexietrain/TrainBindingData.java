package willow.train.hexietrain;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedData.Factory;

public class TrainBindingData extends SavedData {
   public static final String NAME = "hexie_train_bindings";
   private final Map<UUID, UUID> assemblyToTrain = new HashMap<>();
   /** 车辆ID 分配器。落盘、单调递增、永不复用 —— 以前是进程内静态计数器，重启就从 0 重来，导致两个调度器拿到同一个 ID。 */
   private long nextVehicleId = 0L;

   public UUID trainOf(UUID assemblyId) {
      return assemblyId == null ? null : this.assemblyToTrain.get(assemblyId);
   }

   public void bind(UUID assemblyId, UUID trainId) {
      if (assemblyId != null && trainId != null) {
         this.assemblyToTrain.put(assemblyId, trainId);
         this.setDirty();
      }
   }

   public void unbind(UUID assemblyId) {
      if (assemblyId != null && this.assemblyToTrain.remove(assemblyId) != null) {
         this.setDirty();
      }
   }

   /** 分配一个新的车辆ID（15 位十进制上限，超了直接崩，不兜底）。 */
   public long allocateVehicleId() {
      if (this.nextVehicleId >= 1_000_000_000_000_000L) {
         throw new IllegalStateException("[HexieTrain] 车辆ID 超出 15 位十进制上限: " + this.nextVehicleId);
      }
      long id = this.nextVehicleId++;
      this.setDirty();
      HexieTrain.LOGGER.info("[roster] 分配车辆ID {}（落盘计数器）", id);
      return id;
   }

   public CompoundTag save(CompoundTag tag, Provider registries) {
      ListTag list = new ListTag();

      for (Entry<UUID, UUID> e : this.assemblyToTrain.entrySet()) {
         CompoundTag c = new CompoundTag();
         c.putUUID("Assembly", e.getKey());
         c.putUUID("Train", e.getValue());
         list.add(c);
      }

      tag.put("Bindings", list);
      tag.putLong("NextVehicleId", this.nextVehicleId);
      return tag;
   }

   public static TrainBindingData load(CompoundTag tag, Provider registries) {
      TrainBindingData data = new TrainBindingData();
      ListTag list = tag.getList("Bindings", 10);

      for (int i = 0; i < list.size(); i++) {
         CompoundTag c = list.getCompound(i);
         if (c.hasUUID("Assembly") && c.hasUUID("Train")) {
            data.assemblyToTrain.put(c.getUUID("Assembly"), c.getUUID("Train"));
         }
      }

      data.nextVehicleId = tag.getLong("NextVehicleId");
      return data;
   }

   public static TrainBindingData get(ServerLevel level) {
      return (TrainBindingData)level.getDataStorage().computeIfAbsent(new Factory<>(TrainBindingData::new, TrainBindingData::load, null), "hexie_train_bindings");
   }
}
