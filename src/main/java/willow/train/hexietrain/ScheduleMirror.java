package willow.train.hexietrain;

import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.schedule.Schedule;
import com.simibubi.create.content.trains.schedule.ScheduleEntry;
import com.simibubi.create.content.trains.schedule.condition.TimedWaitCondition;
import com.simibubi.create.content.trains.schedule.condition.ScheduleWaitCondition;
import com.simibubi.create.content.trains.schedule.destination.DestinationInstruction;
import com.simibubi.create.content.trains.schedule.destination.ScheduleInstruction;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.Level;

public final class ScheduleMirror {
   private ScheduleMirror.Arrival lastArrival = null;
   private int savedProgress = 0;
   private final List<ScheduleMirror.Stop> stops = new ArrayList<>();
   private String fingerprint = "";
   private CompoundTag blob;
   private static Provider providerRef;

   public void markArrived(String stationName, int entry, long tick) {
      this.lastArrival = new ScheduleMirror.Arrival(stationName, entry, tick);
   }

   public ScheduleMirror.Arrival lastArrival() {
      return this.lastArrival;
   }

   public int savedProgress() {
      return this.savedProgress;
   }

   /** 时刻表是否循环（Schedule.cyclic）。不循环时走到最后一站就结束，不回绕。 */
   public boolean cyclic() {
      return this.cyclic;
   }

   private boolean cyclic = true;

   public int size() {
      return this.stops.size();
   }

   public ScheduleMirror.Stop at(int index) {
      return index >= 0 && index < this.stops.size() ? this.stops.get(index) : null;
   }

   public String targetName(int index) {
      ScheduleMirror.Stop stop = this.at(index);
      return stop == null ? null : stop.stationName();
   }

   public String fingerprint() {
      return this.fingerprint;
   }

   public void clear() {
      this.stops.clear();
      this.fingerprint = "";
   }

   public String describe() {
      StringBuilder sb = new StringBuilder("[");

      for (int i = 0; i < this.stops.size(); i++) {
         if (i > 0) {
            sb.append(", ");
         }

         sb.append(this.stops.get(i).stationName());
      }

      return sb.append(']').toString();
   }

   public void restoreFingerprint(String value) {
      this.fingerprint = value == null ? "" : value;
   }

   public void save(CompoundTag parent) {
      try {
         ListTag list = new ListTag();

         for (ScheduleMirror.Stop stop : this.stops) {
            CompoundTag one = new CompoundTag();
            one.putString("Name", stop.stationName());
            one.putInt("Index", stop.index());
            list.add(one);
         }

         parent.put("MirrorStops", list);
         parent.putString("MirrorFp", this.fingerprint);
      } catch (Throwable var6) {
      }
   }

   public void load(CompoundTag parent) {
      try {
         if (!parent.contains("MirrorStops")) {
            return;
         }

         ListTag list = parent.getList("MirrorStops", 10);
         ArrayList<ScheduleMirror.Stop> built = new ArrayList<>();

         for (int i = 0; i < list.size(); i++) {
            CompoundTag one = list.getCompound(i);
            String name = one.getString("Name");
            if (name != null && !name.isEmpty()) {
               built.add(new ScheduleMirror.Stop(name, List.of(), one.getInt("Index")));
            }
         }

         if (!built.isEmpty()) {
            this.stops.clear();
            this.stops.addAll(built);
            this.fingerprint = parent.getString("MirrorFp");
         }
      } catch (Throwable var7) {
      }
   }

   public void capture(CompoundTag blob, int progress) {
      this.blob = blob == null ? null : blob.copy();
      this.savedProgress = Math.max(0, progress);
      this.stops.clear();
      this.fingerprint = "";
      if (blob != null) {
         try {
            Schedule schedule = Schedule.fromTag(providerRef, blob);
            this.refresh(schedule);
         } catch (Throwable var4) {
         }
      }
   }

   public CompoundTag blob() {
      return this.blob;
   }

   public void restore(CompoundTag savedBlob, int progress, int entryHint) {
      if (savedBlob != null && !savedBlob.isEmpty()) {
         this.capture(savedBlob, progress);
         this.savedProgress = entryHint;
      }
   }

   public static void provider(Provider p) {
      providerRef = p;
   }

   public boolean refresh(Schedule schedule) {
      this.cyclic = schedule != null && schedule.cyclic;   // 时刻表是否循环，由 Create 决定
      if (schedule == null) {
         if (this.stops.isEmpty() && "".equals(this.fingerprint)) {
            return false;
         } else {
            this.clear();
            return true;
         }
      } else {
         StringBuilder fp = new StringBuilder();
         List<ScheduleMirror.Stop> built = new ArrayList<>();

         try {
            int index = 0;

            for (ScheduleEntry entry : schedule.entries) {
               if (entry == null) {
                  index++;
               } else {
                  ScheduleInstruction name = entry.instruction;
                  if (name instanceof DestinationInstruction) {
                     DestinationInstruction destination = (DestinationInstruction)name;
                     String namex = destination.getFilter();
                     if (namex != null && !namex.isEmpty()) {
                        built.add(new ScheduleMirror.Stop(namex, entry.conditions, index));
                        fp.append(namex).append('|').append(index).append(';');
                     }
                  }

                  index++;
               }
            }
         } catch (Throwable var11) {
            return false;
         }

         try {
            this.savedProgress = schedule.savedProgress;
         } catch (Throwable var10) {
         }

         String next = fp.toString();
         if (next.equals(this.fingerprint)) {
            return false;
         } else {
            this.fingerprint = next;
            this.stops.clear();
            this.stops.addAll(built);
            return true;
         }
      }
   }

   public static record Arrival(String stationName, int entry, long tick) {
   }

   public static record Stop(String stationName, List<List<ScheduleWaitCondition>> conditions, int index) {
      /**
       * 我们自己判定，不调 Create 的 tickCompletion：
       *   时间类 TimedWaitCondition：参数取它的 totalWaitTicks()，计时用**我们自己的** gameTime 差
       *   其它类型：暂时仍问 Create（下一步逐个换成我们自己的实现）
       * groups 语义与 Create 一致：组内 AND、组间 OR（任一组全满足即满足）
       */
      public boolean satisfied(Level level, Train train, long arrivedTick, CompoundTag ctx) {
         if (this.conditions == null || this.conditions.isEmpty()) return true;
         long now = level.getGameTime();
         for (List<ScheduleWaitCondition> group : this.conditions) {
            if (group == null || group.isEmpty()) return true;
            boolean all = true;
            for (ScheduleWaitCondition condition : group) {
               if (condition == null) continue;
               boolean ok;
               if (condition instanceof TimedWaitCondition tw) {
                  long elapsed = arrivedTick <= 0L ? 0L : (now - arrivedTick);
                  ok = elapsed >= (long) tw.totalWaitTicks();
               } else {
                  try {
                     ok = condition.tickCompletion(level, train, ctx);
                  } catch (Throwable t) {
                     ok = false;
                  }
               }
               if (!ok) { all = false; break; }
            }
            if (all) return true;
         }
         return false;
      }

      public String waitingText(Level level, Train train, long arrivedTick, CompoundTag ctx) {
         if (this.conditions == null || this.conditions.isEmpty()) return "";
         long now = level.getGameTime();
         for (List<ScheduleWaitCondition> group : this.conditions) {
            if (group == null) continue;
            for (ScheduleWaitCondition condition : group) {
               if (condition == null) continue;
               if (condition instanceof TimedWaitCondition tw) {
                  long elapsed = arrivedTick <= 0L ? 0L : (now - arrivedTick);
                  long left = Math.max(0L, (long) tw.totalWaitTicks() - elapsed);
                  return String.format("等待中 还需 %.1f 秒", left / 20.0);
               }
               try {
                  if (!condition.tickCompletion(level, train, ctx)) {
                     MutableComponent text = condition.getWaitingStatus(level, train, ctx);
                     return text == null ? condition.getClass().getSimpleName() : text.getString();
                  }
               } catch (Throwable t2) {
               }
            }
         }
         return "";
      }
   }
}
