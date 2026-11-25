package willow.train.hexietrain.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import willow.train.hexietrain.DispatcherSettings;
import willow.train.hexietrain.TrainDispatcherMenu;

/**
 * 调度器设置界面（右上角齿轮打开），再按一次齿轮回调度器主界面。
 *
 * <h2>为什么是独立 Screen 而不是浮层</h2>
 * 主面板宽 176 已经排满（时刻表 / 频率槽 / 15 格输出条 / 物品栏），浮层叠上去要么盖住内容，
 * 要么只能画到面板外面显得很挤。用户要求"打开一个新的 UI 界面，再按一次回来"，所以做成独立 Screen。
 *
 * <h2>为什么换 Screen 不会关掉容器</h2>
 * 关容器发生在 {@link Screen#onClose()}（原版 {@code AbstractContainerScreen.onClose()}
 * 里调 {@code player.closeContainer()}）。这里用 {@link net.minecraft.client.Minecraft#setScreen}
 * 直接替换，只会调旧 Screen 的 {@code removed()}，<b>不会</b>调 {@code onClose()}
 * —— 所以 {@code player.containerMenu} 一直是那个 {@link TrainDispatcherMenu}，
 * 数据槽照常由 {@code broadcastChanges()} 同步，{@code handleInventoryButtonClick} 也照常有效。
 *
 * <h2>数据来源</h2>
 * 全部从 {@link TrainDispatcherMenu} 的数据槽读（{@code settingValue(i)}），
 * 每帧重读，所以服务器一改这边立刻能看到，不需要额外包。
 *
 * <h2>按钮</h2>
 * {@code -/+} 走 {@code clickMenuButton(200 + 项序号*2 + 0/1)}，和浮层时代同一套编码。
 */
public class DispatcherSettingsScreen extends Screen {

   private static final int PANEL_W = 214;
   private static final int HEAD_H = 30;
   private static final int ROW_H = 22;
   private static final int FOOT_H = 30;

   // 配色跟调度器主界面一致（浅灰面板 + 深灰字）
   private static final int C_BORDER = 0xFF3E3E3E;
   private static final int C_PANEL = 0xFFC6C6C6;
   private static final int C_HEADER = 0xFFB4B4B4;
   private static final int C_ROW = 0xFFD8D8D8;
   private static final int C_TEXT = 0xFF3E3E3E;
   private static final int C_TEXT_DIM = 0xFF6E6E6E;
   private static final int C_VALUE = 0xFF1B5E20;

   /** 齿轮按钮（和主界面同一个位置规格，按它返回）。 */
   private static final int GEAR_W = 15;
   private static final int GEAR_H = 13;

   private final Screen parent;
   private final TrainDispatcherMenu menu;
   private final int panelH;

   private int left;
   private int top;

   public DispatcherSettingsScreen(Screen parent, TrainDispatcherMenu menu) {
      super(Component.literal("调度器设置"));
      this.parent = parent;
      this.menu = menu;
      this.panelH = HEAD_H + this.menu.settingCount() * ROW_H + FOOT_H;
   }

   protected void init() {
      this.left = (this.width - PANEL_W) / 2;
      this.top = (this.height - this.panelH) / 2;

      for (int i = 0; i < this.menu.settingCount(); i++) {
         final int base = DispatcherSettings.BUTTON_SETTING_BASE + i * 2;
         int y = this.top + HEAD_H + i * ROW_H + 2;
         this.addRenderableWidget(Button.builder(Component.literal("-"), b -> this.send(base))
            .bounds(this.left + PANEL_W - 62, y, 22, 18).build());
         this.addRenderableWidget(Button.builder(Component.literal("+"), b -> this.send(base + 1))
            .bounds(this.left + PANEL_W - 36, y, 22, 18).build());
      }

      this.addRenderableWidget(Button.builder(Component.literal("返回调度器"), b -> this.returnToDispatcher())
         .bounds(this.left + (PANEL_W - 96) / 2, this.top + this.panelH - FOOT_H + 6, 96, 18).build());
   }

   private void send(int id) {
      if (this.minecraft != null && this.minecraft.gameMode != null) {
         this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, id);
      }
   }

   private void returnToDispatcher() {
      if (this.minecraft != null) {
         this.minecraft.setScreen(this.parent);
      }
   }

   /** ESC 也回调度器，而不是把整个容器关掉。 */
   public void onClose() {
      this.returnToDispatcher();
   }

   public boolean isPauseScreen() {
      return false;
   }

   private int gearX() {
      return this.left + PANEL_W - GEAR_W - 4;
   }

   private int gearY() {
      return this.top + 6;
   }

   public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
      this.renderBackground(g, mouseX, mouseY, partialTick);

      // 面板
      g.fill(this.left - 1, this.top - 1, this.left + PANEL_W + 1, this.top + this.panelH + 1, C_BORDER);
      g.fill(this.left, this.top, this.left + PANEL_W, this.top + this.panelH, C_PANEL);
      g.fill(this.left + 1, this.top + 1, this.left + PANEL_W - 1, this.top + 22, C_HEADER);
      g.drawString(this.font, "调度器设置", this.left + 6, this.top + 7, C_TEXT, false);

      // 齿轮（≡）：再按一次回去
      int gx = this.gearX();
      int gy = this.gearY();
      boolean gearHover = mouseX >= gx && mouseX < gx + GEAR_W && mouseY >= gy && mouseY < gy + GEAR_H;
      g.fill(gx, gy, gx + GEAR_W, gy + GEAR_H, gearHover ? 0xFFE8E8E8 : 0xFF2B2B2B);
      g.fill(gx + 1, gy + 1, gx + GEAR_W - 1, gy + GEAR_H - 1, gearHover ? C_HEADER : C_PANEL);
      int barColor = gearHover ? 0xFFFF8800 : C_TEXT;
      for (int k = 0; k < 3; k++) {
         int by = gy + 4 + k * 3;
         g.fill(gx + 3, by, gx + GEAR_W - 3, by + 1, barColor);
      }

      // 每项一行
      for (int i = 0; i < this.menu.settingCount(); i++) {
         int ry = this.top + HEAD_H + i * ROW_H;
         g.fill(this.left + 4, ry, this.left + PANEL_W - 4, ry + ROW_H - 2, C_ROW);
         g.drawString(this.font, this.menu.settingLabel(i), this.left + 10, ry + 6, C_TEXT, false);
         String v = String.valueOf(this.menu.settingValue(i));
         g.drawString(this.font, v, this.left + PANEL_W - 78 - this.font.width(v), ry + 6, C_VALUE, false);
      }

      g.drawString(this.font, "改的是输出上下限（0..15）；阶段 3 才会接到实际输出上",
         this.left + 8, this.top + this.panelH - FOOT_H - 2, C_TEXT_DIM, false);

      // 按钮最后画，保证盖在上面
      super.render(g, mouseX, mouseY, partialTick);
   }
}
