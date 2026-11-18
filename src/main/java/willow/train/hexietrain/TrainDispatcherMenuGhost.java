package willow.train.hexietrain;

import com.simibubi.create.foundation.gui.menu.GhostItemMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;

public class TrainDispatcherMenuGhost extends GhostItemMenu<TrainDispatcherBlockEntity> {
   private static final int FREQ = TrainDispatcherBlockEntity.FREQ_SLOTS;
   public static final int DATA_BRAKE = 0;
   public static final int DATA_LEFT = 1;
   public static final int DATA_RIGHT = 2;
   public static final int DATA_THROTTLE = 9;
   public static final int DATA_UNBOUND = 3;
   public static final int DATA_BOUND = 4;
   public static final int DATA_DISTANCE = 7;
   public static final int DATA_MILEAGE = 8;
   public static final int DATA_STOP_COUNT = 5;
   public static final int DATA_ARRIVED = 10;
   public static final int DATA_MANUAL_MODE = 11;
   public static final int DATA_CURRENT_ENTRY = 12;
   public static final int DATA_DISPATCHER_STATE = 14;
   public static final int DATA_SENSOR_SPEED = 15;
   public static final int DATA_COUNT = 16;
   private Container container;
   private int[] cached;
   private ContainerData data;

   private int[] cached() {
      if (this.cached == null) {
         this.cached = new int[20];
      }

      return this.cached;
   }

   private ContainerData data() {
      if (this.data == null) {
         this.data = new ContainerData() {
            public int get(int i) {
               return i >= 0 && i < 20 ? TrainDispatcherMenuGhost.this.cached()[i] : 0;
            }

            public void set(int i, int v) {
               if (i >= 0 && i < 20) {
                  TrainDispatcherMenuGhost.this.cached()[i] = v;
               }
            }

            public int getCount() {
               return 20;
            }
         };
      }

      return this.data;
   }

   public TrainDispatcherMenuGhost(MenuType<?> type, int id, Inventory inv, RegistryFriendlyByteBuf buf) {
      super(type, id, inv, buf);
      this.addDataSlots(this.data());
   }

   public TrainDispatcherMenuGhost(MenuType<?> type, int id, Inventory inv, TrainDispatcherBlockEntity be) {
      super(type, id, inv, be);
      this.addDataSlots(this.data());
   }

   public static TrainDispatcherMenuGhost create(int id, Inventory inv, TrainDispatcherBlockEntity be) {
      return new TrainDispatcherMenuGhost((MenuType<?>)HexieRegistries.TRAIN_DISPATCHER_MENU.get(), id, inv, be);
   }

   protected TrainDispatcherBlockEntity createOnClient(RegistryFriendlyByteBuf buf) {
      BlockPos pos = buf.readBlockPos();
      ClientLevel level = Minecraft.getInstance().level;
      return level != null && level.getBlockEntity(pos) instanceof TrainDispatcherBlockEntity be ? be : null;
   }

   protected ItemStackHandler createGhostInventory() {
      return new ItemStackHandler(FREQ);
   }

   protected boolean allowRepeats() {
      return false;
   }

   protected void initAndReadInventory(TrainDispatcherBlockEntity be) {
      super.initAndReadInventory(be);
      if (be != null && this.ghostInventory != null) {
         for (int i = 0; i < FREQ; i++) {
            this.ghostInventory.setStackInSlot(i, be.frequency(i));
         }
      }
   }

   protected void addSlots() {
      if (this.container == null) {
         this.container = (Container)(this.contentHolder != null
            ? new TrainDispatcherContainer((TrainDispatcherBlockEntity)this.contentHolder)
            : new SimpleContainer(TrainDispatcherContainer.SIZE));
      }

      this.addSlot(new Slot(this.container, 0, 14, 22));
      TrainDispatcherMenuGhost.GhostContainer ghost = new TrainDispatcherMenuGhost.GhostContainer(
         this.ghostInventory != null ? this.ghostInventory : new ItemStackHandler(FREQ)
      );
      // 制动 freq 0,1 在 y=74；节流阀 freq 2,3 在 y=94。y=54 那一行腾给车厢/转向架信息。
      int[][] xy = new int[][]{{40, 74}, {60, 74}, {40, 94}, {60, 94}};

      for (int i = 0; i < FREQ && i < xy.length; i++) {
         this.addSlot(new Slot(ghost, i, xy[i][0], xy[i][1]) {
            public boolean mayPlace(ItemStack s) {
               return false;
            }

            public boolean mayPickup(Player p) {
               return false;
            }

            public int getMaxStackSize() {
               return 1;
            }
         });
      }

      this.addPlayerSlots(8, 146);
   }

   protected void saveData(TrainDispatcherBlockEntity be) {
      if (be != null && this.ghostInventory != null) {
         for (int i = 0; i < FREQ; i++) {
            be.setFrequency(i, this.ghostInventory.getStackInSlot(i));
         }
      }
   }

   public TrainDispatcherBlockEntity blockEntity() {
      return (TrainDispatcherBlockEntity)this.contentHolder;
   }

   public void refresh() {
      TrainDispatcherBlockEntity be = (TrainDispatcherBlockEntity)this.contentHolder;
      int[] c = this.cached();
      c[0] = be.output(SignalChannel.BRAKE);

      c[4] = be.boundTrain() != null ? 1 : 0;
      c[7] = (int)Math.min(2.147483647E9, be.distanceToTarget());
      double uiDist = be.cacheDistanceForUi();
      c[8] = Double.isFinite(uiDist) ? (int)Math.round(uiDist * 10.0) : -1;
      c[9] = be.output(SignalChannel.THROTTLE);
      c[10] = be.arrivedFlag() ? 1 : 0;
      be.refreshStopNames();
      c[5] = be.stopNames().size();
      c[11] = be.manualMode() ? 1 : 0;
      c[3] = be.isUnbound() ? 1 : 0;
      c[12] = be.currentEntry();
      c[14] = be.dispatcherState().ordinal();
      c[15] = be.measuredSpeedCenti();
      c[16] = be.probeOk() ? 1 : 0;
      c[17] = be.probeBackwards() ? 1 : 0;
      c[18] = be.bogeyCount();
   }

   public void broadcastChanges() {
      this.refresh();
      super.broadcastChanges();
   }

   public boolean clickMenuButton(Player player, int id) {
      TrainDispatcherBlockEntity be = (TrainDispatcherBlockEntity)this.contentHolder;
      if (be == null) {
         return false;
      } else if (id == 3) {
         if (be.manualMode()) {
            be.forceAuto();
         } else {
            be.setManualMode(true);
         }

         this.refresh();
         return true;
      } else {
         return false;
      }
   }

   public int brakeOutput() {
      return this.data().get(0);
   }


   public int throttleOutput() {
      return this.data().get(9);
   }

   /** 已绑定的转向架数量（0 = 未连接） */
   public int bogeyCount() {
      return this.data().get(18);
   }

   public boolean bound() {
      return this.data().get(4) != 0;
   }

   public int distance() {
      return this.data().get(7);
   }

   public int mileage() {
      return this.data().get(8);
   }

   public boolean arrived() {
      return this.data().get(10) != 0;
   }

   public int stopCount() {
      return this.data().get(5);
   }

   public boolean manualMode() {
      return this.data().get(11) != 0;
   }

   public boolean unbound() {
      return this.data().get(3) != 0;
   }

   public int currentEntry() {
      return this.data().get(12);
   }

   public boolean probeOk() { return this.data().get(16) != 0; }
   public boolean probeBackwards() { return this.data().get(17) != 0; }

   public int dispatcherState() {
      return this.data().get(14);
   }

   public int sensorSpeedCenti() {
      return this.data().get(15);
   }

   public boolean stillValid(Player player) {
      return this.container == null || this.container.stillValid(player);
   }

   private static final class GhostContainer implements Container {
      private final ItemStackHandler handler;

      GhostContainer(ItemStackHandler handler) {
         this.handler = handler;
      }

      public int getContainerSize() {
         return this.handler.getSlots();
      }

      public boolean isEmpty() {
         for (int i = 0; i < this.handler.getSlots(); i++) {
            if (!this.handler.getStackInSlot(i).isEmpty()) {
               return false;
            }
         }

         return true;
      }

      public ItemStack getItem(int slot) {
         return this.handler.getStackInSlot(slot);
      }

      public ItemStack removeItem(int slot, int amount) {
         return ItemStack.EMPTY;
      }

      public ItemStack removeItemNoUpdate(int slot) {
         return ItemStack.EMPTY;
      }

      public void setItem(int slot, ItemStack stack) {
         this.handler.setStackInSlot(slot, stack);
      }

      public void setChanged() {
      }

      public boolean stillValid(Player player) {
         return true;
      }

      public void clearContent() {
         for (int i = 0; i < this.handler.getSlots(); i++) {
            this.handler.setStackInSlot(i, ItemStack.EMPTY);
         }
      }
   }
}