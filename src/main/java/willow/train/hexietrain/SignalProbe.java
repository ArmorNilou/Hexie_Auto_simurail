package willow.train.hexietrain;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.signal.SignalBlockEntity;
import com.simibubi.create.content.trains.signal.SignalBoundary;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.signal.SignalEdgeGroup;
import com.simibubi.create.content.trains.signal.SignalPropagator;
import com.simibubi.create.content.trains.signal.SignalBlock.SignalType;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;

public final class SignalProbe {
   private SignalProbe() {
   }

   public static SignalProbe.Obs observe(SignalBoundary signal, TrackNode enteringNode, Train train) {
      return observe(signal, enteringNode, train, null);
   }

   /**
    * @param route 本段缓存路线；只用来判断"链上那几台信号机里，哪几台在<b>我们这条岔口</b>上"。null = 不知道
    */
   public static SignalProbe.Obs observe(SignalBoundary signal, TrackNode enteringNode, Train train, RailCache.Route route) {
      if (signal != null && enteringNode != null) {
         UUID group;
         boolean primary;
         try {
            group = signal.getGroup(enteringNode);
            // 哪一侧必须由 Create 自己判：isPrimary(node) = (node == edgeLocation.getSecond())。
            // 以前是拿 group 去比 groups.getFirst() 反推，那依赖"组号已分配且没被改过"；
            // 信号传播器一重算，primary 就会翻面 ⇒ 同一台信号机从"反向"变"畅通"，
            // 锁存当场被放掉 ⇒ 车开过红灯（1.1.10 实测：等 37 拍后凭空"放行"）。
            primary = signal.isPrimary(enteringNode);
         } catch (Throwable var9) {
            return null;
         }

         try {
            Map<BlockPos, Boolean> thisSide = (Map<BlockPos, Boolean>)signal.blockEntities.get(primary);
            Map<BlockPos, Boolean> otherSide = (Map<BlockPos, Boolean>)signal.blockEntities.get(!primary);
            boolean forcedRed = false;
            try {
               forcedRed = signal.isForcedRed(enteringNode);
            } catch (Throwable ignored) {
            }
            boolean chained = signal.types.get(primary) == SignalType.CROSS_SIGNAL;
            // Create 自己算好的那一份状态（tickState 写进 cachedStates，public）。不读方块实体、不碰区块。
            SignalBlockEntity.SignalState state = signal.cachedStates == null ? null : signal.cachedStates.get(primary);
            String head = "区间=" + shortId(group)
               + " primary=" + primary
               + " 类型=" + (chained ? "黄铜/通过(CROSS)" : "安山/入口(ENTRY)")
               + " 本侧方块=" + (thisSide == null ? 0 : thisSide.size())
               + " 对面=" + (otherSide == null ? 0 : otherSide.size())
               + " 强制红=" + forcedRed
               + " 状态=" + (state == null ? "?" : state);
            lastObserveDetail = head;
            if (thisSide != null && !thisSide.isEmpty()) {
               // ===== 判定口径（1.1.18 定稿，全部对齐 Create 自己的准入判断）=====
               // 显示出来的颜色（cachedStates）**不能单独当依据**，因为 Create 算它时用的是
               // SignalEdgeGroup.isOccupiedUnless(SignalBoundary)：
               //     private boolean isThisOccupiedUnless(SignalBoundary b) {
               //         return !trains.isEmpty() || (reserved != null && reserved != b);
               //     }
               // —— **它不排除本车**。我们这台虚拟车就在那个 group 里，所以车头一压进下一段，
               //    那台信号机立刻变红，牌面上看就是"车到跟前 0.8 m 信号机突然红了、刹不住冲过去"
               //    （2026-11-21 23:35:55 / 23:36:08 两次实测）。
               // Create 自己放行车用的是 isOccupiedUnless(Train)（按引用排除本车），所以我们也这么判：
               //     blocked = 红石压红 || 本车之外还有人占这段区间 || 黄铜岔口上我们要走的那台是红
               String branchRed = chained ? ourBranchRed(signal, primary, train, route) : null;
               SignalProbe.State st;
               String why;
               // ===== 1.1.32：不再把"显示成红"当通行许可 =====
               // 以前这里第一条是 `if (state == RED) ⇒ BLOCKED`，1.1.31 的普查证明它是错的：
               // `SignalBoundary.tickState` 用的是 `isOccupiedUnless(SignalBoundary)`，实现是
               // `!trains.isEmpty() || reserved != null` —— **不排除本车**。所以只要我们自己
               // 占着那个区间，这台信号机对我们永远显示 RED；1.1.27 把"显示红"当许可，
               // 结果就是"我自己占的区间把我自己永久锁死"，而且那个红永远等不到消失
               // （2026-11-22 12:31~12:35 实测：1 辆车、区间里只有本车、预留=无，
               //   latching → 60 秒超时 → 挪 5 米 → 再 latching，loop）。
               //
               // Create 自己的车用的是 `group.isOccupiedUnless(train)`（按引用排除本车）——
               // 也就是说"显示红"里本来就包含"自己占的"这一种，而它在语义上**不算占用**。
               // 所以判据改成下面三条，合起来覆盖 RED 的全部三种来源：
               //   forcedRed      ← isForcedRed(side)
               //   branchRed      ← resolveSignalChain 全红（只对黄铜/岔口，且只算我们要走的那条）
               //   occupancy(...) ← !trains.isEmpty()（**排除本车**）+ 别人的预留 + 交叉区间递归
               // 唯一不再算数的就是"自占"。每次因此放行都会打一行 [sig] 自占放行留证，
               // 不是静默放过。
               if (forcedRed) {
                  st = SignalProbe.State.BLOCKED;
                  why = "红石压红";
               } else if (branchRed != null) {
                  st = SignalProbe.State.BLOCKED;
                  why = "岔口 " + branchRed;
               } else {
                  lastBlockDetail = "";
                  st = occupancy(group, train);
                  why = st == SignalProbe.State.BLOCKED ? lastBlockDetail : "";
               }
               String createSays = state == null ? "?" : state.name();
               String note = "";
               if (st != SignalProbe.State.BLOCKED
                     && (state == SignalBlockEntity.SignalState.RED || state == SignalBlockEntity.SignalState.YELLOW)) {
                  // Create 显示非绿，但排除本车后没人占、我们要走的岔口也不红
                  note = "（Create显示" + createSays + "，排除本车后没人占/本岔口不红，放行）";
               }
               lastObserveDetail = head + " 判定=" + st + (why.isEmpty() ? "" : " 原因=" + why) + note;
               if (st != SignalProbe.State.BLOCKED) {
                  if (state == SignalBlockEntity.SignalState.RED) {
                     logSelfRedPass(head, group, train);
                  } else if (state == SignalBlockEntity.SignalState.YELLOW) {
                     logYellowPass(head);
                  }
               }
               if (st != SignalProbe.State.BLOCKED) {
                  lastBlockDetail = "";
               }
               return new SignalProbe.Obs(signal, group, primary, chained, st);
            } else if (otherSide != null && !otherSide.isEmpty()) {
               // 我们这一侧没有信号方块，对面才有 ⇒ 从背面走到信号机上了，结构上不许走。
               // 该在【规划阶段】就剪掉（§14.4 的 A2）；在线上判红至少不会闯。
               lastBlockDetail = head + " 反向信号（本侧无信号方块，对面才有）";
               lastObserveDetail = head + " 判定=BLOCKED(反向)";
               return new SignalProbe.Obs(signal, group, primary, false, SignalProbe.State.BLOCKED);
            } else {
               lastBlockDetail = head + " 两侧都没有信号方块";
               lastObserveDetail = head + " 判定=UNKNOWN";
               return new SignalProbe.Obs(signal, group, primary, false, SignalProbe.State.UNKNOWN);
            }
         } catch (Throwable var81) {
            return null;
         }
      } else {
         return null;
      }
   }

   /**
   /**
    * 黄铜（CROSS_SIGNAL）的链上信号里，<b>我们要走的那条岔口</b>是不是红的。
    *
    * <p>黄灯在 Create 里的含义（{@code resolveSignalChain} 字节码）是"链上红绿混合"，
    * 也就是<b>岔口</b>：有的路能走、有的不能。所以黄灯绝不能一律拦 —— 那等于把岔口封死。
    *
    * <p>做法全部用 Create 自己的东西，不自己发明：
    * <ol>
    *   <li>{@code SignalPropagator.collectChainedSignals(graph, signal, primary)}（public）
    *       拿到这台信号机链着哪几台（UUID → 那台该用哪一侧）</li>
    *   <li>只留下<b>在本段路线上</b>的那几台（不在我们路线上的岔口与我们无关）</li>
    *   <li>看它们的 {@code cachedStates.get(side)}：只要有一台是 RED，我们这条岔口就是闭锁的</li>
    * </ol>
    *
    * <p><b>1.1.32 起 fail-closed</b>：以前"判不出来"（没有路线 / 链取不到）时返回 null
    * 就是<b>放行</b> —— 那是个真窟窿：明明不知道我们这条岔口红不红，却按绿灯过。
    * 现在改成返回一个"无法判定"的说明 ⇒ 调用方一律 BLOCKED。
    * 只有"链是空的"（= 这台信号机没链着任何东西，不可能有链上的红）才算真的不红。
    *
    * @return null = 我们这条岔口不红（放行）；非 null = 不许过的原因
    */
   private static String ourBranchRed(SignalBoundary signal, boolean primary, Train train, RailCache.Route route) {
      if (train == null || train.graph == null) {
         return "无法判定：列车/轨道图不可用";
      }
      if (route == null) {
         return "无法判定：当前没有路线（不知道要走哪条岔口）";
      }
      Map<UUID, Boolean> chained;
      try {
         chained = SignalPropagator.collectChainedSignals(train.graph, signal, primary);
      } catch (Throwable t) {
         return "无法判定：链取不到（" + t.getClass().getSimpleName() + "）";
      }
      if (chained == null || chained.isEmpty()) {
         // 这台信号机没链着任何东西 ⇒ 不存在"链上的红"，我们这条岔口必然不红
         return null;
      }
      for (Map.Entry<UUID, Boolean> e : chained.entrySet()) {
         UUID id = e.getKey();
         if (id == null || !routeHasSignal(route, id)) {
            continue;   // 别的岔口，与我们无关
         }
         SignalBoundary other;
         try {
            other = train.graph.getPoint(EdgePointType.SIGNAL, id);
         } catch (Throwable t) {
            continue;
         }
         if (other == null || other.cachedStates == null) {
            continue;
         }
         SignalBlockEntity.SignalState st = other.cachedStates.get(e.getValue());
         if (st == SignalBlockEntity.SignalState.RED) {
            return "链上 " + shortId(id) + " 是 RED";
         }
      }
      return null;
   }

   /** 这台信号机在不在本段路线上。 */
   private static boolean routeHasSignal(RailCache.Route route, UUID id) {
      for (RailCache.Hit h : route.points) {
         if (h.point instanceof SignalBoundary sb && id.equals(sb.getId())) {
            return true;
         }
      }
      return false;
   }

   /** 黄灯放行只刷一次日志（同一台不重复）。 */
   private static String lastYellowPassHead = null;

   private static void logYellowPass(String head) {
      try {
         if (head.equals(lastYellowPassHead)) {
            return;
         }
         lastYellowPassHead = head;
         HexieTrain.LOGGER.info("[sig] 黄灯放行（我们要走的岔口不红）{}", head);
      } catch (Throwable ignored) {
      }
   }

   /** 最近一次"显示红但放行"的那台信号机，只用来限流日志。 */
   private static String lastSelfRedPassHead = null;

   /**
    * <b>显示 RED 却放行</b>时留证。
    *
    * <p>这一行是 1.1.32 那条规则的审计痕迹：我们把"显示成红"从通行许可里撤掉了，
    * 理由是所有能导致 RED 的真实原因（红石压红 / 本区间被别的车占或别人预留 /
    * 我们要走的黄铜岔口是红）都已经由另外三条判据独立覆盖，而"被自己占"在
    * Create 的语义里不算占用。**但撤掉一条规则必须留下证据**：
    * 每次因此放行就把那个区间的占用者整份打出来，事后能一条条核对。
    *
    * <p>同一台信号机只打一次（按 {@code head} 去重），不会刷屏。
    */
   private static void logSelfRedPass(String head, UUID groupId, Train train) {
      try {
         if (head.equals(lastSelfRedPassHead)) {
            return;
         }
         lastSelfRedPassHead = head;
         HexieTrain.LOGGER.info("[sig] 显示红但放行（排除本车后没人占/本岔口不红）{} | 是谁: {}",
            head, TrainCensus.group(groupId, train));
      } catch (Throwable ignored) {
      }
   }
   /** 最近一次判 BLOCKED 的原因，只给人看，不参与判定（[sig] 日志靠它说清是谁堵的）。 */
   public static volatile String lastBlockDetail = "";
   /** 最近一次观察到的完整侧信息（本侧/对面各几个信号方块、primary、区间），只给人看。 */
   public static volatile String lastObserveDetail = "";

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
               // 排除「本车」用的是引用比较 —— 和 Create 自己 SignalEdgeGroup.isThisOccupiedUnless(Train) 一致。
               // 绝不能改成比 UUID：那只是让我们看不见「自己的残影」，残影照样占着区间堵别人的车。
               // 残影必须在源头清（TrainBinder.purgeVirtualTrain），不许在这里遮。
               boolean otherTrain = group.trains != null && group.trains.stream().anyMatch(other -> other != train);
               boolean reservedByOther = group.reserved != null && (train.reservedSignalBlocks == null || !train.reservedSignalBlocks.contains(id));
               // 1.2.0：**物理车**占区。没有绑定虚拟车的物理车只在 Simurail 的
               // SignalEdgeGroup.bogeys 里报名，group.trains 里根本没有它 ⇒
               // 以前我们对它完全隐形，直接开过去。见 PhysicalOccupancy 的类注释。
               boolean otherPhysical = PhysicalOccupancy.occupiedByOthers(group);
               if (!otherTrain && !reservedByOther && !otherPhysical) {
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
                  lastBlockDetail = describe(id, group, train);
                  return SignalProbe.State.BLOCKED;
               }
            } catch (Throwable var101) {
               return SignalProbe.State.UNKNOWN;
            }
         }
      }
   }

   public static String shortId(UUID id) {
      return id == null ? "?" : id.toString().substring(0, 8);
   }

   /**
    * 谁在占这个区间。{@code (残影)} = 这个 Train 对象已经不在 {@code RAILWAYS.trains} 里了
    * —— 那说明有个旧对象还在被 tick，它会永久占着区间，必须清掉。
    */
   private static String describe(UUID id, SignalEdgeGroup group, Train self) {
      try {
         StringBuilder sb = new StringBuilder("区间=");
         sb.append(id.toString(), 0, 8);
         // 1.2.0：物理车占区（没有绑定虚拟车的物理车只在这里报名）
         try {
            int phys = PhysicalOccupancy.size(group);
            sb.append(" 物理转向架=").append(phys < 0 ? "?" : String.valueOf(phys));
         } catch (Throwable ignored) {
         }
         sb.append(" 占用车=[");
         boolean first = true;
         if (group.trains != null) {
            for (Train t : group.trains) {
               if (t == null || t == self) {
                  continue;
               }
               if (!first) {
                  sb.append(',');
               }
               first = false;
               sb.append(t.id.toString(), 0, 8);
               Train live = (Train)Create.RAILWAYS.trains.get(t.id);
               if (live != t) {
                  sb.append("(残影)");
               }
            }
         }
         sb.append(']');
         if (group.reserved != null) {
            sb.append(" 预约=有");
         }
         return sb.toString();
      } catch (Throwable t) {
         return "区间=" + (id == null ? "?" : id.toString().substring(0, 8));
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
