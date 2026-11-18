package willow.train.hexietrain;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.signal.SignalBoundary;
import com.simibubi.create.content.trains.signal.SignalEdgeGroup;
import com.simibubi.create.content.trains.signal.SignalBlock.SignalType;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;

public final class SignalProbe {
   private SignalProbe() {
   }

   public static SignalProbe.Obs observe(SignalBoundary signal, TrackNode enteringNode, Train train) {
      if (signal != null && enteringNode != null) {
         UUID group;
         boolean primary;
         try {
            group = signal.getGroup(enteringNode);
            primary = group != null && group.equals(signal.groups.getFirst());
         } catch (Throwable var9) {
            return null;
         }

         try {
            Map<BlockPos, Boolean> thisSide = (Map<BlockPos, Boolean>)signal.blockEntities.get(primary);
            Map<BlockPos, Boolean> otherSide = (Map<BlockPos, Boolean>)signal.blockEntities.get(!primary);
            if (thisSide != null && !thisSide.isEmpty()) {
               boolean chained = signal.types.get(primary) == SignalType.CROSS_SIGNAL;
               return signal.isForcedRed(enteringNode)
                  ? new SignalProbe.Obs(signal, group, primary, chained, SignalProbe.State.BLOCKED)
                  : new SignalProbe.Obs(signal, group, primary, chained, occupancy(group, train));
            } else {
               return otherSide != null && !otherSide.isEmpty()
                  ? new SignalProbe.Obs(signal, group, primary, false, SignalProbe.State.BLOCKED)
                  : new SignalProbe.Obs(signal, group, primary, false, SignalProbe.State.UNKNOWN);
            }
         } catch (Throwable var81) {
            return null;
         }
      } else {
         return null;
      }
   }

   public static SignalProbe.State occupancy(UUID id, Train train) {
      return occupancy(id, train, new HashSet<>());
   }

   private static SignalProbe.State occupancy(UUID id, Train train, HashSet<UUID> visited) {
      if (id == null) {
         return SignalProbe.State.UNKNOWN;
      } else if (!visited.add(id)) {
         return SignalProbe.State.CLEAR;
      } else {
         SignalEdgeGroup group = (SignalEdgeGroup)Create.RAILWAYS.signalEdgeGroups.get(id);
         if (group == null) {
            return SignalProbe.State.UNKNOWN;
         } else {
            try {
               boolean otherTrain = group.trains != null && group.trains.stream().anyMatch(other -> other != train);
               boolean reservedByOther = group.reserved != null && (train.reservedSignalBlocks == null || !train.reservedSignalBlocks.contains(id));
               if (!otherTrain && !reservedByOther) {
                  SignalProbe.State result = SignalProbe.State.CLEAR;
                  if (group.intersecting != null) {
                     for (UUID next : group.intersecting.values()) {
                        SignalProbe.State state = occupancy(next, train, visited);
                        if (state == SignalProbe.State.BLOCKED) {
                           return state;
                        }

                        if (state == SignalProbe.State.UNKNOWN) {
                           result = state;
                        }
                     }
                  }

                  return result;
               } else {
                  return SignalProbe.State.BLOCKED;
               }
            } catch (Throwable var101) {
               return SignalProbe.State.UNKNOWN;
            }
         }
      }
   }

   public static record Obs(SignalBoundary signal, UUID group, boolean primary, boolean chained, SignalProbe.State state) {
   }

   public static enum State {
      CLEAR,
      BLOCKED,
      UNKNOWN;
   }
}
