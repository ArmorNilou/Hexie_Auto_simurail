package willow.train.hexietrain;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod("hexie_auto_simuroute")
public class HexieTrain {
   public static final String MOD_ID = "hexie_auto_simuroute";
   public static final Logger LOGGER = LoggerFactory.getLogger("HexieTrain");

   public HexieTrain(IEventBus modBus, ModContainer container) {
      HexieRegistries.register(modBus);
      SubLevelSuspend.init();
      LOGGER.info("[HexieTrain] 载入：物理列车附属模组");
   }

   public static ResourceLocation id(String path) {
      return ResourceLocation.fromNamespaceAndPath("hexie_auto_simuroute", path);
   }
}
