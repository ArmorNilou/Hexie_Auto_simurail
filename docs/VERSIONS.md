# 版本记录

## 0.3.0（已存档）
- 标签：`v0.3.0-saved` / `v0.3.0-probe`
- jar：`历史版本/[和谐：模拟自动铁路]Hexie_AutoSimulateTrain0.3.0.jar`
- 能力：导航到站、停靠、信号区间闭锁、手动模式交权、应力为 0、
        全参数探针（身份/时刻表/导航/物理/输出/红石/动力学）
- 缺陷：时刻表不推进（手搓 arriveAt 且漏清 destination）；
        虚拟车与物理车位置脱节（speed=0 冻结，点不动）

## 0.4.0（实验）
- 标签：`v0.4.0-wip` → 本次实验提交
- 核心思路（照 CRH-HeXie 技术路径）：
  物理位置是唯一真相，Create 是身份/导航/信号/时刻表的执行者。
  1. `TrainSpeedInjectMixin`：`Train.tick` HEAD 注入 `speed = 物理沿轨位移/20`，
     Create 自己积分 TravellingPoint → 边点事件（到站/信号）原生触发
  2. 移除 `train.speed = 0` 冻结
  3. `BY_TRAIN`：列车 id → 调度器实例，供 mixin 查询
- 参照证据（CRH-HeXie，origin/main 83b7a1d）：
  - `CompatibilityTrain extends Train`，覆盖 `tick()` 使其不移动车厢
  - `CreateTrainBridge.afterTick()`：actualTravel → advanceNativeEvents → sync →
    `train.speed = motion.speed/20` → `updateNavigationTarget(distance)`
  - `@Accessor` 借出 Train 的 private `updateNavigationTarget` / `collideWithOtherTrains`
  - 位置由 `CreateRouteBinding.traversePoints(from,to,listener)` 补放边点事件
- 回退方法：`git checkout v0.3.0-saved -- src/main/java/willow/train/hexietrain/`
## 0.5.0（按六条规格重构，进行中）
1. 三模态：未绑定 / 玩家操控中 / 自动驾驶中（有侧面红石即玩家操控）
2. 右上角按钮切换玩家操控↔自动驾驶（未绑定不可自动）
3. 调度状态真实赋值 IDLE / RUNNING / BRAKING
4. 三个无线通道只做 刹车 / 左岔道 / 右岔道，与运行方向无关
5. 模式切换不拆车，导航可续（保留 currentEntry）
6. `assemblyId ↔ trainId` 持久绑定，独立世界存档 `<世界>/data/hexie_train_bindings.dat`

已完成：第 6 条（TrainBindingData + TrainBinder 复用 UUID）
待完成：第 1~5 条
## 0.6.0（有实证的节点，存档：`v0.6.0-bind-file`）
- jar：`历史版本/[和谐：模拟自动铁路]Hexie_AutoSimulateTrain0.6.0.jar`（80.8KB / 23:54:54）
- 已验证：
  - 服务端 tick 稳定（`[entry]` 每秒一行，level=ServerLevel）
  - 放入时刻表 → 建车 → 导航 → 到站 → `entry` 推进（0→1）→ 规划下一站
  - 取出时刻表 → 注销虚拟列车，`boundTrain` 清空
  - 绑定文件生成：`<GAMEDIR>/saves/hexie_train_bindings.txt`
    `bind.<结构UUID>=<列车UUID>`，结构 UUID 稳定（0fc19fa3-…）
  - 破坏调度器 → 删除该结构的 bind 与 state 行
- 变更：删除 DispatcherTicker 的按坐标去重（唯一化只由 TRAIN_OWNER 按列车 UUID 负责）
- 未完成：`state.*` 实时状态尚未写入；文件未落到 `<世界名>/` 子目录；方向极性判定未接回；三模态/按钮/通道语义未做