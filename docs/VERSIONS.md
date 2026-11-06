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