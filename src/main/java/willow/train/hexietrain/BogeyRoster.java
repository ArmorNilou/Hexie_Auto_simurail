package willow.train.hexietrain;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * 转向架绑定表（调度器级）。
 *
 * <p><b>不用 Simurail 的 connector。</b> 他们的绑定要求两个转向架"面对面、两头两两相连"，
 * 车厢一多就是灾难。这里改成"调度器自己记一份名单"，控制时逐个转向架直接下命令。
 *
 * <p>每一条只记 <b>转向架的 BlockPos</b>：Simurail 自己也是这么存的
 * （{@code connectionFront = other.getBlockPos()}），而且 {@code getBlockPos()} 返回的
 * 是主世界里的 plot 坐标（例如 20481033,128,20481031），全局唯一，不用再带子层 UUID。
 */
public final class BogeyRoster {

   /** 一名成员。车辆ID ∈ long，能容纳 15 位十进制数字（上限 9.2e18，19 位）。 */
   public static final class Entry {
      public final BlockPos pos;
      public final long vehicleId;

      public Entry(BlockPos pos, long vehicleId) {
         this.pos = pos;
         this.vehicleId = vehicleId;
      }
   }

   private final List<BlockPos> bogeys = new ArrayList<>();
   /** -1 = 还没分配。 */
   private long vehicleId = -1L;

   // ================================================================ 基本操作

   public int size() {
      return this.bogeys.size();
   }

   public List<BlockPos> positions() {
      return this.bogeys;
   }

   public boolean contains(BlockPos pos) {
      return pos != null && this.bogeys.contains(pos);
   }

   /**
    * 加一个转向架。<b>幂等</b>：已有的直接返回 false，不会重复，也不会改变已有顺序。
    */
   public boolean add(BlockPos pos) {
      if (pos == null || this.bogeys.contains(pos)) {
         return false;
      }
      this.bogeys.add(pos.immutable());
      return true;
   }

   public boolean remove(BlockPos pos) {
      return pos != null && this.bogeys.remove(pos);
   }

   public void clear() {
      this.bogeys.clear();
   }

   /**
    * 一键清除绑定：表归零，车辆ID 也一起作废 —— 清空之后就是一个还没分配身份的新车，
    * 重新绑定时会拿一个新的 ID，避免两个不同编组共用一个车辆ID。
    */
   /**
    * 由一个<b>落盘的全局计数器</b>分配好的 ID 写进来（见 TrainBindingData.allocateVehicleId）。
    * 以前这里是进程内静态计数器，重启就从 0 重来，两个调度器会拿到同一个 ID。
    */
   public void assignVehicleId(long id) {
      if (id < 0L || id >= 1_000_000_000_000_000L) {
         throw new IllegalStateException("[HexieTrain] 车辆ID 非法: " + id);
      }
      this.vehicleId = id;
      HexieTrain.LOGGER.info("[roster] 写入车辆ID {}", id);
   }

   public void clearBindings() {
      this.bogeys.clear();
      this.vehicleId = -1L;
   }

   public long vehicleId() {
      return this.vehicleId;
   }


   // ================================================================ 排序与命名

   /**
    * 按<b>列车主轴</b>排好序的副本：取 X / Z 里跨度大的那个当主轴。
    *
    * <p>不假设"一节车厢两个转向架" —— 车厢边界在 Simurail 里根本不存在，
    * 所以序号就是"沿主轴的先后"，从 0 开始。
    */
   public List<BlockPos> ordered() {
      List<BlockPos> out = new ArrayList<>(this.bogeys);
      if (out.size() < 2) {
         return out;
      }
      int minX = Integer.MAX_VALUE;
      int maxX = Integer.MIN_VALUE;
      int minZ = Integer.MAX_VALUE;
      int maxZ = Integer.MIN_VALUE;
      for (BlockPos p : out) {
         minX = Math.min(minX, p.getX());
         maxX = Math.max(maxX, p.getX());
         minZ = Math.min(minZ, p.getZ());
         maxZ = Math.max(maxZ, p.getZ());
      }
      final boolean byX = (maxX - minX) >= (maxZ - minZ);
      out.sort((a, b) -> Integer.compare(
         byX ? a.getX() : a.getZ(),
         byX ? b.getX() : b.getZ()));
      return out;
   }

   /**
    * 显示名：{@code 车辆ID + "#" + 序号}，序号按主轴排序。
    *
    * <p>名字是派生的，不写进方块 NBT。铭牌（自定义名）等确认语义后再接进来。
    */
   public String nameOf(BlockPos pos) {
      int i = this.ordered().indexOf(pos);
      return "车" + this.vehicleId + "#" + i;
   }

   /** 一行摘要，给日志和 UI 用。 */
   public String describe() {
      StringBuilder sb = new StringBuilder();
      sb.append("车辆ID=").append(this.vehicleId).append(" 转向架=").append(this.bogeys.size());
      List<BlockPos> order = this.ordered();
      for (int i = 0; i < order.size(); i++) {
         sb.append(" [").append(i).append(']').append(order.get(i).toShortString());
      }
      return sb.toString();
   }

   // ================================================================ 存档

   public CompoundTag save() {
      CompoundTag tag = new CompoundTag();
      tag.putLong("VehicleId", this.vehicleId);
      ListTag list = new ListTag();
      for (BlockPos p : this.bogeys) {
         CompoundTag e = new CompoundTag();
         e.putLong("Pos", p.asLong());
         list.add(e);
      }
      tag.put("Bogeys", list);
      return tag;
   }

   public void load(CompoundTag tag) {
      this.bogeys.clear();
      this.vehicleId = tag.contains("VehicleId") ? tag.getLong("VehicleId") : -1L;
      ListTag list = tag.getList("Bogeys", Tag.TAG_COMPOUND);
      for (int i = 0; i < list.size(); i++) {
         CompoundTag e = list.getCompound(i);
         if (e.contains("Pos")) {
            this.bogeys.add(BlockPos.of(e.getLong("Pos")));
         }
      }
   }

}
