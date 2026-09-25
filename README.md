# WBDAddon

WarZBombDefuse 的第三方附加组件合集 —— 击杀报告 / 单人伤害统计 / 比赛状态显示 / 拆弹声音提示 / 竞技场饥饿锁定 / 防第三人称偷看 / JourneyMap 队伍桥接。

每个功能都是独立模块，通过 `config.yml` 单独开关，互不影响。无需改动 WBD 本体，纯附加。

> 已在 **Arclight 1.20.1**（Forge + Bukkit 混合端）环境实测运行。

## 功能

| 模块 | id | 说明 |
| --- | --- | --- |
| 击杀报告 | `kill-reports` | 替换默认淘汰消息、显示击杀者与武器；回合结束给每个玩家单独发送自己的伤害统计 |
| 比赛状态 | `match-status` | 顶部 ActionBar 显示 T/CT 存活人数；炸弹安放后 BossBar 显示倒计时进度条 |
| 拆弹声音提示 | `bomb-defuse-sound` | CT 开始拆弹时在 C4 位置向全图播放可定位的提示音与滴答声，让 T 能回防博弈 |
| 竞技场饥饿锁定 | `arena-food` | 竞技场玩家饥饿恒定到安全值：跑速恒定、不自然回血 |
| JourneyMap 桥接 | `journeymap-bridge` | 同步 WBD 队伍数据到原版 Team，配合 JourneyMap Teams 队友可见、敌军隐藏、观战消失 |
| 防第三人称 | `antithirdcam` | 给参赛玩家挂一块只对本人可见的巨大遮挡面，让第三人称视角失去隔墙偷看的价值 |

### 击杀报告（kill-reports）
- 淘汰提示显示击杀者与武器（TacZ 枪械按 GunId 人性化改名：`tacz:p320 → P320`）
- **伤害统计单独发给每个玩家**：「你对 XX 造成了 X 伤害」，不全局刷屏
- 修正 TacZ 致死伤害在 Forge 层漏记的问题，**血包回血场景下也能正确累计真实总伤害**

### 比赛状态（match-status）
- 屏幕顶部实时显示双方存活人数：`T 存活 3/5   CT 存活 4/5`
- 炸弹安放后头顶 BossBar 显示倒计时进度条，爆 / 拆后消失，颜色与样式可配置

### 拆弹声音提示（bomb-defuse-sound）
- WBD 本体**只在拆除成功与被中断时有音效，开始拆弹与整个拆弹过程是静音的**，T 阵营因此无法察觉 CT 在拆弹
- 本模块在 C4 的位置播放一声「开始拆弹」提示音，随后按固定间隔持续播放滴答声
- 声音以 C4 为音源，**带方位与距离衰减**——T 能听出「有人在拆、大概在哪个方向」，而不是只收到一句广播
- 音量决定可听距离（约 16 × 音量 格，默认 2.0 ≈ 32 格，只有靠近炸弹的人才听得到）；客户端会对音量限幅，调大只传得更远，不会把近处玩家震聋
- 听众默认只限该竞技场内玩家（含已淘汰的旁观者），与 WBD 自身 `bomb-planted` 的做法一致；也可切到 `world` 连大厅一起播
- 中断与成功音效默认留空，因为 WBD 本体已经有了，避免重复出声
- 音效可换：默认用 `BLOCK_NOTE_BLOCK_BIT`（8-bit 芯片音色）做电子「嘀嘀嘀」，用 `/wbdaddon sound` 可以试听并查看推荐清单

### 竞技场饥饿锁定（arena-food）
- 跑步消耗饥饿、饿到跑不动，或满饥饿自然回血，都会不公平
- 把竞技场玩家饥饿恒定到 17（跑速恒定、不自然回血），只靠血包 / 治疗，公平竞技
- 只作用于竞技场玩家，不影响大厅与全局

### JourneyMap 桥接（journeymap-bridge）
- 把 WBD 队伍数据写入原版 Scoreboard Team，配合客户端 JourneyMap Teams 实现队友可见、敌军隐藏、观战消失

### 防第三人称（antithirdcam）
- 第三人称视角是纯客户端按键，服务端既禁不掉也侦测不到，所以改用「遮挡」思路
- 给参赛玩家挂一块巨大的黑色遮挡面，且**只对该玩家本人可见**；切到第三人称时视野会被糊住，隔着掩体偷看就失去意义了
- 遮挡面默认每 tick 主动跟随玩家（`follow-mode: teleport`），也可切回开销更低的「乘客」机制（`passenger`）
- 只在竞技场内对**参赛且仍存活**的玩家生效；观战者、大厅玩家、以及本回合已阵亡的人都不受影响
- **可见性由 ProtocolLib 在数据包层拦截实现**：遮挡面的生成 / 位置 / 元数据包只放行给本人，其他玩家的客户端压根收不到这个实体
- 之所以不用 Bukkit 的 `hideEntity`：实测 Arclight 上它**方法存在、调用不报错、但完全不生效**（`setVisibleByDefault` 同理），于是遮挡面对所有人可见，看着就像「黑块挂在别人身上」
- 没装 ProtocolLib 时**模块会自动禁用**（由 `require-protocol-lib: true` 控制），避免出现「功能没生效、黑块却挂在所有人身上」这种最糟的组合；只有在 Paper 系服务端上、并确认 `hideEntity` 确实有效时，才建议设为 `false` 退回 Bukkit API。当前环境是否满足用 `/wbdaddon diag` 查看
- 实现方案参考开源插件 [AntiF5](https://github.com/ladakx/AntiF5)

## 致谢

感谢 [Crazy_Jky](https://www.minebbs.com/members/crazy-jky.88908/) 开发的 [WarZBombDefuse](https://www.minebbs.com/resources/folia-warzbombdefuse-cs-t-ct-tacz.17007/) 插件，并提供的 API 支持。

## 依赖

- **Java 17**
- **Paper / Spigot / Arclight 1.20.1**
- **WarZBombDefuse**（必装）
- **TacZSpigotBridge**（可选，提供枪械名与更准确的击杀归属）
- **ProtocolLib**（可选；但**防第三人称模块在 Arclight 上必须装**，否则遮挡面藏不住）
- **JourneyMap**（仅桥接模块需要）

## 安装

1. 把 `WBDAddon-1.0.1.jar` 放进服务端 `plugins`
2. 重启服务器（或 `/reload`）
3. 插件自动生成 `config.yml`，按需修改

> 旧配置升级后建议删除或合并新配置项，让新增模块段落生效。

## ⚙️ 配置

```yaml
modules:
  kill-reports:
    enabled: true
    replace-default-death-message: true  # 是否替换 WBD 默认的淘汰消息
    show-round-summary: true        # 是否给每人单独发送伤害统计
    damage-decimal-places: 1        # 伤害数值保留几位小数
  match-status:
    enabled: true
    enable-alive-action-bar: true   # 存活人数顶部显示
    enable-bomb-bar: true           # 炸弹倒计时条
    update-interval-ticks: 2        # 刷新间隔，20 tick = 1 秒
    alive-display-style: number     # number = 数字「3/5」；block = 方块串
  bomb-defuse-sound:
    enabled: true
    start-sound: BLOCK_NOTE_BLOCK_BIT   # 开始拆弹：低沉一声「嘟」，留空 "" 关闭
    start-pitch: 0.7
    start-volume: 2.0                   # 16 × 音量 ≈ 可听格数（2.0 ≈ 32 格）
    tick-sound: BLOCK_NOTE_BLOCK_BIT    # 拆弹中：电子「嘀嘀嘀」，留空 "" 关闭
    tick-pitch: 1.8
    tick-interval-ticks: 10             # 滴答间隔，20 tick = 1 秒
    cancel-sound: ""                    # 默认留空：WBD 本体已有 cancel 音效
    complete-sound: ""                  # 默认留空：WBD 本体已有 bomb-defused 音效
    audience: arena                     # arena = 仅该竞技场；world = 整个世界
  arena-food:
    enabled: true
    update-interval-ticks: 5        # 重置间隔
    food-level: 17                  # 恒定饥饿值（跑速恒定 + 不回血）
    saturation: 0                   # 饱和度清零，封死自然回血
  antithirdcam:
    enabled: true
    update-interval-ticks: 20       # 只做低频检查、丢失时重建
    follow-mode: teleport           # teleport = 每 tick 主动跟随；passenger = 骑在身上
    only-in-arena: true             # 只对竞技场参赛且存活的玩家生效
    overlay-text: "§0█"             # 遮挡面字形，默认黑色实心方块
    translation-y: -16.0            # 渲染平移，位置不对时调这里
    scale-x: 128.0                  # 缩放倍数：挡不住就加大，误挡就减小
    scale-y: 128.0
    scale-z: 128.0
  journeymap-bridge:
    enabled: true
    sync-interval-ticks: 20         # 同步间隔
    team-prefix: "wbd_"             # 原版队伍名前缀
```

执行 `/wbdaddon reload` 即可生效，无需重启。

## ⌨️ 命令

- `/wbdaddon reload` —— 重载配置并重新启停模块
- `/wbdaddon modules` —— 查看各模块启用状态
- `/wbdaddon sound <音效名> [音调]` —— 试听拆弹提示音（连播三次）；不带参数列出推荐音效清单

## 构建

> ⚠️ 构建**前必须先自己把 `wbd.jar` 放进 `libs/` 目录**。

WBDAddon 依赖的是**闭源收费**的 WarZBombDefuse API（`pom.xml` 里是 system-scope，直接指向 `libs/wbd.jar`）。这个 jar **不随本项目代码分发**,你需要自行获取（简而言之就是得去[Minebbs](https://www.minebbs.com/resources/folia-warzbombdefuse-cs-t-ct-tacz.17007/)买）：

1. 从 WarZBombDefuse 作者处取得 `wbd.jar` ,放入 `libs/`
2.（可选，推荐）放入 `taczspigotbridge.jar`,以获得枪械名显示与更准确的击杀归属
3. 然后再构建:

```bash
mvn clean package
```

产物在 `target/WBDAddon-1.0.1.jar`。

## 📜 许可证

开源协议GPL-3.0。互联网共享精神，所以**不准拿我的代码搞闭源！** 详见仓库 LICENSE 文件。

## 💬 反馈

- 反馈 QQ：2387629002
- 欢迎提 Issue 与 Pull Request
