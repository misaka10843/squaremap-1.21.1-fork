# squaremap NeoForge 1.21.1

[![Build](https://img.shields.io/github/actions/workflow/status/misaka10843/squaremap-1.21.1-fork/build.yml?branch=main)](https://github.com/misaka10843/squaremap-1.21.1-fork/actions)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

这是面向 Minecraft 1.21.1、NeoForge 和 Java 21 的 [squaremap](https://github.com/jpenilla/squaremap) 分支，在浏览器中展示服务器俯视地图、玩家、标记和 Create 铁路。当前版本为 `1.3.2-fork.3`，本仓库只构建 NeoForge 平台。

## 本版变更

### 从新版 squaremap 回移

上游核对基准为 `2e158c2f`，以下改动按 1.21.1 的区块格式与接口适配：

| 改动 | 具体行为 |
| --- | --- |
| 磁盘快照与 palette 解码（#601 / #615） | 直接读取已保存的区块 NBT，解码方块、群系 palette 并缓存条目；重建缺失高度图，减少区块加载和数据复制。需要升级或不适合直接解码的区块使用兼容路径。 |
| Moonrise 读取接口 | 适配 1.21.1 可用的直接区域文件读取接口；检测到 `NoCopyNBTData` 才使用该接口，保留普通回调与 Vanilla 路径。C2ME 环境仍使用兼容读取路径。 |
| 渲染管线 | 每世界串行调度任务，限制同时处理的区域、区块和快照请求；共享快照缓存，分别配置全图、半径及后台渲染并发。 |
| 区块级续绘 | 保存全图、半径渲染进度，恢复时跳过已完成区块；迁移旧进度与旧并发配置。 |
| 后台写入与生命周期（#624 等） | 世界与全局数据使用独立后台执行器；停机、重载和重置等待写入完成，前台渲染期间拒绝重置，重置后重新发布网页配置。 |
| 网页刷新（#622） | 增加 `changes.json` 变更记录、断线补偿、图片加载失败退避重试和请求超时；修复 JSON 缓存、gzip 与 ETag。 |
| 扫描及颜色 | 修复群系降雨量读取、水面阴影；缓存最低高度，单群系窗口跳过混色，减少像素写锁，在缓存锁外完成回调。 |
| 缩放图 | 使用线性光、alpha 加权降采样，减少透明边缘和缩放颜色误差。 |

### 本分支功能与兼容修复

- WebP 无损瓦片、PNG 后备和格式迁移。优先使用原生 WebP 解码器，损坏文件保留 `.unreadable-*` 副本，并尝试从另一格式恢复；两种格式均不可恢复时需要重渲染。
- SSE 精确通知变化瓦片，`changes.json` 作为外部 Web 服务和断线后的后备；替换图片加载完成前保留旧图。
- 可选 `?static=true` 静态浏览模式，首次加载后停止玩家、标记、地图和 Create 的自动轮询及 SSE，默认仍为实时模式。
- 注册表适配苍白橡树叶、落叶堆、野花、金蒲公英、灌木和干草等新版方块，识别实际注册的 `minecraft` / `vanillabackport` 方块并保留自定义颜色；默认隐藏灌木与干草，与短草一致。
- Create 6.x 覆盖层展示直线与曲线轨道、车站、信号、占用区段、列车和跨维度连接，使用现有网页服务端口；无人查看时暂停采样，静态网络刷新分摊到多个 tick。
- 同源玩家头像缓存、可搜索标记侧栏、世界与图层切换；修复缺失玩家坐标、退出后跟随异常、切换世界累积 Spawn 图层，以及 Create 折叠控件的居中和悬停显示。
- 补齐默认世界、玩家与 Spawn 图标，构建时核对最终 JAR 中的网页图片。
- 内置服务自动 gzip 压缩前端文本资源，构建时为入口、JS 模块与 CSS 生成统一版本号；升级后使用新 URL，未变版本可复用浏览器缓存。
- 玩家名单与当前世界坐标分别增量推送；Create 按世界和开启的列车、信号、占用图层订阅，只传变化字段。断线或版本缺口以完整快照恢复，旧 JSON 接口继续提供轮询后备。
- 大型 SSE 数据包按浏览器能力协商 gzip 压缩；状态去重，倒计时在网页本地更新，隐藏标签页时断开订阅。
- 瓦片使用版本 URL，离开视野后中止未完成请求；变更记录支持游标增量，Create 静态网络按世界裁剪且扫描结果未变时不重复发布。
- 像素未变且文件完整的瓦片跳过写入与刷新通知，减少重复下载；仍逐级检查缩放图，补建缺失或过时的父级瓦片。

网络与瓦片优化通过 20 项定向测试、60 张网页图片核对及打包后前端的浏览器回归，覆盖压缩、增量顺序、重复写入、世界切换、隐藏与恢复、断线后补全。本轮未重跑完整 Java 测试套件，真实服务器上的 C2ME/Moonrise、Vanilla Backport、Create 联合验收仍待完成。`/map link`、装备隐身配置和新版前端构建工具链尚未纳入。

## 安装与使用

从[本分支 Releases](https://github.com/misaka10843/squaremap-1.21.1-fork/releases)下载 NeoForge JAR，放入服务器 `mods` 目录，使用 Java 21 启动。Create 集成需要服务器安装 Create 6.x，普通地图可独立使用。

首次启动在服务器工作目录生成 `squaremap/`：

| 路径 | 用途 |
| --- | --- |
| `squaremap/config.yml` | 网页服务、图片格式、渲染并发、Create、玩家和世界设置。 |
| `squaremap/advanced.yml` | 方块过滤、群系与方块颜色覆盖。 |
| `squaremap/locale/<language-file>` | 网页文字、命令反馈和日志语言。 |
| `squaremap/web/` | 网页静态文件，默认图标位于 `images/icon/`。 |
| `squaremap/web/tiles/` | 地图瓦片与 JSON，维度目录使用 `minecraft_overworld` 等名称。 |
| `squaremap/data/` | 渲染进度等内部数据。 |

默认访问 `http://服务器地址:8080/`。对外地址由 `settings.web-address` 记录，监听地址与端口由 `settings.internal-webserver` 控制。反向代理应转发完整路径，SSE 位于 `api/events`。外部 Web 服务直接读取文件时启用 JSON 即时落盘，瓦片变化可通过 `changes.json` 获取。

升级前备份配置和地图数据。`web-directory.auto-update: true` 会更新已有网页文件；设为 `false` 会保留已有文件，并补齐缺失文件。自定义网页部署可手动更新 HTML、JS、CSS 和图片，再刷新浏览器缓存。已有损坏瓦片、旧降采样结果和新增方块颜色需要重新渲染才能更新。

| 常用命令 | 用途 |
| --- | --- |
| `/squaremap fullrender [world]` | 渲染已生成地图范围。 |
| `/squaremap radiusrender <world> <radius> [center]` | 按半径渲染，半径单位为方块；省略中心时使用 `0, 0`。 |
| `/squaremap pauserender [world]`、`/squaremap cancelrender [world]` | 暂停或取消渲染，具体参数见命令补全。 |
| `/squaremap reload` | 重载配置与网页服务。 |
| `/squaremap tiles gc <world>` | 清理空白或冗余瓦片。 |
| `/squaremap tiles migrate <world>` | 将旧瓦片迁移到当前格式并清理冗余文件。 |
| `/squaremap create status` | 查看 Create 集成状态。 |
| `/squaremap create enable`、`/squaremap create disable`、`/squaremap create toggle` | 开关 Create 集成并保存配置。 |
| `/squaremap create refresh` | 排队刷新静态铁路网络。 |

默认别名为 `/map`，世界参数以命令补全为准。Create 命令在集成可用时注册。

## 配置说明

内置服务的网络优化默认启用，无需反向代理：浏览器支持时压缩 JS、CSS、HTML、SVG 和 JSON 文本；PNG/WebP 图片保持原格式。带构建版本的 JS/CSS 可长期缓存，HTML 与未版本化文本继续验证更新。带版本的瓦片缓存 5 分钟，变化通知会切换 URL；缺失瓦片短暂缓存 30 秒。实时推送失败时恢复 HTTP 轮询，纯静态部署继续读取完整 JSON。

SSE 连接按需订阅，较大且压缩后有收益的数据包才使用 gzip 封装；不支持浏览器解压接口时发送普通 JSON。玩家坐标推送遵守各世界刷新间隔，Create 采样仍由 `settings.create-map` 控制。静态浏览模式仍通过 `?static=true` 开启。

以下键相对注明的节点，默认值以首次生成配置为准。`config-version` 由自动迁移管理。修改渲染或服务设置后执行 `/squaremap reload`。

### config.yml（完整默认配置与注释）

下面包含所有配置项。除末尾标注的世界覆盖示例外，值均为当前代码的默认值。修改后执行 `/squaremap reload`。

```yaml
config-version: 3 # 自动迁移版本，由程序维护
settings:
  language-file: lang-en.yml # 使用 squaremap/locale/ 下的语言文件
  debug-mode: false # 输出调试信息
  update-checker: true # 检查更新，目前检查上游 squaremap
  web-address: "http://localhost:8080" # 对外网页地址，不改变实际监听端口

  web-directory:
    path: web # 相对 squaremap/ 的网页目录，也支持绝对路径
    auto-update: true # 启动/重载时更新已有网页；false 保留已有文件，但补齐缺失文件
  internal-webserver:
    enabled: true # 使用内置 HTTP 服务
    bind: "0.0.0.0" # 监听地址
    port: 8080 # 监听端口
    flush-json-immediately: false # 外部 Nginx 等直接读取地图文件时设为 true，及时写入 JSON

  image-quality:
    tile-format: webp # webp 或 png；WebP 固定使用无损编码，非法值回退为 png
    keep-png: false # 写 WebP 时同时保存 PNG，便于兼容和恢复，会增加磁盘占用
    compress-images:
      enabled: false # 显式设置 PNG 编码压缩参数，对 WebP 无效
      value: 0.0 # 范围通常为 0–1，PNG 编码器收到 1-value；PNG 仍为无损格式
  player-head-cache:
    enabled: true # 启用同源玩家头像缓存，供 avatars/{uuid}.png 使用

  create-map:
    enabled: true # 安装 Create 6.x 时启用铁路集成
    update-interval-ticks: 20 # 动态数据采样间隔，至少 5 tick
    idle-timeout-seconds: 20 # 无网页活动后休眠的等待时间，至少 5 秒
    static-refresh-interval-seconds: 300 # 静态铁路网络校验间隔，至少 30 秒
    static-tick-budget-micros: 1000 # 每 tick 静态扫描预算，至少 100 微秒；1000 约为 1ms
    web-refresh-cooldown-seconds: 30 # 网页手动刷新冷却，至少 10 秒

  # 旧管线保留字段：新管线未使用这些值，实际限流请修改下方 map.render
  render-protection:
    snapshot-requests-per-thread: 16 # 当前不参与快照请求限流
    throttle-mspt: 35.0 # 当前不会按此 MSPT 阈值降速
    pause-mspt: 47.0 # 当前不会按此 MSPT 阈值暂停

  ui:
    coordinates:
      enabled: true # 显示坐标
    link:
      enabled: true # 显示网页位置链接控件
    sidebar:
      pinned: unpinned # pinned 展开、unpinned 自动、hide 隐藏侧栏
  commands:
    main-command-label: squaremap # 主命令名称
    main-command-aliases: [map] # 默认也可使用 /map
  render-progress-logging:
    enabled: true # 在控制台输出渲染进度
    interval-seconds: 1 # 日志间隔，单位秒

world-settings:
  default: # 所有世界的默认值；下方维度节点可以单独覆盖
    map:
      enabled: true # 是否启用该世界地图
      display-name: "{world}" # 网页世界名称
      order: 0 # 世界列表排序，较小值在前
      icon: "" # web/images/icon/ 下的 PNG 文件名，不含扩展名；留空按维度选默认图标
      iterate-up: false # 从下向上寻找表面，适用于下界等需要穿过基底的视图
      max-height: -1 # 渲染高度上限，-1 使用世界默认高度

      biomes:
        enabled: true # 使用群系颜色
        blend-biomes: 3 # 群系混色半径，范围 0–15，0 关闭混色
      glass:
        clear: true # 玻璃按透明层处理
      lava:
        checkerboard: true # 熔岩棋盘纹理
      water:
        clear-depth: true # 水层透明深度效果
        checkerboard: false # 水面棋盘纹理
      zoom:
        maximum: 3 # 原生最大缩放级别
        default: 3 # 打开世界时的缩放级别
        extra: 2 # 在原生瓦片之上额外放大的级数

      # 三种模式分别限流；多世界会共同占用服务器资源
      render:
        full: # 全图渲染
          workers: default # 扫描线程数，整数至少 1；default 按 JVM 可见处理器数自动计算
          max-chunk-requests: default # 在途快照请求数，至少 1；default = workers × 96
          max-active-chunks: default # 同时处理的区块数，至少 1；default = workers × 64
          max-active-regions: 2 # 同时处理的地图区域数，至少 1
          max-column-chunks: 16 # 每列批次的区块上限，至少 1
          idle-chunk-cache-size: default # 空闲快照条目数；default = max-active-chunks，0 关闭空闲缓存
        radius: # 半径渲染，各字段含义与 full 相同
          workers: default
          max-chunk-requests: default
          max-active-chunks: default
          max-active-regions: 2
          max-column-chunks: 16
          idle-chunk-cache-size: default
        background: # 变化区块的后台渲染
          enabled: true # 开启后台更新
          interval-seconds: 15 # 后台调度间隔，至少 1 秒
          max-chunks-per-interval: 2048 # 每次调度最多处理的区块数，至少 1
          workers: default # 自动值为全图线程数的三分之二，向下取整并至少为 1
          max-chunk-requests: default # 以下字段含义与 full 相同
          max-active-chunks: default
          max-active-regions: 2
          max-column-chunks: 16
          idle-chunk-cache-size: default
      image-saving: # 每世界共享的图片保存队列
        workers: 2 # 并行保存线程数，至少 1；同一瓦片仍按顺序写入
        max-pending-images: 100 # 等待保存完成的图片上限，至少 1，降低可减少队列占用

      markers:
        update-interval-seconds: 5 # API 标记刷新间隔
        spawn-icon:
          enabled: true # 显示出生点
          show-controls: true # 在侧栏提供图层开关
          default-hidden: false # 默认隐藏此图层
          layer-priority: 0 # 图层列表排序，较小值在前
          z-index: 0 # 地图叠放层级
        world-border:
          enabled: true # 显示世界边界
          show-controls: true
          default-hidden: false
          layer-priority: 1
          z-index: 1

      visibility-limits: # 限制地图可见范围；空列表 [] 表示不额外限制
        - type: world-border # 按游戏世界边界裁剪；默认示例未启用
          enabled: false # 改为 true 才生效
        # 也可增加以下范围，坐标与半径单位均为方块：
        # - type: circle
        #   center-x: 0
        #   center-z: 0
        #   radius: 5000
        # - type: rectangle
        #   min-x: -5000
        #   min-z: -5000
        #   max-x: 5000
        #   max-z: 5000
        # - type: polygon
        #   points: ["0 0", "5000 0", "2500 5000"]

    player-tracker:
      enabled: true # 显示玩家位置
      update-interval-seconds: 1 # 玩家数据刷新间隔
      show-controls: true # 在侧栏提供玩家图层开关
      default-hidden: false # 默认隐藏玩家图层
      layer-priority: 2 # 图层列表排序
      z-index: 2 # 地图叠放层级
      use-display-names: false # 使用玩家显示名
      nameplate:
        enabled: true # 显示名牌
        show-head: true # 显示头像
        heads-url: "avatars/{uuid}.png" # 头像 URL 模板，默认走同源缓存
        show-armor: true # 显示护甲
        show-health: true # 显示生命值
      hide:
        invisible: true # 隐藏隐身玩家
        spectators: true # 隐藏旁观玩家

  # 世界覆盖示例：只填写需要修改的值，其余继承 default
  # 配置键使用维度 ID，不是网页目录名 minecraft_overworld
  # "minecraft:overworld":
  #   map:
  #     display-name: 主世界
  # "minecraft:the_nether":
  #   map:
  #     iterate-up: true
```

自动线程数按 JVM 可见处理器数计算：全图与半径渲染在 1–7 个处理器时为 1、8–9 时为 2，更多时约为四分之一。提高并发会增加 CPU、内存和磁盘压力，降低负载时可先调小 `workers` 和 `max-chunk-requests`。

`map.render.*` 与 `map.image-saving.*` 还支持同路径 JVM 参数，例如 `-Dsquaremap.map.render.full.workers=2`，会覆盖各世界的 YAML 值。旧 `map.max-render-threads` 和 `map.background-render` 自动迁移到新节点。

### advanced.yml 与语言文件

`advanced.yml` 同样使用 `world-settings.default` 和维度覆盖节点：

| 键 | 说明 |
| --- | --- |
| `invisible-blocks` | 渲染时跳过的方块 ID 列表。默认包含草、蕨、灌木与干草，移除对应项可让它们显示。 |
| `iterate-up-base-blocks` | `iterate-up` 模式识别的基底，例如下界岩、玄武岩。 |
| `color-overrides.blocks` | 方块 ID 到 `"#RRGGBB"` 的颜色映射。 |
| `color-overrides.biomes.grass` | 覆盖指定群系的草颜色。 |
| `color-overrides.biomes.foliage` | 覆盖指定群系的树叶颜色。 |
| `color-overrides.biomes.dry-foliage` | 覆盖落叶等干燥植被颜色。 |
| `color-overrides.biomes.water` | 覆盖指定群系的水颜色。 |

```yaml
world-settings:
  default:
    color-overrides:
      blocks:
        "minecraft:pale_oak_leaves": "#626760"
        "yourmod:custom_block": "#AABBCC"
      biomes:
        water:
          "minecraft:plains": "#3F76E4"
```

颜色值加引号，避免被 YAML 当作注释。新版可选方块不存在时跳过，`minecraft` 名称会尝试对应 Vanilla Backport 名称；群系与普通基底配置应填写实际存在的 ID。配置迁移保留已有自定义方块颜色。

`locale/` 文件由 `settings.language-file` 选择，包含命令反馈、日志、网页标题和图层文字。编辑时保留 `{world}`、`{cur}`、`{max}` 等占位符。网页布局、颜色和图标由 `web/` 中的 CSS、JS、图片控制。

## 构建与手动发版

使用 Java 21 和仓库中的 Gradle wrapper：

```bash
./gradlew :squaremap-neoforge:build
# Windows：gradlew.bat :squaremap-neoforge:build
```

正式文件位于 `build/libs/squaremap-neoforge-mc1.21.1-<version>.jar`。构建核对最终 JAR 中的网页图片与源文件一致；只检查资源可运行 `:squaremap-neoforge:verifyWebResources`。Java 回归测试为 `:squaremap-common:test`，浏览器检查脚本为 `tools/test-web-map.cjs`，需要在 `build/diagnostics` 安装 Playwright，并使用 Microsoft Edge。

Push / PR 工作流只构建附件。发版使用 **Actions → Release NeoForge 1.21.1 (manual) → Run workflow**：

1. 更新 `gradle.properties` 的版本与本 README 的“本版变更”，提交并推送。
2. 选择分支，标签填写 `v` 加源码版本，例如 `v1.3.2-fork.3`。
3. 默认跳过测试，创建草稿预发布。勾选 `run_tests` 运行 Java 测试，取消 `draft` 会在该次手动运行时直接发布。
4. 上传正式 JAR 与 `SHA256SUMS`，从 README 提取发版说明。已有标签必须指向本次提交，已有 Release 不会被覆盖。

工作流使用 `GITHUB_TOKEN`，只有发布任务具有内容写权限。草稿可在 Releases 中审核后发布，不向 Modrinth、CurseForge 或 Maven 自动发布。

## API 与许可证

保留 squaremap API 模块，扩展可通过 API 绘制图标、形状和标记。上游 API 的 Maven 坐标为 `xyz.jpenilla:squaremap-api:1.3.2`，编译时使用 `compileOnly` / `provided`；本分支未单独发布 Maven 构件。

本项目使用 [MIT 许可证](LICENSE)，基于 [jpenilla/squaremap](https://github.com/jpenilla/squaremap)。Create 铁路提取设计与数据模型参考 Jennifer Chan / LittleChaSiu 的 Create Track Map，以及 AyOhEe 维护的 [NeoForge 1.21 分支](https://github.com/AyOhEe/create-track-map)，完整 MIT 文本见 [Create Track Map 许可证](licenses/create-track-map-MIT.txt)。该集成使用 Java 和现有 Undertow 服务。

随附 Leaflet 来自 Create Track Map 源码包，遵循 [BSD-2-Clause 许可证](licenses/leaflet-BSD-2-Clause.txt)，版权声明保留在分发文件中。
