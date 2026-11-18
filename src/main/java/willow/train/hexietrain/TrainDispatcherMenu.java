package willow.train.hexietrain;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public class TrainDispatcherMenu extends AbstractContainerMenu {
   public static final int DATA_BRAKE = 0;
   public static final int DATA_LEFT = 1;
   public static final int DATA_RIGHT = 2;
   public static final int DATA_UNBOUND = 3;
   public static final int DATA_BOUND = 4;
   public static final int DATA_DISTANCE = 7;
   public static final int DATA_MILEAGE = 8;
   public static final int DATA_THROTTLE = 9;
   public static final int DATA_ARRIVED = 10;
   public static final int DATA_STOP_COUNT = 5;
   public static final int DATA_MANUAL_MODE = 11;
   public static final int DATA_CURRENT_ENTRY = 12;
   public static final int DATA_DISPATCHER_STATE = 14;
   public static final int DATA_SENSOR_SPEED = 15;
   public static final int DATA_COUNT = 16;
   private final Container container;
   private final TrainDispatcherBlockEntity blockEntity;
   private final int[] cached = new int[20];
   private final ContainerData data = new ContainerData() {
      public int get(int index) {
         return index >= 0 && index < 20 ? TrainDispatcherMenu.this.cached[index] : 0;
      }

      public void set(int index, int value) {
         if (index >= 0 && index < 20) {
            TrainDispatcherMenu.this.cached[index] = value;
         }
      }

      public int getCount() {
         return 20;
      }
   };

   public TrainDispatcherMenu(int containerId, Inventory playerInventory, FriendlyByteBuf buf) {
      this(containerId, playerInventory, clientEntity(playerInventory, buf.readBlockPos()));
   }

   private static TrainDispatcherBlockEntity clientEntity(Inventory inv, BlockPos pos) {
      return inv.player.level().getBlockEntity(pos) instanceof TrainDispatcherBlockEntity be ? be : null;
   }

   public TrainDispatcherMenu(int containerId, Inventory playerInventory, TrainDispatcherBlockEntity be) {
      this(containerId, playerInventory, be, (Container)(be != null ? new TrainDispatcherContainer(be) : new SimpleContainer(TrainDispatcherContainer.SIZE)));
   }

   private TrainDispatcherMenu(int containerId, Inventory playerInventory, TrainDispatcherBlockEntity be, Container container) {
      super((MenuType)HexieRegistries.TRAIN_DISPATCHER_MENU.get(), containerId);
      this.blockEntity = be;
      this.container = container;
      this.addDataSlots(this.data);
      if (be != null) {
         be.refreshStopNames();
      }

      this.addSlot(new Slot(container, 0, 14, 22) {
         public int getMaxStackSize() {
            return 1;
         }
      });

      // 只剩两行频段：制动（freq 0,1）在 y=74，节流阀（freq 2,3）在 y=94。
      // 原来 y=54 那行是制动，腾出来显示车厢/转向架信息。
      for (int f = 0; f < 2; f++) {
         this.addFreqSlot(container, 1 + f, 40 + f * 20, 74);
         this.addFreqSlot(container, 3 + f, 40 + f * 20, 94);
      }

      for (int row = 0; row < 3; row++) {
         for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 146 + row * 18));
         }
      }

      for (int col = 0; col < 9; col++) {
         this.addSlot(new Slot(playerInventory, col, 8 + col * 18, 204));
      }

      this.refresh();
   }

   public void refresh() {
      if (this.blockEntity != null) {
         this.cached[0] = this.blockEntity.output(SignalChannel.BRAKE);
         this.cached[4] = this.blockEntity.boundTrain() != null ? 1 : 0;
         this.cached[7] = (int)Math.min(2.147483647E9, this.blockEntity.distanceToTarget());
         double uiDist = this.blockEntity.cacheDistanceForUi();
         this.cached[8] = Double.isFinite(uiDist) ? (int)Math.round(uiDist * 10.0) : -1;
         this.cached[9] = this.blockEntity.output(SignalChannel.THROTTLE);
         this.cached[10] = this.blockEntity.arrivedFlag() ? 1 : 0;
         this.blockEntity.refreshStopNames();
          this.cached[5] = this.blockEntity.stopNames().size();
         this.cached[11] = this.blockEntity.manualMode() ? 1 : 0;
         this.cached[3] = this.blockEntity.isUnbound() ? 1 : 0;
         this.cached[12] = this.blockEntity.currentEntry();
         this.cached[14] = this.blockEntity.dispatcherState().ordinal();
         this.cached[15] = this.blockEntity.measuredSpeedCenti();
      this.cached[16] = this.blockEntity.probeOk() ? 1 : 0;
      this.cached[17] = this.blockEntity.probeBackwards() ? 1 : 0;
      this.cached[18] = this.blockEntity.bogeyCount();
      this.cached[19] = this.blockEntity.rosterCars();
      }
   }

   public void broadcastChanges() {
      this.refresh();
      super.broadcastChanges();
   }

   public boolean clickMenuButton(Player player, int id) {
      if (this.blockEntity == null) {
         return false;
      } else {
         switch (id) {
            case 3:
               if (this.blockEntity.manualMode()) {
                  this.blockEntity.forceAuto();
               } else {
                  this.blockEntity.setManualMode(true);
               }

               this.refresh();
               return true;
            default:
               return false;
         }
      }
   }

   public TrainDispatcherBlockEntity blockEntity() {
      return this.blockEntity;
   }

   public int brakeOutput() {
      return this.data.get(0);
   }

   public boolean bound() {
      return this.data.get(4) != 0;
   }

   public int distance() {
      return this.data.get(7);
   }

   public int mileage() {
      return this.data.get(8);
   }

   public int throttleOutput() {
      return this.data.get(9);
   }

   public boolean arrived() {
      return this.data.get(10) != 0;
   }

   private void addFreqSlot(Container container, int index, int x, int y) {
      this.addSlot(new Slot(container, index, x, y) {
         public boolean mayPlace(ItemStack stack) {
            return false;
         }

         public boolean mayPickup(Player player) {
            return false;
         }

         public int getMaxStackSize() {
            return 1;
         }
      });
   }

   public void clicked(int slotId, int button, ClickType type, Player player) {
      int base = 1;
      int ci = -1;
      if (slotId >= 0 && slotId < this.slots.size()) {
         Slot sl = this.getSlot(slotId);
         if (sl.container == this.container) {
            ci = sl.getContainerSlot();
         }
      }

      if (ci >= base && ci < base + TrainDispatcherBlockEntity.FREQ_SLOTS) {
         ItemStack carried = this.getCarried();
         this.blockEntity.setFrequency(ci - base, carried.isEmpty() ? ItemStack.EMPTY : carried.copyWithCount(1));
      } else {
         super.clicked(slotId, button, type, player);
      }
   }

   public int stopCount() {
      return this.data.get(9);
   }

   public boolean manualMode() {
      return this.data.get(11) != 0;
   }

   public boolean unbound() {
      return this.data.get(3) != 0;
   }

   public int currentEntry() {
      return this.data.get(12);
   }

   public int dispatcherState() {
      return this.data.get(14);
   }

   public int sensorSpeedCenti() {
      return this.data.get(15);
   }

   public ItemStack quickMoveStack(Player player, int index) {
      Slot slot = (Slot)this.slots.get(index);
      if (slot != null && slot.hasItem()) {
         ItemStack stack = slot.getItem();
         ItemStack copy = stack.copy();
         int blockSlots = TrainDispatcherContainer.SIZE;
         int inventoryEnd = blockSlots + 36;
         if (index < blockSlots) {
            if (!this.moveItemStackTo(stack, blockSlots, inventoryEnd, true)) {
               return ItemStack.EMPTY;
            }
         } else if (!this.moveItemStackTo(stack, 0, blockSlots, false)) {
            return ItemStack.EMPTY;
         }

         if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
         } else {
            slot.setChanged();
         }

         return copy;
      } else {
         return ItemStack.EMPTY;
      }
   }

   public boolean stillValid(Player player) {
      return this.container.stillValid(player);
   }
}