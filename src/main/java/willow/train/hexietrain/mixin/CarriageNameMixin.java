package willow.train.hexietrain.mixin;

import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import willow.train.hexietrain.TrainBinder;

/**
 * 让车厢实体显示<b>我们在调度器上同步过去的列车名</b>。
 *
 * <h2>问题（2026-11-22 用户实测）</h2>
 * 用户用 Xaero 小地图看实体名，看到的永远是「未命名列车」。
 *
 * <p>{@code CarriageContraptionEntity.getDisplayName()} 的实现是：
 * <pre>
 *   if (carriage == null) return CreateLang.translateDirect("train");   // 「列车」
 *   return ... carriage.train.name ...
 * </pre>
 *
 * <p>而 {@code Train} 是<b>纯服务端对象</b>——它一个 send/sync 方法都没有（全类核过），
 * 客户端那份 {@code Train} 永远停在构造函数给的默认值
 * {@code CreateLang.translateDirect("train.unnamed")}「未命名列车」。
 * 所以我们在服务端把 {@code train.name} 改成「111」对客户端毫无影响。
 *
 * <h2>为什么不用 Create 自己的 {@code TrainEditPacket}</h2>
 * {@code TrainEditPacket$TrainEditReturnPacket} 的 {@code handle(LocalPlayer)}
 * 只是 {@code handleSided(null)}，去更新某个 GUI 的局面，**不会写回客户端 Train**。
 * 走它等于白发。
 *
 * <h2>做法</h2>
 * 名字随调度器 BlockEntity 的同步包过去（`write/read` 的 {@code clientPacket} 分支），
 * 客户端 BE 读到就 {@link TrainBinder#putClientName}，这里取出来直接当显示名。
 * 没有新增任何网络包。
 */
@Mixin({CarriageContraptionEntity.class})
public abstract class CarriageNameMixin {

   @Inject(
      method = {"getDisplayName"},
      at = {@At("RETURN")},
      cancellable = true,
      require = 0
   )
   private void hexie$ourTrainName(CallbackInfoReturnable<Component> cir) {
      try {
         CarriageContraptionEntity self = (CarriageContraptionEntity)(Object)this;
         String name = TrainBinder.clientNameOf(self.trainId);
         if (name != null && !name.isEmpty()) {
            cir.setReturnValue(Component.literal(name));
         }
      } catch (Throwable ignored) {
      }
   }
}
