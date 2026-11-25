package willow.train.hexietrain.client;

import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import willow.train.hexietrain.TrainDispatcherBlockEntity;
import willow.train.hexietrain.TrainDispatcherMenu;

/**
 * 调度器界面。
 *
 * <p>0.62.0 版式（自上而下）：
 * <pre>
 *   标题 / 状态徽章
 *   时刻表槽 + 目标站名
 *   ──────────────────────────────
 *   绑定了 N 个转向架              ← 转向架绑定信息（占用原来"制动"那一行）
 *   制动    [频][频]  ▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮   ← 从第 54 行下移到第 74 行
 *   节流阀  [频][频]  ▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮
 *   调度：xx            距站：xx m
 *   ──────────────────────────────
 *   物品栏                        0.0 m/s
 *   背包 / 快捷栏
 * </pre>
 *
 * <p>原来的"左道岔 / 右道岔"一行（4 个无线频率槽）已经整行删除：道岔转向不再走红石，
 * 由 {@link willow.train.hexietrain.BogeySteering} 逐转向架直接写 Simurail 的遥控值。
 */
public class TrainDispatcherScreen extends AbstractContainerScreen<TrainDispatcherMenu> {
   private static final int PANEL_W = 176;
   private static final int PANEL_H = 230;
   private static final int C_BORDER = -12763843;
   private static final int C_PANEL = -3750202;
   private static final int C_PANEL_LIGHT = -2171170;
   private static final int C_PANEL_DARK = -7631989;
   private static final int C_HEADER = -4934476;
   private static final int C_SLOT_BG = -7631989;
   private static final int C_SLOT_INNER = -13158601;
   private static final int C_TEXT = -12763843;
   private static final int C_TEXT_DIM = -9539986;
   private static final int C_CELL_OFF = -7631989;
   private static final int C_CELL_FRAME = -10855846;
   /** 两行频段：0 = 制动，1 = 节流阀。 */
   private static final int[] ROW_Y = new int[]{74, 94};
   private static final String[] ROW_NAMES = new String[]{"制动", "节流阀"};
   /** 转向架绑定信息占的两行 y。 */
   private static final int INFO_Y1 = 52;
   private static final int INFO_Y2 = 62;
   /** "清除绑定"按钮：放在"绑定了 N 个转向架"右边。 */
   private static final int CLEAR_X = 120;
   private static final int CLEAR_Y = 50;
   private static final int CLEAR_W = 48;
   private static final int CLEAR_H = 12;
   private static final int CELLS_X = 86;
   private static final int CELL_W = 4;
   private static final int CELL_GAP = 1;
   private static final int CELL_COUNT = 15;
   /**
    * 无线输出开关：画在 15 格输出条右端。
    *
    * <p>15 格条从 {@code CELLS_X=86} 起，每格 5px（4 格 + 1 间隔），最后一格右边缘在 160，
    * 面板宽 176，所以 162..172 这 10px 是空的，正好放开关。
    */
   private static final int TOGGLE_X = 162;
   private static final int TOGGLE_W = 10;
   private static final int TOGGLE_H = 8;

   /*
    * 无线开关配色：绿 = 开启（默认），红 = 关闭。
    * 之前用的 -11974327 / -10569647 其实都是灰色（0xFF494A49 / 0xFF5E5E5E），
    * 开和关只差一档亮度，根本分不出来 —— 这里改成明确色相。
    * 悬停不换色相，只把同一色相提亮（往白里拉），避免"亮了=状态变了"的误解。
    */
   private static final int C_TOGGLE_FRAME = 0xFF2B2B2B;
   private static final int C_TOGGLE_FRAME_HOVER = 0xFFE8E8E8;
   /** 开启：绿 */
   private static final int C_TOGGLE_ON = 0xFF3FA34D;
   private static final int C_TOGGLE_ON_HOVER = 0xFF5FD37A;
   /** 关闭：红 */
   private static final int C_TOGGLE_OFF = 0xFFC0392B;
   private static final int C_TOGGLE_OFF_HOVER = 0xFFE8695A;
   private int browseIndex = -1;

   public TrainDispatcherScreen(TrainDispatcherMenu menu, Inventory playerInventory, Component title) {
      super(menu, playerInventory, title);
      this.imageWidth = 176;
      this.imageHeight = 230;
   }

   protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
      int x = this.leftPos;
      int y = this.topPos;
      g.fill(x, y, x + 176, y + 230, C_PANEL);
      g.fill(x + 1, y + 1, x + 176 - 1, y + 16, C_HEADER);
      g.fill(x + 1, y + 1, x + 176 - 1, y + 2, C_PANEL_LIGHT);
      g.fill(x + 1, y + 1, x + 2, y + 230 - 1, C_PANEL_LIGHT);
      g.fill(x + 1, y + 230 - 2, x + 176 - 1, y + 230 - 1, C_PANEL_DARK);
      g.fill(x + 176 - 2, y + 1, x + 176 - 1, y + 230 - 1, C_PANEL_DARK);
      g.renderOutline(x - 1, y - 1, 178, 232, C_BORDER);

      // 时刻表槽
      this.slotWell(g, x + 13, y + 21);
      // 两行频段的频率槽
      for (int row = 0; row < ROW_Y.length; row++) {
         this.slotWell(g, x + 39, y + ROW_Y[row] - 1);
         this.slotWell(g, x + 59, y + ROW_Y[row] - 1);
      }

      // 目标站名条
      g.fill(x + 37, y + 20, x + 169, y + 40, C_PANEL_DARK);
      g.fill(x + 38, y + 21, x + 168, y + 39, C_SLOT_INNER);
      g.fill(x + 39, y + 22, x + 167, y + 38, C_HEADER);
      g.fill(x + 8, y + 46, x + 176 - 8, y + 47, C_PANEL_DARK);
      g.fill(x + 8, y + 47, x + 176 - 8, y + 48, C_PANEL_LIGHT);

      // 两行 15 格输出条底框
      for (int row = 0; row < ROW_Y.length; row++) {
         int cy = y + ROW_Y[row] + 4;
         for (int i = 0; i < CELL_COUNT; i++) {
            int cx = x + CELLS_X + i * (CELL_W + CELL_GAP);
            g.fill(cx - 1, cy - 1, cx + CELL_W + 1, cy + 9, C_CELL_FRAME);
         }
      }

      for (int invRow = 0; invRow < 3; invRow++) {
         for (int col = 0; col < 9; col++) {
            this.slotWell(g, x + 7 + col * 18, y + 145 + invRow * 18);
         }
      }

      for (int col = 0; col < 9; col++) {
         this.slotWell(g, x + 7 + col * 18, y + 203);
      }

      g.fill(x + 8, y + 134, x + 176 - 8, y + 135, C_PANEL_DARK);
      g.fill(x + 8, y + 135, x + 176 - 8, y + 136, C_PANEL_LIGHT);
   }

   private void slotWell(GuiGraphics g, int x, int y) {
      g.fill(x, y, x + 18, y + 18, C_PANEL_DARK);
      g.fill(x + 1, y + 1, x + 17, y + 17, C_SLOT_INNER);
      g.fill(x + 2, y + 2, x + 16, y + 16, C_PANEL_DARK);
   }

   protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
      TrainDispatcherMenu menu = this.menu;
      g.drawString(this.font, this.title, 8, 4, C_TEXT, false);

      String badge;
      if (menu.unbound()) {
         badge = "未绑定";
      } else if (menu.manualMode()) {
         badge = "玩家操控中";
      } else {
         badge = "自动驾驶中";
      }

      int badgeColor = menu.manualMode() ? -4955350 : (!menu.bound() ? C_PANEL_DARK : -13730510);
      boolean badgeHover = isIn(mouseX, mouseY, 102, 1, 70, 15);
      g.drawString(this.font, badge, 168 - this.font.width(badge), 4, badgeHover ? -14721089 : badgeColor, false);

      this.drawDestination(g);

      // ---- 车厢 / 转向架绑定信息（原来"制动"那一行）----
      // 车厢数去掉了：整列车常常就是一个 Sable 子层，"1 车厢"永远是 1，没有信息量。
      // 车辆ID 也不再重复显示 —— 它现在在手持连接器的屏幕提示里（见 ConnectorHudRenderer）。
      TrainDispatcherBlockEntity be = menu.blockEntity();
      int bogeys = be == null ? 0 : be.roster().size();
      g.drawString(this.font, "绑定了 " + bogeys + " 个转向架", 8, INFO_Y1,
         bogeys > 0 ? -11862016 : -2047488, false);
      if (bogeys <= 0) {
         g.drawString(this.font, "潜行右键调度器选中，再右键转向架绑定", 8, INFO_Y2, C_TEXT_DIM, false);
      }
      this.drawClearButton(g, bogeys, mouseX, mouseY);

      // ---- 制动 / 节流阀 ----
      for (int row = 0; row < ROW_Y.length; row++) {
         g.drawString(this.font, ROW_NAMES[row], 2, ROW_Y[row] + 4, C_TEXT, false);
         int value = row == 0 ? menu.brakeOutput() : menu.throttleOutput();
         value = Math.max(0, Math.min(CELL_COUNT, value));
         int cy = ROW_Y[row] + 4;

         for (int i = 0; i < CELL_COUNT; i++) {
            int cx = CELLS_X + i * (CELL_W + CELL_GAP);
            boolean lit = i < value;
            int color = lit ? litColor(i) : C_CELL_OFF;
            g.fill(cx, cy, cx + CELL_W, cy + 8, color);
            if (lit) {
               g.fill(cx, cy, cx + CELL_W, cy + 1, -8739);
            }
         }
      }

      // ---- 无线输出开关（画在 15 格条右端）：关掉 = 这个通道不发红石，但需求值照显示 ----
      int mx = mouseX - this.leftPos;
      int my = mouseY - this.topPos;
      for (int row = 0; row < ROW_Y.length; row++) {
         this.drawWirelessToggle(g, row, mx, my);
      }

      // ---- 调度状态 / 距站 ----
      if (menu.bound() || menu.stopCount() > 0) {
         String[] stateNames = new String[]{"待命", "行驶中", "滑车", "急刹"};
         int[] stateColors = new int[]{C_TEXT_DIM, -13730510, -4684277, -5242848};
         int stIdx = Math.max(0, Math.min(stateNames.length - 1, menu.dispatcherState()));
         g.drawString(this.font, "调度：" + stateNames[stIdx], 8, 112, stateColors[stIdx], false);
         if (menu.arrived()) {
            g.drawString(this.font, "距站：到站", 8, 124, -12599489, false);
         } else {
            // 1.2.0：距站三态。以前"寻路失败"和"等待信号开放"都显示成 `--`，
            // 看不出是"还没规划出来"还是"规划好了在等灯"，得去翻日志。
            // 状态由 BE.uiDistanceState() 给（0=正常 / 1=寻路失败 / 2=等待信号开放）。
            int distState = menu.distanceState();
            int mil = menu.mileage();
            String txt;
            int color;
            if (distState == TrainDispatcherMenu.DIST_STATE_PLAN_FAILED) {
               txt = "距站：寻路失败";
               color = -35072;
            } else if (distState == TrainDispatcherMenu.DIST_STATE_WAIT_SIGNAL) {
               txt = "距站：等待信号";
               color = -171;
            } else if (!menu.probeOk() || mil < 0) {
               txt = "距站：--";
               color = -10496;
            } else {
               txt = String.format("距站：%.1f m", (double)mil / 10.0);
               color = -10496;
            }
            g.drawString(this.font, txt, 8, 124, color, false);
         }
      }

      g.drawString(this.font, "物品栏", 8, 136, C_TEXT_DIM, false);
      String sensor = String.format("%.1f m/s", (double)menu.sensorSpeedCenti() / 100.0);
      g.drawString(this.font, sensor, 168 - this.font.width(sensor), 136, -14721089, false);
   }


   /** 目标站名（可滚轮浏览）。 */
   private void drawDestination(GuiGraphics g) {
      TrainDispatcherBlockEntity be = this.menu.blockEntity();
      List<String> names = be == null ? List.of() : be.stopNames();
      int dest = this.menu.currentEntry();
      int cnt = names.size();
      if (cnt > 0) {
         if (this.browseIndex < 0 || this.browseIndex >= cnt) {
            this.browseIndex = Math.max(0, Math.min(cnt - 1, dest));
         }

         String name = names.get(this.browseIndex);
         int color;
         if (this.browseIndex == dest) {
            color = -10496;
         } else if (this.browseIndex > dest) {
            color = -12599489;
         } else {
            color = C_PANEL_DARK;
         }

         int maxW = 122;
         String shown = name == null ? "" : name;
         if (this.font.width(shown) > maxW) {
            while (shown.length() > 1 && this.font.width(shown + "...") > maxW) {
               shown = shown.substring(0, shown.length() - 1);
            }

            shown = shown + "...";
         }

         g.drawString(this.font, shown, 42, 27, color, false);
      } else {
         String none = be != null && be.nextTargetName() != null && !be.nextTargetName().isEmpty()
            ? be.nextTargetName() : "（无目标）";
         g.drawString(this.font, none, 42, 27, C_TEXT_DIM, false);
      }
   }

   /**
    * "清除绑定"按钮：画在"绑定了 N 个转向架"右边。
    * <p>只有真的绑了东西才点亮；没绑时是个死按钮，点了不发包。
    */
   private void drawClearButton(GuiGraphics g, int bogeys, int mouseX, int mouseY) {
      boolean enabled = bogeys > 0;
      boolean hover = enabled && isIn(mouseX, mouseY, CLEAR_X, CLEAR_Y, CLEAR_W, CLEAR_H);
      int frame = enabled ? (hover ? -14721089 : C_CELL_FRAME) : C_PANEL_DARK;
      int fill = hover ? C_SLOT_INNER : C_HEADER;
      g.fill(CLEAR_X, CLEAR_Y, CLEAR_X + CLEAR_W, CLEAR_Y + CLEAR_H, frame);
      g.fill(CLEAR_X + 1, CLEAR_Y + 1, CLEAR_X + CLEAR_W - 1, CLEAR_Y + CLEAR_H - 1, fill);
      String label = "清除绑定";
      int color = enabled ? (hover ? -8739 : C_TEXT) : C_TEXT_DIM;
      g.drawString(this.font, label, CLEAR_X + (CLEAR_W - this.font.width(label)) / 2,
         CLEAR_Y + 2, color, false);
   }

   /**
    * 画一个通道的无线输出开关：开=绿、关=暗，悬停时提亮。
    *
    * <p>注意：{@code renderLabels} 拿到的 mouseX/mouseY 是<b>屏幕坐标</b>
    * （本类没有重写 render，原版是直接透传的），所以这里必须先减 leftPos/topPos
    * 才能和面板坐标比。{@code mouseClicked} 里本来就是减过的。
    */
   private void drawWirelessToggle(GuiGraphics g, int row, int mouseX, int mouseY) {
      boolean on = this.menu.wirelessOn(row);
      int x = TOGGLE_X;
      int y = ROW_Y[row] + 4;
      boolean hover = isIn(mouseX, mouseY, x, y, TOGGLE_W, TOGGLE_H);
      int frame = hover ? C_TOGGLE_FRAME_HOVER : C_TOGGLE_FRAME;
      int fill;
      if (on) {
         fill = hover ? C_TOGGLE_ON_HOVER : C_TOGGLE_ON;
      } else {
         fill = hover ? C_TOGGLE_OFF_HOVER : C_TOGGLE_OFF;
      }
      g.fill(x, y, x + TOGGLE_W, y + TOGGLE_H, frame);
      g.fill(x + 1, y + 1, x + TOGGLE_W - 1, y + TOGGLE_H - 1, fill);
   }

   private static int litColor(int index) {
      int gray = 90 * (14 - index) / 14;
      return -65536 | gray << 8 | gray;
   }

   private static boolean isIn(int mx, int my, int x, int y, int w, int h) {
      return mx >= x && mx < x + w && my >= y && my < y + h;
   }

   public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
      int x = (int)(mouseX - (double)this.leftPos);
      int y = (int)(mouseY - (double)this.topPos);
      TrainDispatcherBlockEntity be = this.menu.blockEntity();
      int cnt = be == null ? 0 : be.stopNames().size();
      if (cnt > 0 && isIn(x, y, 38, 20, 130, 20)) {
         this.browseIndex += scrollY < 0.0 ? 1 : -1;
         if (this.browseIndex < 0) {
            this.browseIndex = cnt - 1;
         }

         if (this.browseIndex >= cnt) {
            this.browseIndex = 0;
         }

         return true;
      } else {
         return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
      }
   }

   public boolean mouseClicked(double mouseX, double mouseY, int button) {
      int x = (int)(mouseX - (double)this.leftPos);
      int y = (int)(mouseY - (double)this.topPos);
      if (button == 0 && this.minecraft != null && this.minecraft.gameMode != null) {
         // 清除绑定（按钮 id 4）：没绑东西时是死按钮，连包都不发
         if (this.menu.bogeyCount() > 0 && isIn(x, y, CLEAR_X, CLEAR_Y, CLEAR_W, CLEAR_H)) {
            this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, 4);
            return true;
         }
         // 无线输出开关（按钮 id 10 + 通道序号）
         for (int row = 0; row < ROW_Y.length; row++) {
            if (isIn(x, y, TOGGLE_X, ROW_Y[row] + 4, TOGGLE_W, TOGGLE_H)) {
               this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId,
                  willow.train.hexietrain.DispatcherSettings.BUTTON_TOGGLE_BASE + row);
               return true;
            }
         }
         // 手动 / 自动 切换（按钮 id 3）
         if (isIn(x, y, 102, 1, 70, 15)) {
            this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, 3);
            return true;
         }
      }
      return super.mouseClicked(mouseX, mouseY, button);
   }
}
