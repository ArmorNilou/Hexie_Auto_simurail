package willow.train.hexietrain.mixin;

import com.simibubi.create.content.trains.entity.Navigation;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.track.TrackMaterial.TrackType;
import java.util.Set;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import willow.train.hexietrain.TrainBinder;

@Mixin({Navigation.class})
public abstract class NavTrackFilterMixin {
   @Redirect(
      method = {"search(DDZLjava/util/ArrayList;Lcom/simibubi/create/content/trains/entity/Navigation$StationTest;)V"},
      at = @At(
         value = "INVOKE",
         target = "Ljava/util/Set;contains(Ljava/lang/Object;)Z"
      )
   )
   private boolean hexie$allowAnyTrackType(Set<TrackType> set, Object probe) {
      try {
         Train t = TrainBinder.trainOfNavigation(this);
         if (t != null && TrainBinder.OUR_TRAINS.contains(t.id)) {
            return true;
         }
      } catch (Throwable var4) {
      }

      return set.contains(probe);
   }
}
