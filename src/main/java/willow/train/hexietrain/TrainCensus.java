package willow.train.hexietrain;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.GlobalRailwayManager;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.signal.SignalBoundary;
import com.simibubi.create.content.trains.signal.SignalEdgeGroup;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <b>只读普查</b>：回答"这个信号区间里到底是谁在占"和"Create 现在认得几辆车"。
 *
 * <p>为什么必须有它：{@code SignalBoundary.tickState} 里
 * <pre>
 *   occupied = isForcedRed(side) || edgeGroup.isOccupiedUnless(this);
 * </pre>
 * 而 {@code isOccupiedUnless(SignalBoundary)} 展开是 {@code !trains.isEmpty() || reserved != null} ——
 * <b>不排除本车</b>。所以"RED"这个布尔值天生分不清两件事：
 * <ol>
 *   <li>前面真有一辆别的车停在那段里（该等）；</li>
 *   <li>那段是被<b>我们自己</b>占的（不该等，等了就是永久自锁）。</li>
 * </ol>
 * Create 自己在 {@code isOccupiedUnless(Train)} 里是按<b>车辆引用</b>排除自己的，
 * 也就是说第 2 种情况在语义上根本不算占用。要区分这两种，唯一可靠的办法是
 * 把 {@code SignalEdgeGroup.trains} 这个集合原样打出来 —— 就是本类的全部职责。
 *
 * <p><b>本类不做任何判定、不写任何状态</b>，只拼字符串。判定仍然只在
 * {@link SignalProbe} 里，所以加这个类不会改变任何行驶行为。
 */
public final class TrainCensus {

   private TrainCensus() {
   }

   /**
    * 某个信号区间里现在有谁。
    *
    * @param groupId 区间 id（{@code SignalBoundary.getGroup(enteringNode)} 或
    *                {@code SignalBoundary.getId()} 的结果）
    * @param self    本车，用来标出"这个就是我自己"
    */
   public static String group(UUID groupId, Train self) {
      if (groupId == null) {
         return "区间=null";
      }

      SignalEdgeGroup group = groupOf(groupId);
      if (group == null) {
         return "区间=" + SignalProbe.shortId(groupId) + " 不在 signalEdgeGroups 里（已失效）";
      }

      StringBuilder sb = new StringBuilder();
      sb.append("区间=").append(SignalProbe.shortId(groupId));
      if (group.fallbackGroup) {
         sb.append("(fallback)");
      }

      // 1.2.0：物理车占区（Simurail 的 SignalEdgeGroup.bogeys）。
      // 没有绑定虚拟车的物理车只在这里出现，group.trains 里根本看不到它。
      try {
         int phys = PhysicalOccupancy.size(group);
         sb.append(" 物理转向架=").append(phys < 0 ? "?（反射失败）" : phys);
         if (phys > 0) {
            sb.append(PhysicalOccupancy.occupiedByOthers(group) ? "(含别人的)" : "(全是我们的)");
         }
      } catch (Throwable t) {
         sb.append(" 物理转向架=?");
      }

      List<Train> occupants = group.trains == null ? List.of() : new ArrayList<>(group.trains);
      sb.append(" 占用车数=").append(occupants.size()).append(" [");

      boolean onlySelf = true;
      for (int i = 0; i < occupants.size(); i++) {
         Train t = occupants.get(i);
         if (i > 0) {
            sb.append("; ");
         }
         sb.append(describe(t, self));
         if (t == null || self == null || t.id == null || !t.id.equals(self.id)) {
            onlySelf = false;
         }
      }
      sb.append("]");

      // 本车自己登记在案（Create 的 Train.occupiedSignalBlocks）的区间。
      // 这是"我们的占区范围有没有超出车身"的**唯一实测依据**：
      // 17 m 的车正常只该占 1~2 个区间；如果占了一大串，说明占区起点（最后一节车的
      // trailing 点）不在车尾，或者轴序反了 —— 那会误锁**别人**的车，必须单独修。
      if (self != null && self.occupiedSignalBlocks != null) {
         sb.append(" 本车占的区间数=").append(self.occupiedSignalBlocks.size()).append(" [");
         boolean firstGroup = true;
         for (UUID g : self.occupiedSignalBlocks.keySet()) {
            if (!firstGroup) {
               sb.append(',');
            }
            firstGroup = false;
            sb.append(SignalProbe.shortId(g));
         }
         sb.append(']');
      }

      // 预留：Create 用 reserved 表示"这条区间已经被某台信号机预留给一列车"。
      // 它既可能是我们自己预留的，也可能是别人的 —— 所以也要把 id 打出来。
      SignalBoundary reserved = group.reserved;
      sb.append(" 预留=").append(reserved == null ? "无" : SignalProbe.shortId(reserved.getId()));

      // 结论要把三种"红"分开，因为处置不同：
      //   自占   → 不该因此停车（Create 自己 isOccupiedUnless(Train) 就是这么排除的）
      //   有别人 → 该等，等对方走
      //   没人也没预留 → 红是 resolveSignalChain 顺着链解析出来的（链上有红），
      //                 不是这个区间被占 —— 要找的是链上那一台的占用者
      String verdict;
      if (occupants.isEmpty()) {
         verdict = reserved == null
            ? "没人占也没预留 ⇒ 红来自链上解析（resolveSignalChain），不是本区间被占"
            : "没人占但被信号机预留了 ⇒ 红来自预留";
      } else if (onlySelf) {
         verdict = "只有本车（自占）⇒ 这个红是我们自己占出来的，不该因此停车";
      } else {
         verdict = "有别的车 ⇒ 该等，等对方离开";
      }
      sb.append(" ⇒ ").append(verdict);
      return sb.toString();
   }

   /**
    * Create 现在到底认得几辆车。
    *
    * <p><b>为什么连 key 一起打</b>：{@code trains} 的 key 是 id，但历史版本可能留下
    * "key 是 A、对象里 id 是 B"的错位条目；也可能同一辆车既有 map 条目又挂在
    * {@code movingTrains}/{@code waitingTrains} 里。只打 id 会漏掉这种残影。
    *
    * <p>同时标出每辆车归谁：{@code 我}（就是本车）／{@code 我们的}（在
    * {@link TrainBinder#OUR_TRAINS}）／{@code 有调度器}（在
    * {@link TrainBinder#TRAIN_DISPATCHERS}）／<b>{@code 孤儿}</b>（三者都不是 ——
    * 这种车就是老版本留下、没人再管的虚拟车，它会永久占着区间）。
    */
   public static String trains(Train self) {
      GlobalRailwayManager rm = Create.RAILWAYS;
      if (rm == null || rm.trains == null) {
         return "RAILWAYS 不可用";
      }

      StringBuilder sb = new StringBuilder();
      sb.append("Create 认得的车 = ").append(rm.trains.size()).append(" 辆 [");
      boolean first = true;
      for (Map.Entry<UUID, Train> e : rm.trains.entrySet()) {
         if (!first) {
            sb.append("; ");
         }
         first = false;
         Train t = e.getValue();
         sb.append("{key=").append(SignalProbe.shortId(e.getKey())).append(' ').append(describe(t, self)).append('}');
      }
      sb.append("]");
      return sb.toString();
   }

   /** 是否"这个区间里除了我们自己没有别人"。取不到区间时返回 false（不猜）。 */
   public static boolean onlyOurselves(UUID groupId, Train self) {
      SignalEdgeGroup group = groupOf(groupId);
      if (group == null || self == null || group.trains == null) {
         return false;
      }
      if (group.trains.isEmpty()) {
         return false;
      }
      for (Train t : group.trains) {
         if (t == null || t.id == null || !t.id.equals(self.id)) {
            return false;
         }
      }
      return true;
   }

   /** 一辆车的身份与形状，一行。 */
   private static String describe(Train t, Train self) {
      if (t == null) {
         return "null";
      }
      StringBuilder sb = new StringBuilder();
      sb.append(SignalProbe.shortId(t.id));

      boolean isSelf = self != null && t.id != null && t.id.equals(self.id);
      if (isSelf) {
         sb.append("·我");
      } else if (t.id != null && TrainBinder.OUR_TRAINS.contains(t.id)) {
         sb.append("·我们的");
      } else {
         sb.append("·孤儿");
      }
      if (t.id != null && TrainBinder.TRAIN_DISPATCHERS.containsKey(t.id)) {
         sb.append("/有调度器");
      }

      sb.append(" 节=").append(t.carriages == null ? -1 : t.carriages.size());
      int bogeySpacing = t.carriages == null || t.carriages.isEmpty() ? -1 : ((Carriage)t.carriages.get(0)).bogeySpacing;
      sb.append(" 首节bogeySpacing=").append(bogeySpacing);

      sb.append(" inv=").append(t.invalid).append(" drail=").append(t.derailed);
      sb.append(" spd=").append(String.format("%.2f", t.speed));

      TravellingPoint head = headPoint(t);
      sb.append(" 头=").append(head == null || head.edge == null ? "无轨" : fmt(head));
      return sb.toString();
   }

   /** 车头（第 0 节车的 leading 点）。取不到返回 null。 */
   private static TravellingPoint headPoint(Train t) {
      try {
         return t.carriages == null || t.carriages.isEmpty() ? null : ((Carriage)t.carriages.get(0)).getLeadingPoint();
      } catch (Throwable ignored) {
         return null;
      }
   }

   private static String fmt(TravellingPoint p) {
      try {
         double len = Math.max(1.0E-6, p.edge.getLength());
         return (p.edge.node1.getLocation().getLocation().toString())
            + "@" + String.format("%.1f/%.1f", p.position, len);
      } catch (Throwable t) {
         return "?";
      }
   }

   private static SignalEdgeGroup groupOf(UUID groupId) {
      try {
         GlobalRailwayManager rm = Create.RAILWAYS;
         return rm == null || rm.signalEdgeGroups == null ? null : rm.signalEdgeGroups.get(groupId);
      } catch (Throwable ignored) {
         return null;
      }
   }


}
