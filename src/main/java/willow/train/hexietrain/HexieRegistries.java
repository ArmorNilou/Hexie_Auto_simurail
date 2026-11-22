package willow.train.hexietrain;

import com.mojang.datafixers.types.Type;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.CreativeModeTab.Row;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.BlockEntityType.Builder;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.BlockBehaviour.Properties;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredRegister.Blocks;
import net.neoforged.neoforge.registries.DeferredRegister.Items;

public final class HexieRegistries {
   public static final Blocks BLOCKS = DeferredRegister.createBlocks("hexie_auto_simuroute");
   public static final Items ITEMS = DeferredRegister.createItems("hexie_auto_simuroute");
   public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, "hexie_auto_simuroute");
   public static final DeferredBlock<TrainDispatcherBlock> TRAIN_DISPATCHER = BLOCKS.register(
      "train_dispatcher", () -> new TrainDispatcherBlock(Properties.of().strength(3.0F, 6.0F).noOcclusion())
   );
   public static final DeferredItem<BlockItem> TRAIN_DISPATCHER_ITEM = ITEMS.registerSimpleBlockItem("train_dispatcher", TRAIN_DISPATCHER);
   /** 转向架连接器：先右键调度器，再逐个右键转向架。 */
   public static final DeferredItem<BogeyConnectorItem> BOGEY_CONNECTOR = ITEMS.register(
      "bogey_connector",
      () -> new BogeyConnectorItem(new net.minecraft.world.item.Item.Properties().stacksTo(1)));
   public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TrainDispatcherBlockEntity>> TRAIN_DISPATCHER_BE = BLOCK_ENTITIES.register(
      "train_dispatcher", () -> buildBlockEntityType((Block)TRAIN_DISPATCHER.get())
   );
   public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, "hexie_auto_simuroute");
   public static final DeferredHolder<MenuType<?>, MenuType<TrainDispatcherMenu>> TRAIN_DISPATCHER_MENU = MENUS.register(
      "train_dispatcher",
      // 1.2.0：两套菜单合并成一个类。以前这里造的是 TrainDispatcherMenuGhost（客户端专用），
      // 而 createMenu() 返回的是 TrainDispatcherMenu（服务端），两边槽位/数据下标各写一遍。
      () -> IMenuTypeExtension.create((id, inv, buf) -> new TrainDispatcherMenu(id, inv, buf))
   );
   public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, "hexie_auto_simuroute");
   /** 供菜单工厂在运行时取类型，避开初始化器自引用。 */
   public static MenuType<TrainDispatcherMenu> menuType() {
      return TRAIN_DISPATCHER_MENU.get();
   }

   public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register(
      "main",
      () -> CreativeModeTab.builder(Row.TOP, 0)
            .title(Component.translatable("itemGroup.hexie_auto_simuroute"))
            .icon(() -> new ItemStack((ItemLike)TRAIN_DISPATCHER_ITEM.get()))
            .displayItems((params, output) -> {
               output.accept((ItemLike)TRAIN_DISPATCHER_ITEM.get());
               output.accept((ItemLike)BOGEY_CONNECTOR.get());
            })
            .build()
   );

   private HexieRegistries() {
   }

   public static void register(IEventBus modBus) {
      BLOCKS.register(modBus);
      ITEMS.register(modBus);
      BLOCK_ENTITIES.register(modBus);
      MENUS.register(modBus);
      TABS.register(modBus);
   }

   private static BlockEntityType<TrainDispatcherBlockEntity> buildBlockEntityType(Block block) {
      try {
         Class<?> supplierType = Class.forName("net.minecraft.world.level.block.entity.BlockEntityType$BlockEntitySupplier");
         Object supplier = Proxy.newProxyInstance(
            supplierType.getClassLoader(),
            new Class[]{supplierType},
            (proxy, method, args) -> {
               String name = method.getName();
               if ("create".equals(name) && args != null && args.length >= 2) {
                  return new TrainDispatcherBlockEntity((BlockPos)args[0], (BlockState)args[1]);
               }
               if ("toString".equals(name)) return "hexietrain:train_dispatcher";
               if ("hashCode".equals(name)) return System.identityHashCode(proxy);
               if ("equals".equals(name)) return proxy == (args != null && args.length > 0 ? args[0] : null);
               return null;
            }
         );
         Method of = Builder.class.getMethod("of", supplierType, Block[].class);
         Object builder = of.invoke(null, supplier, new Block[]{block});
         Method build = builder.getClass().getMethod("build", Type.class);
         Object built = build.invoke(builder, (Object)null);
         if (built == null) throw new IllegalStateException("build() 返回 null");
         return (BlockEntityType<TrainDispatcherBlockEntity>)built;
      } catch (Throwable t) {
         Throwable cause = t.getCause() == null ? t : t.getCause();
         throw new RuntimeException("[HexieTrain] 构造 BlockEntityType 失败: " + cause.getClass().getName() + ": " + cause.getMessage(), cause);
      }
   }
}
