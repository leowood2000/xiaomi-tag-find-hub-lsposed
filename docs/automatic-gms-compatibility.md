# GMS 入口自动识别方案

2026-11-06 调查。此文是实现方案；0.9.13 仍使用精确构建映射。

## GitHub 工具

[LuckyPray/DexKit](https://github.com/LuckyPray/DexKit) 提供运行时 DEX 解析，
支持字符串、参数类型、返回类型、调用关系等条件组合查找混淆方法。
它能解决混淆改名引起的维护，不保证跨越业务逻辑或协议变化。

## 当前 APK 中可用于识别的特征

26.43.30 已通过反编译确认：

| 功能 | 稳定特征 | 当前入口 |
| --- | --- | --- |
| Fast Pair 配件 | `EnableFindMyDeviceModule__enable_fast_pair_accessories` + 无参数 boolean | `kbic.n()`，门面 `kbia.g()` |
| 自定位 | `EnableFindMyDeviceModule__enable_self_location_reporting` + 无参数 boolean | `kbic.v()`，门面 `kbia.k()` |
| 网页动作 | `EnableFindMyDeviceModule__enable_spot_client_actions_handler` + 无参数 boolean | `kbic.w()`，门面 `kbia.l()` |
| SPOT 集成 | `enable_fast_pair_spot_integration` + 无参数 boolean | `kein.az()`，门面 `keil.P()` |
| Tag 资格 | 三条 InitialPairingHandlerBase 日志 + 返回 int + 单个请求参数 | `efoh.f(efjb)` |
| 账号设置客户端 | 构造器字符串 `Spot.API` + 稳定请求类型的方法 | `ckow` |
| 设置回调 | `Status` + 稳定响应类型 + void 返回 + 客户端调用关系 | `ckot.c` / `ckou.a` |

本次资格方法由 `e` 改为 `f`，但日志和参数结构仍可识别，说明只存类名
和方法名的版本表可以改成特征匹配。

## 建议实现

1. 保留已确认的精确映射作为快速路径；新构建用 DexKit 本地扫描。
2. 四个 flag 直接识别含字符串的 getter，避免依赖会重命名的门面方法。
   getter 是实例方法，现有“仅允许 static boolean”的校验要单独调整。
3. Tag 资格必须同时满足三条日志、返回 int、请求参数结构及 Xiaomi model
   ID 检查；多匹配或不匹配时停用该项，不按第一个结果猜测。
4. 账号设置客户端用构造器和请求签名识别；回调需要校验客户端调用关系。
   请求/响应字段语义和 DeviceSyncService 依赖结构也要检查，不能只认类名。
5. 缓存键包含完整版本、版本代码和 APK 指纹，升级后重新识别。扫描开销
   必须实测，不能把扫描大型 GMS APK 放进每个进程的主线程启动路径。
6. 兼容页面区分“固定映射”“自动识别成功”“自动识别失败”，未知构建
   通知只在不能可靠识别时提示，并继续保留已配对 Tag 的响铃说明。

## 落地前验证

用留存的多个 GMS APK 检查匹配唯一性，再在当前手机上确认动态找到的
目标与反编译结果一致。需要覆盖首次扫描、缓存命中、升级失效、匹配失败，
以及配对和账号同步实际调用。仅构建成功不能证明动态查找可靠。

## 与原生配置路线的关系

仓库的 `native-override-research-20261010.md` 记录了官方 override 写入
入口的限制。DexKit 路线沿用当前模块的运行时修改方式，不依赖修改
phenotype 数据库，也不需要先解锁 debug 服务。
