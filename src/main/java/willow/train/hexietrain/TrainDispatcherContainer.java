package willow.train.hexietrain;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public class TrainDispatcherContainer implements Container {
   public static final int SLOT_SCHEDULE = 0;
   public static final int SLOT_FREQ_BASE = 1;
   public static final int SIZE = 1 + TrainDispatcherBlockEntity.FREQ_SLOTS;
   private final TrainDispatcherBlockEntity be;

   public TrainDispatcherContainer(TrainDispatcherBlockEntity be) {
      this.be = be;
   }

   public int getContainerSize() {
      return SIZE;
   }

   public boolean isEmpty() {
      if (!this.be.scheduleSlot().isEmpty()) {
         return false;
      } else {
         for (int i = 0; i < TrainDispatcherBlockEntity.FREQ_SLOTS; i++) {
            if (!this.be.frequency(i).isEmpty()) {
               return false;
            }
         }

         return true;
      }
   }

   public ItemStack getItem(int slot) {
      return slot == 0 ? this.be.scheduleSlot() : this.be.frequency(slot - 1);
   }

   public ItemStack removeItem(int slot, int amount) {
      if (slot == 0) {
         try {
            return this.be.takeRebuiltSchedule();
         } catch (Throwable var5) {
            return ItemStack.EMPTY;
         }
      } else {
         ItemStack current = this.getItem(slot);
         if (current.isEmpty()) {
            return ItemStack.EMPTY;
         } else {
            ItemStack taken = current.split(amount);
            this.setItem(slot, current);
            return taken;
         }
      }
   }

   public ItemStack removeItemNoUpdate(int slot) {
      if (slot == 0) {
         try {
            return this.be.takeRebuiltSchedule();
         } catch (Throwable var3) {
            return ItemStack.EMPTY;
         }
      } else {
         ItemStack current = this.getItem(slot);
         this.setItem(slot, ItemStack.EMPTY);
         return current;
      }
   }

   public void setItem(int slot, ItemStack stack) {
      if (slot == 0) {
         this.be.setScheduleSlot(stack);
      } else {
         this.be.setFrequency(slot - 1, stack);
      }
   }

   public void setChanged() {
      this.be.setChanged();
   }

   public boolean stillValid(Player player) {
      return this.be.hasLevel()
         && player.distanceToSqr(
               (double)this.be.getBlockPos().getX() + 0.5, (double)this.be.getBlockPos().getY() + 0.5, (double)this.be.getBlockPos().getZ() + 0.5
            )
            <= 64.0;
   }

   public void clearContent() {
      this.be.setScheduleSlot(ItemStack.EMPTY);

      for (int i = 0; i < TrainDispatcherBlockEntity.FREQ_SLOTS; i++) {
         this.be.setFrequency(i, ItemStack.EMPTY);
      }
   }
}
