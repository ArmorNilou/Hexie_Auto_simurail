# 调度器 UI 设置栏 · 设计与契约

> 阶段 0 产物。后面阶段 1/2/3/4 都按这份契约实现，改这里之前先想清楚会不会破坏存档兼容。

## 1. 要做的事

| 阶段 | 内容 | 状态 |
|---|---|---|
| 0 | 设置项登记表 + 按钮 id 编码 + 数据槽预算 + 本文档 | **已完成** |
| 1 | 制动 / 节流阀两路的**无线输出开关**（画在 15 格条右端） | **已完成** |
| 2 | 右上角 ⚙ 设置栏（展开/收起、登记表驱动渲染、±调整） | 待做 |
| 3 | 上下限真的作用到输出（0..15 重映射到 `[min,max]`） | **已完成** |
| 4 | 转向架制动接入（`setRemoteBrakeOverride`，每 tick 刷新） | **已完成** |

## 2. 传输通道选型（**硬边界，先记住**）

整个 mod **目前一条自定义网络包都没有**。所以同步用的是原版机制：

- **服务器 → 客户端**：`AbstractContainerMenu.addDataSlots(ContainerData)`，靠 `cached[]` 数组。
- **客户端 → 服务器**：`this.minecraft.gameMode.handleInventoryButtonClick(containerId, id)`
  → 服务器侧 `Menu.clickMenuButton(player, id)`。

**代价：数据槽只能传 `int`。**

所以：

- ✅ 能加：整数、有界、事先知道范围的设置（上下限、开关、枚举序号、0..15 级输出…）
- ❌ 不能加：字符串、任意长列表、需要动态长度的东西
  （"目标站名""下拉选择策略"这类要**先补一套 `CustomPacketPayload`**，那时这张表要扩出类型字段）

这条边界写死在这里，免得以后有人以为改一行登记表就能加字符串。

## 3. 按钮 id 编码表

`clickMenuButton(int id)` 只有一个 int，所以约定：

| id | 含义 |
|---|---|
| 3 | 手动 / 自动切换（原有） |
| 4 | 清除绑定（原有） |
| 10 | 无线输出开关：制动通道 |
| 11 | 无线输出开关：节流阀通道 |
| `200 + 项序号*2 + 0` | 该设置项 **减 1** |
| `200 + 项序号*2 + 1` | 该设置项 **加 1** |

`200` 之后留 64 项的空间，和 10/11 不会撞。

## 4. 数据槽分配表

| 槽 | 内容 |
|---|---|
| 0 | 制动输出（需求值 0..15） |
| 1 / 2 | 左 / 右（占位） |
| 3 | 未绑定 |
| 4 | 已绑定 |
| 5 | 时刻表站数 |
| 7 / 8 | 距站 / 里程 |
| 9 | 节流阀输出（需求值 0..15） |
| 10 | 已到站 |
| 11 | 手动模式 |
| 12 | 当前 entry |
| 13 | 距站三态 |
| 14 | 调度状态 |
| 15 | 探针测速 |
| 16 / 17 | 探针 OK / 探针反向 |
| 18 / 19 | 绑定转向架数 / 车厢数 |
| **20** | **无线开关位掩码**（位0=制动，位1=节流阀） |
| **21 + i** | **设置项 i**（顺序见 `DispatcherSettings.ENTRIES`） |
| 25..31 | 预留给阶段 2/4 新增设置项 |
| 32 | `DATA_COUNT`（上限） |

## 5. 怎么加一项设置（三步）

1. 在 `DispatcherSettings` 里加一个 `Entry`，并塞进 `ENTRIES`：
   ```java
   public static final Entry BOGEY_BRAKE = new Entry("BogeyBrake", "转向架制动", 0, 1, 1);
   ```
2. `TrainDispatcherMenu.refresh()` 里那段循环会**自动**把它写进 `DATA_SETTING_BASE + i`，不用改。
3. 服务器侧用 `blockEntity.settings().get(BOGEY_BRAKE)` 读；UI 侧用 `menu.settingValue(i)` 读。

**不用改**：按钮编码、数据槽常量、网络收发。

## 6. 阶段 1 的实现要点

- **只拦"往外发"**：`TrainDispatcherBlockEntity.transmittedStrength(ch)` = 开关开 ? `output(ch)` : 0。
  `output(ch)` **保持不变**，因为 UI 的 15 格条显示的是"需求值"——关掉通道不该让操作员看不到自己下了多少。
- `WirelessChannel.getTransmittedStrength()` 调 `transmittedStrength()`。
- 翻转开关必须 `pushWireless(ch)`：Create 的 `RedstoneLinkNetworkHandler` **没有 tick()**，
  只有 `updateNetworkOf` 被调时才会重读 `getTransmittedStrength` 并广播。不推这一下，外面一直读旧值。
- 开关落盘进 `DispatcherStore` 的独立 NBT（`WirelessBrake` / `WirelessThrottle` / `Settings`），
  和频率槽、时刻表游标放在同一个文件里（`hexietrain_dispatchers/<assemblyId>.nbt`）。

## 7. 验收

**阶段 1**

1. 打开调度器界面，15 格条右端应看到两个小方块（绿 = 开）。
2. 点击 → 变暗 → 日志出现 `[wireless] BRAKE 通道 关（需求=N 实发=0）`。
3. 此时用红石链接收端看该频率：读到 0；15 格条**仍然**显示需求值。
4. 再点一次 → 恢复，接收端立刻回到原值（靠 `pushWireless`）。
5. 退出重进世界 → 开关状态保持。

## 8. 阶段 3 实现要点（上下限真的生效）

- **只改"出去的那个数"**：`output()` 仍是**需求值**（0..15），新增 `outputMapped()` 做映射。
  原因：状态机里有 `output(BRAKE) > 0` 之类的判断，若让 `output()` 返回映射值，
  下限设成非 0 时它会**恒大于 0**，把状态机带偏。
- `transmittedStrength()` = 开关开 ? `outputMapped()` : 0。
- 15 格条也改成显示 `outputMapped()` —— 否则设了上限 10 却看到格子亮到 15，会以为上限没生效。
- 改上下限时必须 `pushWireless` 两个通道，否则红石网络不会重读。
- **下限是真兜底**：制动下限 3 ⇒ 即使需求 0，实际也输出 3，车一直带闸。这是字面语义，不是 bug。

## 9. 阶段 4 实现要点（转向架制动）

`BogeyBraking`，三条从 Simurail 源码读出来的硬语义：

1. **条目只活 3 拍**（`currentTime - pair.leftLong() > 2` 就删）⇒ 非 0 值**必须每 tick 重写**。
2. **发 0 等于删条目**（`remoteBrakeOverrides.remove`）⇒ 松闸发一次 0，不是每拍发小正数。
3. **接收端会平方**（`getRemoteBrakeStrength() = square(clamp(v/15,0,1))`）
   ⇒ 想要制动力 b，要发 `15*sqrt(b)`，否则只会拿到平方后的小值。

**硬约束**：`getBrakeStrength()` 是 `Floats.max(本地红石, 遥控, CC, 导航制动)`
⇒ **远程制动只能让车更刹，不能让车更松**。

**开关**：设置栏里的「转向架制动」（`BogeyBrake`，0/1，默认 1）。

**与转向不冲突**：转向走 `setCurrentPath`，制动走 `setRemoteBrakeOverride`，
在 Simurail 里是**两张独立的表**（`currentPath` 字段 / `remoteBrakeOverrides` 映射），
都拿 `worldPosition` 当 anchor，互不覆盖。