# 原生配置路线：完整替代范围与验收清单

本清单用于保持研究目标完整，不把单个开关的成功当成全部修复完成。
2026-11-06 当前只批准并写入了配对集成开关；其余原生写入需另行授权。
实验记录见 [阶段报告](native-override-progress-20261106.md)。

## 分项审计

| 现有路径 | 原生替代及应验证的证据 | 当前状态 |
| --- | --- | --- |
| SPOT 集成门控 | 官方 override；停用对应 Hook 后底层 getter、门面均 true；24 小时后、新进程及配置更新后仍有效 | 初始实机通过；计时观察中 |
| Fast Pair accessories 门控 | `EnableFindMyDeviceModule__enable_fast_pair_accessories` 原生 true；原始 SPOT binder 探测可用 | 尚未原生写入或验证 |
| 自定位上报门控 | `EnableFindMyDeviceModule__enable_self_location_reporting` 原生值、原生设置入口、真实上报结果 | 不在本阶段写入授权内，未验证 |
| 网页动作门控 | `EnableFindMyDeviceModule__enable_spot_client_actions_handler`；既有 Tag 响铃与新设备/缓存冷启动分开验证 | 用户实测已配对 Tag 响铃可不依赖版本适配；不能据此证明所有动作路径 |
| Finder 复合门控 | 当前反编译为 `(self && offline_beacon) || accessories`；若 accessories 原生 true，验证未 Hook 的复合门控自然 true | 代码层推导，仍缺停 Hook 的实机证据 |
| Xiaomi Tag 资格 bypass | 原始资格方法仍有集成、EDDYSTONE_TRACKING 能力和 binder 可用性分支；必须在 bypass 关闭时自然合格 | 尚未验证。不可只把返回值强制设成 15 来证明原生成功 |
| 组件启用保护 | 检查 HalfSheet 等必要组件实际启用状态及原生配置后的变化 | 单独验证；该 Hook 使用 Android 稳定 API，不是混淆入口，但可能仍需替代 |
| 账号 Find Hub 网络设置 | 原生 flags 生效后，通过原生 Find Hub 设置 UI 完成/读取账号状态；停用混淆客户端同步后仍 ready | 当前账号已 ready=true、networkMode=2；尚未证明新设置流程无需模块同步 |
| 拥有设备缓存同步 | 停用模块主动调用 DeviceSyncService 后，GMS 自行维护缓存且实机操作有效 | 尚未验证，不能直接假定 flag 能替代缓存同步 |
| 地图坐标修正 | Find Hub 应用自身功能，与本次 GMS 配置实验分开 | 不在本次“摆脱 GMS 逐版本 Hook”的替代范围 |

## 持久性证据要求

1. 记录官方 API 成功状态、目标原生记录及已提交关联；仅广播 result=0 不足。
2. 恢复 debug 引导，读取其原始 false，检查无遗留 debug override。
3. 停用目标 Hook，读取原始 getter；仅在 Hook 返回 true 的日志里看结果不足。
4. 用户正常使用期间不补写、不定时恢复。超过完整 24 小时后再确认记录和
   新进程原始值；手机离线时不能生成新的设备观察证据。
5. 配置刷新后确认 active/source/关联和实际值；数据库记录保留但读取不到
   仍算失败。不能以旧快照 true 掩盖数据库已失效。
6. 确认 GMS 升级后的效果。诊断探针的旧混淆名失配时，要区分“仪器失配”
   和“原生配置失效”；最终设置工具不能依赖这些诊断名。

## 完整退出版本映射的条件

- 启用和撤销工具按 namespace/flag 名、公开 Receiver 协议、经验证格式工作，
  不随混淆名改动重新编译。协议或数据格式变化仍必须检测并拒绝未知操作。
- 从原始状态到可用状态的流程可重现；对需要原生账号设置的步骤，优先
  验证官方 UI，而不是继续内置逐版本账号客户端/回调映射。
- 对必要 GMS Hook 逐项停用并实际验证。不能一次全删后凭一次响铃宣布完成。
- 回滚只撤销本工具拥有的配置，保留既有账号、实验、服务器配置和其他工具
  的修改；写入中断、设备断连、快照并发更新均要有明确恢复结果。
- 不发布真实快照、数据库、配置令牌及账号数据。实验路径不能直接作为面向
  其他设备的安装脚本。

## 当前等待安排

24 小时观察起点为 2026-11-06 22:20，截止不早于 2026-11-07 22:20
（Asia/Shanghai）。用户会在次日重新连接并通知确认。在此之前仅进行
离线工作或只读检查，不自动重启 GMS，不要求用户守在手机旁边。
