package willow.train.hexietrain;

import com.simibubi.create.content.redstone.link.IRedstoneLinkable;
import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler.Frequency;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;

final class WirelessChannel implements IRedstoneLinkable {
   private final TrainDispatcherBlockEntity be;
   private final SignalChannel channel;
   private int received;

   WirelessChannel(TrainDispatcherBlockEntity be, SignalChannel channel) {
      this.be = be;
      this.channel = channel;
   }

   public int getTransmittedStrength() {
      return this.be.output(this.channel);
   }

   public void setReceivedStrength(int strength) {
      this.received = strength;
   }

   public int received() {
      return this.received;
   }

   public boolean isListening() {
      return false;
   }

   public boolean isAlive() {
      return !this.be.isRemoved() && this.be.hasLevel();
   }

   public Couple<Frequency> getNetworkKey() {
      return this.be.networkKey(this.channel);
   }

   public BlockPos getLocation() {
      return this.be.getBlockPos();
   }

   SignalChannel channel() {
      return this.channel;
   }
}
