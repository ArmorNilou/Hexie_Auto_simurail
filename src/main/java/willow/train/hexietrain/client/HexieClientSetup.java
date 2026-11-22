package willow.train.hexietrain.client;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.EventBusSubscriber.Bus;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import willow.train.hexietrain.HexieRegistries;
import willow.train.hexietrain.HexieTrain;
import willow.train.hexietrain.TrainDispatcherMenu;

@EventBusSubscriber(
   modid = "hexie_auto_simuroute",
   bus = Bus.MOD,
   value = {Dist.CLIENT}
)
public final class HexieClientSetup {
   private HexieClientSetup() {
   }

   @SubscribeEvent
   public static void registerScreens(RegisterMenuScreensEvent event) {
      try {
         Class<?> constructorType = Class.forName("net.minecraft.client.gui.screens.MenuScreens$ScreenConstructor");
         Object factory = Proxy.newProxyInstance(
            constructorType.getClassLoader(),
            new Class[]{constructorType},
            (proxy, method, args) -> args != null && args.length >= 3
                  ? new TrainDispatcherScreen((TrainDispatcherMenu)args[0], (Inventory)args[1], (Component)args[2])
                  : null
         );
         Method register = RegisterMenuScreensEvent.class.getMethod("register", MenuType.class, constructorType);
         register.invoke(event, HexieRegistries.TRAIN_DISPATCHER_MENU.get(), factory);
      } catch (Throwable var41) {
         HexieTrain.LOGGER.error("[HexieTrain] 注册调度器界面失败", var41);
      }
   }
}
