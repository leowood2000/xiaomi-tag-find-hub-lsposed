# Find Hub 原生 override：2026-11-06 阶段实验结果

**状态：单个配对开关的原生写入、撤销引导及 GMS 进程重启验证成功；超过 24 小时的持久性观察进行中。尚未证明可删除全部 GMS Hook。**

本文接续 [2026-10-10 研究备忘](native-override-research-20261010.md)，
目标是使配置按稳定的 namespace/flag 名工作，减少对逐版本混淆入口的依赖。
本文记录实验结果，不是可直接用于其他设备的改库教程。

## 环境及授权范围

- 设备：国行 Redmi K80 Pro（miro），已 Root，LSPosed。
- GMS：`26.43.30 (260400-1006944126)`，versionCode `264330035`。
- 当前 Phenotype 数据库 schema：`1036`。
- Tag：国际版 Xiaomi Tag，model ID `15D23E`；不适用于国行版 Xiaomi Tag。
- 用户先批准临时 debug 开关及快照实验、验证后恢复；随后单独批准仅写入
  `com.google.android.gms.nearby / enable_fast_pair_spot_integration=true`
  并观察超过 24 小时，不做周期性补写。
- 本阶段未修改位置上报开关，未清除 GMS 数据，未解绑 Tag，未重启整台手机。

## 已取得的运行证据

### 1. 确认真实读取源

在当前手机上，`enableDebugService` 读取设备加密区的共享快照：

```
/data/user_de/0/com.google.android.gms/app_phenotype_shared/<salt>/<key>.pb
```

`files/phenotype/shared/<namespace>.pb` 在这条读取链上是后备文件。
因此，CE 数据库中存在 override，或后备文件中存在 true，都不能直接
证明运行时 getter 已生效。只读探针实际观测到共享快照来源。

### 2. 临时引导可以通过原生 debug 门禁

备份实际共享快照，在其独立元数据 override 区临时增加
`enableDebugService=true`，保留压缩的服务器参数区原字节内容。
等待 GMS 完成初始化后，观察到：

```
raw enableDebugService=true
snapshot debug=true status=5,2
debug authorization result=passed
native API exception=null
native API success=true
```

对照实验中，未引导时官方操作明确返回：

```
SecurityException: com.google.android.gms is not authorized for debug operations
native API success=false
```

原生删除实验将 debug override 的 `active` 置为 NULL，证明操作确实
进入了原生写入/撤销链，而不只是修改客户端日志显示。随后清理本实验
新增记录，恢复备份快照并核对 SHA-256；debug override 数量恢复为 0。

### 3. 通过官方 API 写入实际配对开关

再次临时引导后，通过 GMS 的 `FLAG_OVERRIDE` 接口，仅对上述配对集成
开关执行 `commit=true` 的原生 SET，得到成功返回和以下数据库结果：

```
active=1 value=1 type=2 source=0 links=2 committedLinks=1
```

这些记录及关联由官方 API 创建；这一步没有直接改写服务器参数分区。
之后恢复 debug 引导快照，确认 debug override 为 0。

### 4. 撤去对应 Hook 后原始值仍为 true

研究构建停用了 `keil.P()` 的配对集成 Hook。重启 GMS 进程后，
直接读取底层 `kein.az()` 及未 Hook 的 `keil.P()`，得到：

```
research: SPOT integration gate is NOT hooked; using native config
raw enableDebugService=false
raw SPOT getter=true unhooked facade=true
snapshot debug=null status=5,2
provider debugPresent=false
```

这证明当前单项开关可在撤销 debug 引导后，由原生配置提供。
其他现有 Hook 仍在运行，不能把这条证据扩大为“整个模块已无需 Hook”。

## 对 10 月研究的修正

1. 当前 Manifest 注册的广播是 `com.google.android.gms.phenotype.FLAG_OVERRIDE`，
   不是备忘中的 `FLAG_OVERRIDE_INTENT`。实验显式指定官方 Receiver。
2. 当前 schema 要求 `config_package_id` 与 `config_package_name` 二者
   恰有一个非 NULL。旧示例同时填写二者会违反 CHECK 约束。
3. 原生写入器将 boolean 存为整数 `0/1`、`type=2`，不是自定义布尔 BLOB。
4. API 是异步操作；必须等待 GMS 初始化并检查真实任务异常/成功状态和
   数据库，不能只看 `am broadcast result=0` 或被脱敏的 `%b` 日志。
5. 删除的账号参数需要与记录归属相符；`*` 是账号名，不应直接理解为
   SQL 的通配符。当前删除实现允许空账号过滤，实验只限定目标 flag。
6. 直接插入裸行仍不足以证明生效；读到的数据库、提交关联、实际快照和
   运行时 getter 必须相互核对。本次成功的引导点是实际共享快照。

## 为什么仍需观察 24 小时

[GMS Phixit](https://github.com/polodarb/GMS-Phixit) 的源码会重写压缩的参数
分区，其 README 明确警告修改会在约 24 小时内被重置。本次使用官方
手工 override 记录及提交关联，写入链不同，因此不能直接套用它的成功
或失败结论。

当前版本更新实现中，已定位到清理 `source=1` 记录及复制 active 关联
的逻辑；本实验官方手工 override 为 `source=0`。这只是代码层面的
支持证据，不能替代时间、配置刷新、重启和升级后的实机验证。

## 当前观察与后续验收

- 保守计时起点：**2026-11-06 22:20（Asia/Shanghai）**。
- 满 24 小时：**2026-11-07 22:20（Asia/Shanghai）**。
- 期间只读检查，不补写，不靠定时恢复制造持续有效的表象。
- 用户将正常使用手机，次日重新连接后确认。离线不影响计时，但未连接
  时不能声称取得新的设备证据；未经用户回来确认不自动重启 GMS。
- 到期后核对原生记录、debug 已撤销、对应 Hook 未启用，并读取新进程
  的原始 getter。还需确认配置刷新及 GMS 升级后的行为。
- 只有单个开关通过，不等于其他配件门控、账号设置同步、Tag 资格处理
  都可以移除；这些部分需要分别验证。Find Hub 地图修正属于独立功能。

## 研究代码与资料边界

临时诊断代码在 [research/native-config-probe 分支](https://github.com/leowood2000/xiaomi-tag-find-hub-lsposed/tree/research/native-config-probe)，
本阶段停用配对门控 Hook 的提交为 `add5ed0`。此分支是当前版本的研究
仪器，不是已完成的跨版本安装器，也未作为正式 APK 发布。

本报告省略账号、设备网络地址、快照密钥目录和配置令牌。数据库副本、
原始快照及包含设备路径的临时脚本未上传。后续可复用工具应按格式识别
和稳定名称工作，并具备匹配唯一性检查、原子写入和只撤销自身修改的能力。
