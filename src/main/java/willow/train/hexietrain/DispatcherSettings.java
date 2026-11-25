package willow.train.hexietrain;

import net.minecraft.nbt.CompoundTag;

/**
 * 调度器<b>设置项登记表</b>。
 *
 * <h2>为什么要这张表</h2>
 * 需求是"右上角一个设置栏，先放制动/节流阀输出的上下限，以后想塞什么都能塞"。
 * 所以这里把"一项设置"抽象成一条 {@link Entry}：名字、范围、默认值、NBT 键。
 * 新增一项设置 = 在 {@link #ENTRIES} 里加一行，UI 与网络收发都不用改。
 *
 * <h2>传输方式（重要边界）</h2>
 * 现在整套同步走<b>原版菜单数据槽</b>（{@code AbstractContainerMenu.addDataSlots}）+
 * {@code clickMenuButton}，<b>没有任何自定义网络包</b>（整个 mod 目前一条都没有）。
 * 好处是不用造包系统；代价是<b>数据槽只能传 int</b>。
 *
 * <p>所以：<b>只能存整数、并且必须事先知道上下限</b>的设置可以直接加进来。
 * 将来若要加字符串/枚举/任意长度（比如"下拉选择信号策略"、"目标站名"），
 * 必须先补一套 {@code CustomPacketPayload}，那时这张表要扩出类型字段。
 * 这个边界写在这里，免得以后有人以为改一行就能加字符串。
 *
 * <h2>按钮编码</h2>
 * {@code clickMenuButton(int id)} 只有一个 int，约定：
 * <pre>
 *   3                手动 / 自动切换（原有）
 *   4                清除绑定（原有）
 *   10 + 通道序号     无线输出开关（0 = 制动，1 = 节流阀）
 *   200 + 项序号*2+方向  调整设置项（方向 0 = 减，1 = 加）
 * </pre>
 * 200 之后留了足够空间（64 项），与前面 10/11 不会撞。
 */
public final class DispatcherSettings {

   /** 通道开关的按钮基址：10 = 制动，11 = 节流阀。 */
   public static final int BUTTON_TOGGLE_BASE = 10;

   /** 设置项调整的按钮基址：200 + 序号*2 + 方向。 */
   public static final int BUTTON_SETTING_BASE = 200;

   /** 一项设置的<b>元数据</b>（值不在这里，见 {@link DispatcherSettings#values}）。 */
   public static final class Entry {
      /** 落盘用的 NBT 键。改了会丢掉老存档里的这一项，慎改。 */
      public final String key;
      /** 界面上显示的名字。 */
      public final String label;
      public final int min;
      public final int max;
      public final int def;

      Entry(String key, String label, int min, int max, int def) {
         this.key = key;
         this.label = label;
         this.min = min;
         this.max = max;
         this.def = def;
      }
   }

   // ================================================================ 登记表

   /** 制动输出下限（0..15 的 15 级编码）。 */
   public static final Entry BRAKE_MIN = new Entry("BrakeMin", "制动下限", 0, 15, 0);
   /** 制动输出上限。 */
   public static final Entry BRAKE_MAX = new Entry("BrakeMax", "制动上限", 0, 15, 15);
   /** 节流阀输出下限。 */
   public static final Entry THROTTLE_MIN = new Entry("ThrottleMin", "节流下限", 0, 15, 0);
   /** 节流阀输出上限。 */
   public static final Entry THROTTLE_MAX = new Entry("ThrottleMax", "节流上限", 0, 15, 15);
   /**
    * 转向架制动开关（0 = 关，1 = 开，默认开）。
    *
    * <p>开：把调度器的制动指令写进 Simurail 物理转向架（{@link BogeyBraking}）。
    * 关：清掉遥控制动条目，物理车只听 Create 那边的。
    */
   public static final Entry BOGEY_BRAKE = new Entry("BogeyBrake", "转向架制动", 0, 1, 1);

   /**
    * <b>往这里加就是加一项设置。</b>
    *
    * <p>阶段 2/3 会把它们接进右上角设置栏；阶段 4 会在这里再加一条
    * {@code BogeyBrake}（转向架制动开关）。
    */
   public static final Entry[] ENTRIES =
      new Entry[]{BRAKE_MIN, BRAKE_MAX, THROTTLE_MIN, THROTTLE_MAX, BOGEY_BRAKE};

   // ================================================================ 值

   private final int[] values = new int[ENTRIES.length];

   public DispatcherSettings() {
      this.reset();
   }

   public void reset() {
      for (int i = 0; i < ENTRIES.length; i++) {
         this.values[i] = ENTRIES[i].def;
      }
   }

   public int count() {
      return ENTRIES.length;
   }

   public int get(int index) {
      return index >= 0 && index < this.values.length ? this.values[index] : 0;
   }

   /** 按 Entry 取（同包内用，语义比序号清楚）。 */
   public int get(Entry entry) {
      for (int i = 0; i < ENTRIES.length; i++) {
         if (ENTRIES[i] == entry) {
            return this.values[i];
         }
      }
      return entry == null ? 0 : entry.def;
   }

   /** 直接设值（会 clamp）。返回是否真的变了。 */
   public boolean set(int index, int value) {
      if (index < 0 || index >= this.values.length) {
         return false;
      }
      Entry e = ENTRIES[index];
      int v = Math.max(e.min, Math.min(e.max, value));
      if (this.values[index] == v) {
         return false;
      }
      this.values[index] = v;
      return true;
   }

   /** 步进（设置栏上点 +/-）。返回是否真的变了。 */
   public boolean adjust(int index, int delta) {
      return this.set(index, this.get(index) + delta);
   }

   // ================================================================ 落盘

   public CompoundTag save() {
      CompoundTag tag = new CompoundTag();
      for (int i = 0; i < ENTRIES.length; i++) {
         tag.putInt(ENTRIES[i].key, this.values[i]);
      }
      return tag;
   }

   public void load(CompoundTag tag) {
      if (tag == null) {
         return;
      }
      for (int i = 0; i < ENTRIES.length; i++) {
         Entry e = ENTRIES[i];
         this.values[i] = tag.contains(e.key) ? Math.max(e.min, Math.min(e.max, tag.getInt(e.key))) : e.def;
      }
   }

   /** 一行摘要，给日志和调试用。 */
   public String describe() {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < ENTRIES.length; i++) {
         if (sb.length() > 0) {
            sb.append(' ');
         }
         sb.append(ENTRIES[i].key).append('=').append(this.values[i]);
      }
      return sb.toString();
   }
}
