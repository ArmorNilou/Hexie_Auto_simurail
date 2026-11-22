package willow.train.hexietrain;

import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;
import com.simibubi.create.content.trains.signal.SignalEdgeGroup;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Set;

/**
 * <b>物理车占区</b>：回答"这个信号区间里有没有<b>别人的</b>物理转向架"。
 *
 * <h2>为什么需要它（用户实测报告）</h2>
 * 「他会无视没有虚拟车的物理车，虽然物理车在没有绑定虚拟车的情况下可以在信号上占用区间，
 * 但我们的车直接无视」
 *
 * <p>Create 的占区有两套：
 * <ul>
 *   <li>{@code SignalEdgeGroup.trains} —— Create 的 {@code Train} 对象（我们的虚拟车、别人的 Create 车）；</li>
 *   <li>Simurail 自己加的 {@code SignalEdgeGroup.bogeys} —— <b>物理</b>转向架，
 *       由 {@code PhysicsBogeyAxle.updateSignalGroup()} 每拍 {@code simurail$queueBogey(bogey)} 报名。</li>
 * </ul>
 * 我们的 {@code SignalProbe.occupancy()} 只查了前者 ⇒ <b>没有虚拟车的物理车完全隐形</b>。
 *
 * <h2>为什么不用 Simurail 现成的 {@code simurail$isOccupiedUnless(PhysicsBogeyGroup)}</h2>
 * 它的实现是
 * <pre>
 *   group.bogeys.stream().noneMatch(bogeys::contains) &amp;&amp; !bogeys.isEmpty() || reserved != null || !trains.isEmpty()
 * </pre>
 * {@code noneMatch} 的意思是"我这个组里<b>一个</b>转向架都不在这个区间里"才判占用。
 * 于是"我方一根 + 对方一根"同处一个区间时它返回 <b>false</b> —— 恰恰是最该拦的那种混行场景会漏判。
 * 而逐根调用 {@code simurail$isThisOccupiedUnless(bogey)} 又因为里面写死
 * {@code bogeys.size() > 1} 而把我方两根转向架同处一区间判成"被占" ⇒ 自锁。
 *
 * <p>所以这里直接读那个集合本身，按 {@link TrainBinder#isOurBogey} 精确排除自己。
 * <b>这是 Simurail 没有公开 API 的地方，只能反射</b>；反射失败会大声报一次并返回 false，
 * 绝不假装"没人占"还不出声。
 */
public final class PhysicalOccupancy {

   private PhysicalOccupancy() {
   }

   private static Field bogeysField = null;
   private static boolean resolveTried = false;
   /** 反射失败只报一次，别每拍刷。 */
   private static volatile boolean warned = false;

   /**
    * 这个区间里有没有<b>不属于我们任何一辆车</b>的物理转向架。
    *
    * @return true = 有别人的物理车占着（该停）。取不到集合时返回 false 并已报过一次警。
    */
   public static boolean occupiedByOthers(SignalEdgeGroup group) {
      Set<?> bogeys = bogeysOf(group);
      if (bogeys == null || bogeys.isEmpty()) {
         return false;
      }
      for (Object o : bogeys) {
         if (o instanceof PhysicsBogeyBlockEntity bogey) {
            if (!TrainBinder.isOurBogey(bogey.getBlockPos())) {
               return true;
            }
         } else if (o != null) {
            // 集合里出现非物理转向架的东西：说不清就当有人占（安全侧）
            return true;
         }
      }
      return false;
   }

   /** 这个区间里的物理转向架个数（只给日志/诊断用）。 */
   public static int size(SignalEdgeGroup group) {
      Set<?> bogeys = bogeysOf(group);
      return bogeys == null ? -1 : bogeys.size();
   }

   private static Set<?> bogeysOf(SignalEdgeGroup group) {
      if (group == null) {
         return null;
      }
      Field f = resolveField();
      if (f == null) {
         return null;
      }
      try {
         Object v = f.get(group);
         return v instanceof Set<?> set ? set : null;
      } catch (Throwable t) {
         warnOnce("读取失败：" + t.getClass().getSimpleName());
         return null;
      }
   }

   /**
    * 找 Simurail 加在 {@code SignalEdgeGroup} 上的那个集合字段。
    *
    * <p>{@code @Unique} 字段在有同名冲突时会被 Mixin 改名，所以不能只靠名字：
    * 先试 {@code bogeys}，否则退化成"扫所有 Set 字段、取泛型里带 PhysicsBogeyBlockEntity 的那个"。
    * 全都找不到就只能报一次警。
    */
   private static Field resolveField() {
      if (resolveTried) {
         return bogeysField;
      }
      resolveTried = true;
      try {
         Field byName = SignalEdgeGroup.class.getDeclaredField("bogeys");
         byName.setAccessible(true);
         bogeysField = byName;
         return bogeysField;
      } catch (Throwable ignored) {
         // 名字对不上，按类型扫
      }
      try {
         for (Field f : SignalEdgeGroup.class.getDeclaredFields()) {
            if (!Set.class.isAssignableFrom(f.getType())) {
               continue;
            }
            String generic = f.getGenericType().toString();
            if (generic.contains("PhysicsBogeyBlockEntity")) {
               f.setAccessible(true);
               bogeysField = f;
               return bogeysField;
            }
         }
      } catch (Throwable t) {
         warnOnce("扫描字段异常：" + t.getClass().getSimpleName());
         return null;
      }
      warnOnce("找不到 Simurail 加在 SignalEdgeGroup 上的物理转向架集合 —— 物理车占区将看不见！");
      return null;
   }

   private static void warnOnce(String why) {
      if (!warned) {
         warned = true;
         HexieTrain.LOGGER.warn("[sig][物理占区] {}（Simurail 改了内部结构？这条只报一次）", why);
      }
   }

   /** 只读视图，给诊断用。 */
   public static Set<?> peek(SignalEdgeGroup group) {
      Set<?> s = bogeysOf(group);
      return s == null ? Collections.emptySet() : Collections.unmodifiableSet(s);
   }
}
