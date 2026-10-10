# Find Hub 原生启用路线：可行性研究备忘（2026-10-10）

> [!NOTE]
> 本文档为**纯研究备忘**，记录一次针对"让 GMS 原生启用 Find Hub（摆脱逐版本 Hook）"的可行性调查。
> 它**不改变本模块的主线实现**——截至撰写时，模块功能仍完全依赖现有 LSPosed Hook（`FastPairHook.java` / `GmsCompatibility.java`）。
> 全部结论基于实机取证：GMS **26.37.37**（versionCode `263737035`）在国行 Redmi K80 Pro 上的反编译与 Phenotype 数据库分析。
> 混淆类名随 GMS 版本变化；**flag 名、广播协议、数据库结构等稳定标识**才具有跨版本参考价值。

## TL;DR

1. 国行 GMS 不启用 Find Hub **不是 bug，而是 Phenotype 配置层的服务器投放决策**：四个门控 flag 均未对国行设备下发，代码默认值全部为 `false`。
2. 原生开启在**读取侧成立**：Phenotype 读取优先级是"Provider 直查 override → 已提交快照 → 代码默认"，未下发的 flag 同样能被 override 命中。
3. 但**写入侧被锁死**：零售机上官方 `FLAG_OVERRIDE` 广播入口被 debug-op 门禁（`frvk`）拦截——`enableDebugService=false`（服务器不下发）+ 调用包不在白名单 + 非 dogfood/模拟器/dev-keys 设备，直接 `SecurityException`。实机 3 次广播全部被拒、零写入。
4. 最有希望的"原生路线"是 **B：SQL 引导 `enableDebugService=true`**，之后所有 SET/DELETE 走 GMS 原生协议、可原生回滚；**A：Hook 门禁读取（`jyzn.l()`）** 可作过渡验证；**C：四 flag 全 SQL 直写** 不推荐（裸行缺挂链、无法原生删除）。
5. 结论：模块当前逐版本 Hook 的主线**仍然必要**；本研究只是为未来"去 Hook 化"标注一条已探明的路径。

## 1. 研究环境与纪律

- 手机：Redmi K80 Pro（`miro` / `24122RKC7C`），国行 HyperOS `OS2.0.15.0.VOMCNXM`，Android 15，KernelSU root。
- GMS：`26.37.37`（versionCode `263737035`），研究时新于 README 已验证列表（26.26.34 / 26.36.35）。
- Tag：国际版 Xiaomi Tag，Fast Pair model ID `15D23E`。
- 方法：无线 ADB + `su -c` 只读取证；`adb pull` base.apk（192 MB）后 jadx 单类反编译；CE 与 user_de 两套 `phenotype.db` 全量分析（`flags_content` 全部 raw-deflate 解压 + `config_packages` blob + `external_experiments`）。
- 纪律：只读优先；写入实验（E1）先经用户批准、确认回滚路径后才执行；`enable_self_location_reporting` 涉及账号位置隐私，未经用户明确批准不得实验；一次只验证一个 flag。

## 2. 原生开关全景：四个门控 flag

### 2.1 flag 定义（精确反编译结果）

| 代码入口（26.37.37 混淆名） | flag 全名 | namespace | 代码默认 | 作用 |
|---|---|---|---|---|
| `jtzg.f()` → `jtzi.m()` | `EnableFindMyDeviceModule__enable_fast_pair_accessories` | `com.google.android.gms.findmydevice` | `false` | FMD 侧 Fast Pair 配件集成总开关；同时门控 SPOT binder 探测（`dzdt.a()`） |
| `jtzg.i()` → `jtzi.t()` | `EnableFindMyDeviceModule__enable_self_location_reporting` | `com.google.android.gms.findmydevice` | `false` | 自身位置上报（**隐私 flag**） |
| `jtzg.j()` → `jtzi.u()` | `EnableFindMyDeviceModule__enable_spot_client_actions_handler` | `com.google.android.gms.findmydevice` | `false` | SPOT 客户端动作处理器 |
| `jwys.N()` → `jwyu.aw()` | `enable_fast_pair_spot_integration` | `com.google.android.gms.nearby` | `false` | Fast Pair ↔ SPOT 集成；`ealy.e()` 资格链第一道门槛（**主根因**） |

**反混淆陷阱**：`ciza.a()` 中的实例方法 `lt().m()`（= accessories）与静态方法 `jtzg.m()` 同名不同物——后者经 `lt().E()` 读的是**数字名 flag `45801636`**（默认 `true`），与 accessories 无关。按方法名 grep 时极易踩坑。

### 2.2 `ciza.a()` 是复合门控，不是第五开关

```
ciza.a() = (self_location_reporting && enable_offline_beacon) || accessories
```

只要 `enable_fast_pair_accessories=true`，`ciza.a()` 自然为 true。研究四个 flag 即完整覆盖，不存在遗漏的第五开关。

### 2.3 `ealy.e(eaiz)` 资格链：status 11 的三个产生分支

父类 `eakp.e()` 对 Xiaomi Tag 返回 **15**（合格），**不背锅**。真正把状态打成 11（禁用）的分支依次为：

1. `!jwys.N()`（`enable_fast_pair_spot_integration` 为 false）→ 11，日志文案 `FastPair SPOT integration disabled`——**主根因**；
2. 设备能力位无 `EDDYSTONE_TRACKING` → 11（Tag 侧数据/能力）；
3. `dzdt.a()`（SPOT binder 探测，其可用性由 accessories flag 门控）为 false → 11。

另两个分支的具体日志文案未逐一记录，但 status 值一致。

### 2.4 白名单 flag：`fast_pair_locator_tags_model_ids_to_bond`

服务器下发 28 个 model ID：

```
A76E38 3390EA B4122F 8C9712 97B899 AA5812 4944A1 499780 F31E34 5E4A46
D68353 A05281 1407F4 74BEB9 84978F E9D4E4 D815F2 B36B82 4E7C13 1FC93B
E0F4FF FB990E 07BB5E ED7E00 8FE397 1EBAF0 565EE2 B8CEB7
```

**无 `15D23E`**。但该白名单**不在 `ealy.e()` 资格链上**，只影响 bonding（配对绑定流程）；读取者尚未定位（疑 `jwyo` 族，见 §8 遗留）。换言之，Xiaomi Tag 当前"不显示/不可用"的直接原因不在这份白名单。

## 3. 服务器投放证据（数据库取证）

CE 库 `/data/data/com.google.android.gms/databases/phenotype.db`：

- `flags_content` 共 **1089** 行，全部 raw-deflate 压缩，逐行解压后全文搜索；
- `config_packages` 表 **193** 个 blob（实验配置）逐一展开；
- `external_experiments` 亦搜索。

结果：**四个门控 flag 名与 `15D23E` 全部 0 命中**——服务器没有下发任何相关配置，行为由代码默认 `false` 决定。

反证细粒度投放的对照样本：

- finder UI 相关 flag（如 `PersonalsafetyFeature__enable_find_device_ui`）已下发 `true`——说明 Google 是**按 flag 逐个投放**，不是"国行一刀切全关"；Find Hub 的 UI 存在而功能缺席正源于此。
- `com.google.android.gms.findmydevice` 命名空间仅下发 4 个 flag：电池电量上报相关（`SpotFlags__battery_level…`）、`SpotFlags__location_report_self_location_fetching_strategy`、两个 telemetry 配置。

user_de 库 `/data/user_de/0/com.google.android.gms/databases/phenotype.db`：只含 6 个命名空间（`clearcut.public` / `permissions` / `playlog.uploader` / `gcm` / `usagereporting` / `phenotype`），另一套 `flag_overrides` schema（WITHOUT ROWID），同样 0 行。

## 4. Phenotype 读取链（override 为何能生效）

来自 `hcgt.j()` 指令级转储：

```
优先级：ConfigurationProvider 直查（hcde / hccv）
      > 已提交快照（hckb / hcmb）
      > 代码默认值
```

- 缓存：`hchx` 按 `hckm` 的提交版本号失效，override 提交后能被读到。
- 关键性质：`hcia` 布尔 flag 的 `o()==false` **不要求该 flag 已在快照中注册**——即**未下发的 flag 也能被 override 生效**。
- 推论（读取侧成立，未实机验证）：只要能把 override 行写进 `flag_overrides` 并被 Provider 直查命中，四个门控 flag 就会翻转，Find Hub 原生启用。

## 5. E1 实验：官方广播入口与失败根因（已定案）

### 5.1 三层门禁

1. **manifest 签名权限** `PHENOTYPE_OVERRIDE_FLAGS`（`protectionLevel=signature`）：普通 shell 不持有，但 **root（uid 0）经 AOSP `checkComponentPermission` 旁路**——实测广播可达 receiver，此关通过。
2. **chimera 代理**：manifest 里的 `FlagOverrideReceiver` 只是 `bizn` 代理壳，真身为 `com.google.android.gms.phenotype.service.FlagOverrideChimeraReceiver`。
3. **debug-op 门禁（真正的死锁）**，`frvk.p()` → `v()` / `s()`：
   - 调用包 `com.google.android.gms` ∉ `allowlisted_apps_for_flag_overrides`（该 allowlist flag 位于 `com.google.android.gms.phenotype` 命名空间，base64 编码的默认值仅含 `play.games`）；
   - `enableDebugService`（`jyzn`，**默认 false 且服务器不下发**）——关键开关；
   - 包名名单 `fruw.a = {mobdog, mobileutilities}`，不匹配；
   - 门禁内还有针对 LTS 构建的特例（需系统属性），本机不满足；
   - 设备指纹：`miro` + `release-keys`，非 goldfish/ranchu 模拟器、非 dev-keys/test-keys。

   最终 `throw SecurityException("com.google.android.gms is not authorized for debug operations")` → task 失败。

**日志陷阱**：receiver 打印 `Successfully set flag overrides? %b`，形似成功语，实际 `%b=false`（GMS 日志脱敏）。判断成败必须以数据库行数为准，不能信日志。

### 5.2 实验记录（全部失败、零残留）

- 3 次广播：21:57:31（commit=false）、22:00:59（commit=false）、22:02:59（commit=true）。被拒发生在任何 flag 级处理之前（包级 debug-op 检查先行），与目标 flag 选择无关。
- 证据三重定案：CE 与 user_de 双库 `flag_overrides` 每次 0 行；模块日志观测 `original=false` 不变；SecurityException + `%b=false`。
- 收尾：双库复核零残留、临时脚本已删、GMS 正常运行（研究全程 GMS 仅被 kill 过一次并自动重启——对后续 B 路线的"停 GMS 窗口"有参考意义）。

### 5.3 广播协议还原（可用作 B 路线成功后的操作接口）

SET（写入 override）：

```
am broadcast -a com.google.android.gms.phenotype.FLAG_OVERRIDE_INTENT \
  --es package <namespace> \
  --es user '*' \
  --esa flags <flag 名> \
  --esa types boolean \
  --esa values true \
  [--ez commit true] \
  com.google.android.gms
```

DELETE（原生回滚，单条软删）：

```
am broadcast -a com.google.android.gms.phenotype.FLAG_OVERRIDE_INTENT \
  --es action delete \
  --es package <namespace> \
  --es user '*' \
  --es flag <flag 名> \
  com.google.android.gms
```

协议细节（反编译确认）：

- DELETE 的动作 extra 键为 `action`（`ForegroundPriority.KEY_ACTION`）。
- `commit=true` → `Flag` 第 4 参 `-1000`（要求 `user='*'`、非 direct boot 场景）。
- 原生 delete 是**软删**：`active=NULL` + experiment state 重建，行仍在表里。
- flag 名带 `*` 的前缀删除已废弃（"Prefix deletes are no longer supported"）。

## 6. `phenotype.db` 结构备忘（user_version=1036）

CE 库 `flag_overrides` 列：

```
(override_id PK, config_package_id, config_package_name, account_id,
 active DEFAULT 1, name, value BLOB, type, source DEFAULT 0)
```

- `accounts`：`(0, '')`（匿名/广播上下文）与 `(1, 主 Google 账号，邮箱脱敏)`。
- `config_packages` 关键 ID：`599='com.google.android.gms.phenotype'`（Phenotype 自身命名空间，`enableDebugService`、`allowlisted_apps_for_flag_overrides` 挂在此）；`580='com.google.android.gms.findmydevice'`（静态注册 112 个 flag）；`499='com.google.android.gms.nearby'`。
- 本机观测 GMS uid=`10131`（权限恢复参照：`chown 10131:10131`、`chmod 660`）。

## 7. 后续路线（均未执行，待批准）

| 路线 | 做法 | 优点 | 缺点/风险 | 状态 |
|---|---|---|---|---|
| **B：SQL 引导 debug 开关**（最有希望） | 按原生序列化格式向 `flag_overrides` 直插一行 `enableDebugService=true`，解锁广播门禁 | 之后全部 SET/DELETE 走原生协议、**可原生回滚**；只碰一行数据 | 动库需停 GMS 窗口原子替换（备份、清 `-wal`/`-shm`、属主权限）；`account_id` 取值待定；裸行能否被 Provider 直查命中待验证 | 未执行 |
| **A：Hook 门禁读取**（过渡验证） | 模块 hook `jyzn.l()` → `true` 骗过 debug-op 门禁 | 不动数据库；单点 hook | 仍是逐版本混淆名 hook，与主线同维护负担，仅作过渡 | 未执行 |
| **C：四 flag 全 SQL 直写** | 直接插入四个 flag 行 | 无需过门禁 | 裸行缺 `frwk` 挂链；原生 DELETE 的 SQL `INNER JOIN experiment_states_to_overrides` 认不出裸行 → **无法原生回滚** | 不推荐 |
| **L3：等待服务器下发** | 被动等待 | 零成本 | 投放维度（机型/地区/账号）未知，不可本地控制 | 被动 |

B 路线引导行（`value`/`source` 编码需先按 `frwk` 序列化代码确认，见 §8）：

```sql
INSERT INTO flag_overrides
  (config_package_id, config_package_name, account_id, active, name, value, type, source)
VALUES
  (599, 'com.google.android.gms.phenotype', /* 0 或 '*'，待定 */, 1,
   'enableDebugService', /* 1 的 BLOB 编码，待对照确认 */, 2, 0);
```

配套流程：停 GMS → 窗口内原子替换 DB（先备份）→ 清遗留 `-wal`/`-shm` → `chown 10131:10131` + `chmod 660` → 广播 SET 验证写入 → 广播 DELETE 验证回滚。

## 8. 遗留问题

1. `fast_pair_locator_tags_model_ids_to_bond` 的读取者未定位（疑 `jwyo` 族）——需确认它只影响 bonding 而不影响资格判定，以判断原生启用后是否需要补 `15D23E`。
2. `ConfigurationChimeraProvider` 对裸 override 行的解析与账户归属——决定 B 路线 `account_id` 用 `0` 还是 `'*'`，也是"裸行可见性"的最终验证点。
3. `enableDebugService` 行的 `value`/`type`/`source` 精确编码——库里无原生样行可对照，需按 `frwk` 序列化代码反推。

## 9. 方法论：环境重建手册（约 10 分钟）

- APK 路径（随机段随安装变化，先 `pm path com.google.android.gms`）：本例 `/data/app/~~xvstttRKfYp23NUOa9sHNQ==/com.google.android.gms-5QoayOU8GyLz7pz3lumxiQ==/base.apk`（192 MB）。
- jadx 1.5.2 + Java 11：`jadx.bat` 的 `DEFAULT_JVM_OPTS` 含 `--enable-native-access=ALL-UNNAMED`，Java 11 下报错，删掉该参数即可。
- 单类反编译：`jadx.bat --no-res [--show-bad-code] --single-class <FQCN> --single-class-output <out.java> base.apk`；`FlagOverrideChimeraReceiver` 必须 `--show-bad-code` 才出完整体。
- DB 取证：CE 与 user_de 双库都拉；`flags_content.value` 是 raw-deflate（无 zlib 头）；`config_packages` 含 193 个实验 blob；`external_experiments` 也要搜。
- 关键类索引（**26.37.37 混淆名，升级即变**）：
  - FMD flag 门面/访问器：`jtzg` / `jtzi` / `jtzh`
  - Nearby FP flag：`jwys` / `jwyu` / `jwyt`
  - FMD 复合门控：`ciza`
  - FP SPOT 资格链：`ealy` / `eakp` / `eaiz` / `dzdt` / `jeqk`
  - 命名空间描述符：`jtzc` / `jwxe` / `jyxn`
  - Phenotype 读取链：`hckj`（注册表）/ `hcip`（工厂）/ `hcia`（布尔 flag）/ `hcib` / `hcgt`（读取转储）/ `hchx`·`hckm`（缓存失效）
  - override 写入路径：`frsr` / `frvk`（debug-op 门禁）/ `fryj` / `fryl` / `frwg` / `fruo` / `fruw`（`{mobdog, mobileutilities}`）/ `frwk`（行封装挂链）
  - debug-op 配置：`jyzk`（app 白名单）/ `jyzn`（`enableDebugService`）/ `jfbp`
  - chimera 代理壳：`bizn`

## 10. 恢复研究 checklist

- [ ] 反编译 `ConfigurationChimeraProvider`，定 B 路线 `account_id`（`0` vs `'*'`）与裸行可见性。
- [ ] 按 `frwk` 序列化代码确认 `enableDebugService` 行的 `value`/`type`/`source` 编码。
- [ ] 制定停 GMS 窗口原子替换流程（备份、`-wal`/`-shm`、`chown`/`chmod`）。
- [ ] 引导成功后先 SET `enable_fast_pair_spot_integration`（资格链主根因、风险最小），验证 DB 行 + Find Hub 状态变化。
- [ ] `enable_self_location_reporting` 涉及隐私：必须用户单独明确批准；**一次只验证一个 flag**。
- [ ] 每次写入后用原生 DELETE 广播验证回滚（`active=NULL` 软删）。
- [ ] GMS 升级后复核：混淆名全变，flag 名与广播协议通常稳定，DB schema 版本（1036）可能升级。
- [ ] 若 B 受挫 → 退回 A（模块 hook `jyzn.l()`）作过渡验证。

---

*本研究由用户主导、TeleAgent 执行取证，初始方向（Phenotype Override 假设与 `jtzg.f()` 调用链）由 ChatGPT 规划会话提出；本文档为结论的唯一固化载体。*
