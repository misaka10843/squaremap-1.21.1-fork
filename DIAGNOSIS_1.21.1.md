# 1.21.1 故障诊断与上游回移（2026-10-30）

本文记录首轮 `1.3.2-fork.1` 的诊断与当时的回移范围。后续已继续移植多项原列为待办的功能；当前状态见 `BACKPORT_PLAN.md`。

## spark 实测

输入：`x4G7qDLpJT.sparkprofile`，香港时间 2026-10-30 08:41:19–08:47:37，378.289 秒，4 ms 采样间隔。仅采集 `Server thread`；百分比为该线程的采样时间，包含等待，不能当作 CPU 占用率，也不能逐层相加。

- squaremap Vanilla 快照入口的两处分支合计约 63.148 秒 / 16.71%。主要分支内 `getChunkFuture` 为 37.212 秒，其中 managed-block 等待 36.404 秒；直接 `CompletableFuture.join` 为 25.040 秒。
- 源码对应：主线程调用 `readChunk(...).join()`，然后在主线程调用本身会等待完成的 `getChunkFuture`。这是实际抓到的问题，与图片保存错误分别处理。
- 最近一分钟 TPS 20.00，平均 MSPT 9.617，P95 15.061，中位数 5.800。08:43:19–08:44:19 窗口 TPS 18.168；整个样本最大 tick 1101.681 ms。属于间歇性长停顿，不能用最终一分钟的正常 TPS 排除问题，也不能将每次长 tick 都归于同一调用。
- VillageForge / DragonHunter 主要服务器 tick 入口分别约 0.24% / 0.41%，未显示为主要直接耗时来源。日志中的扫描重试问题仍需该模组源码或现场对照验证。
- 安装了 C2ME `0.4.0-alpha.0.123+1.21.1`，调用经过它的 chunk system；没有足够证据认定它与 squaremap 不兼容。
- 堆使用约 5.58 GiB / 14 GiB，GC 数据没有显示持续 Full GC。当前证据优先指向主线程等待，不支持仅靠加内存解决。
- 仅有主线程数据，无法评估 WebP 编码线程、渲染工作线程、区块 IO 线程的总负载或磁盘延迟。

## 日志解释

`dragoncare/VillageForge` 的 `never reached FULL` 表示扫描器等待的区块未达到 FULL，不等于区块存档损坏。squaremap 为读取已有地形会加载 EMPTY 状态区块，不需要把所有读取的区块都推进到 FULL；这可能触发其他模组的加载监听，但两段日志本身不能证明因果关系。不要为了消除扫描警告强制地图生成新区块。

`Could not save map region [-11, 7]` 的异常发生在读取旧 WebP，具体失败者是服务器提供的 TwelveMonkeys 3.12.0 `VP8LDecoder`。仅凭堆栈不能区分文件损坏与解码器兼容问题。原实现全局自动挑选 reader，异常直接结束该区域保存，旧文件保留，后续重复失败；这部分已修复。

修复优先使用随 squaremap 提供的 Sejda/libwebp reader，其他 reader 作为备用；每次尝试使用独立流并释放 reader，校验 512×512 尺寸。全部解码失败时将原件移到同目录 `.unreadable-<UUID>`，尝试另一种格式副本。没有可恢复副本时，从空白图应用当前更新，未包含在当前更新中的像素需要重新渲染恢复。备份不会被自动认定为可删除瓦片。

## 本次回移与适配

上游核对基准：[master 2e158c2f](https://github.com/jpenilla/squaremap/commit/2e158c2f11b22923c29bdd91ed0dbab74d055d15)，核对日 2026-10-30。当前 upstream 为 1.4.4-SNAPSHOT，最近正式版 1.4.3，面向 Minecraft 26.3。下面是适配结果，不表示把本分支整体升级成 1.4.x。

| 上游变更 | 1.21.1 处理 |
|---|---|
| [#629 线性光、alpha 加权的逐级降采样](https://github.com/jpenilla/squaremap/commit/2e158c2f) | 已回移滤波算法，保留本分支单线程合并写入和脏父瓦片去重；缩小时细轨道、边缘更稳定。旧粗图需再次渲染才更新 |
| [Vanilla 异步区块加载](https://github.com/jpenilla/squaremap/commit/e7812aff) | 已适配“读盘 future 不 join 主线程、从主线程外调用 getChunkFuture、快照回主线程、独立无超时票据保护”的关键修复；没有直接回移新版 NBT 解码体系 |
| [#604 水边阴影](https://github.com/jpenilla/squaremap/commit/2b0fa13b) | 已回移，流体像素也更新上一表面高度 |
| [无坐标玩家网页修复](https://github.com/jpenilla/squaremap/commit/aac1bd1c) | 已适配，保留本分支同源头像缓存和重试功能 |
| [移动端动态视口](https://github.com/jpenilla/squaremap/commit/1de48809) | 已回移，并保留 vh 后备值 |
| [NeoForge 停机生命周期](https://github.com/jpenilla/squaremap/commit/a9cc0188) | 已适配 1.21.1 的 FMLEnvironment API |
| [NeoForge 多方块放置修复](https://github.com/jpenilla/squaremap/commit/641d933d) | 本分支已有按位置合并的实现，无需重复移植 |
| [#622 精确瓦片刷新](https://github.com/jpenilla/squaremap/commit/2a228eeb) | 本分支已有 SSE `tiles-invalidated`。upstream 的 changes.json 轮询可支持外部静态 Web 服务器，属于后续独立功能；本次保留 SSE |
| [#624 全局/世界后台 JSON 写入器](https://github.com/jpenilla/squaremap/commit/94c8f473) | 后续适配，需要同时调整 reload/reset/shutdown；本次修复图片队列与停机竞争 |
| [#615 直接解码 palette](https://github.com/jpenilla/squaremap/commit/b4a7f231)、渲染管线重写、Moonrise 新接口 | 后续独立迁移，涉及 1.21.1 NBT 格式、注册表、缓存、retrogen、取消和停止生命周期，不能直接复制新版类 |
| 新版花草、干燥树叶着色 | 仅移植 1.21.1 存在的方块才有意义；新版独有方块不引入 |
| Paper/Folia/Fabric/Sponge、demo 部署、MC 26.3 构建依赖 | 不属于本 NeoForge 1.21.1 分支的回移范围 |

本地额外修复：WebP codec 从 Shadow minimize 中排除并合并 SPI 服务；显式创建随包 codec。图片队列关闭后拒绝新提交，等待队列容量的提交会检查关闭状态，避免 executor 关闭竞争；Moonrise callback 异常会完成 future，避免渲染永远等待。

## 现场验证

本地验证：Java 21 下 `:squaremap-common:test :squaremap-neoforge:build` 成功。4 项 Java 测试覆盖 WebP/PNG 副本恢复及备份、尺寸错误、负坐标父瓦片保留、线性光与 alpha 滤波；测试运行时包含 TwelveMonkeys 3.12.0。`node tools/test-player-markers.cjs` 通过，覆盖隐藏/恢复玩家坐标、跟随、离线和缺失玩家。`git diff --check` 通过。

产物：`build/libs/squaremap-neoforge-mc1.21.1-1.3.2-fork.1.jar`。已检查随包 WebP reader/writer、SPI 和 Linux x64 原生库。

SHA-256：`8D8287ECC050E1447C09DBBC584CA8D559D763F3CCB70F0143F7B22B97641186`。

替换 JAR 前备份 squaremap 数据目录。重启后针对失败区域重新渲染；无需删除 Minecraft 的 region 文件。检查是否仍有 TwelveMonkeys 的 TileStorage 解码错误、主线程 `VanillaChunkSnapshotProvider` 的 join 等待、未释放票据导致的常驻区块。

相同渲染任务下重新采样，并补采全部线程；对照暂停地图渲染时的 MSPT / TPS 与 VillageForge 警告。如果警告仍存在，需要 dragoncare 的扫描器代码或完整日志判断它是否错误地把 EMPTY 加载当成 FULL 扫描任务。

自动测试和构建通过仍不能替代含 C2ME、dragoncare、Create 的真实服务器验证。此文件只记录已观察到的证据与适配范围。
