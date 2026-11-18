package willow.train.hexietrain;

import com.simibubi.create.content.trains.entity.Navigation;
import com.simibubi.create.content.trains.entity.Train;

public final class TrainStateReader {
   private TrainStateReader() {
   }

   public static TrainStateReader.State read(Train train) {
      if (train == null) {
         return new TrainStateReader.State(0.0, 0.0, false, false);
      } else {
         double speedBps = train.speed * 20.0;
         Navigation nav = train.navigation;
         if (nav == null) {
            return new TrainStateReader.State(speedBps, 0.0, false, false);
         } else if (nav.waitingForSignal != null) {
            return new TrainStateReader.State(speedBps, nav.distanceToSignal, true, true);
         } else {
            return nav.destination != null
               ? new TrainStateReader.State(speedBps, nav.distanceToDestination, true, false)
               : new TrainStateReader.State(speedBps, 0.0, false, false);
         }
      }
   }

   public static record State(double speedBlocksPerSecond, double distance, boolean hasTarget, boolean targetIsSignal) {
   }
}
