# WBDAddon

WarZBombDefuse 的第三方附加组件合集 —— 击杀报告 / 单人伤害统计 / 比赛状态显示 / 拆弹声音提示 / 竞技场饥饿锁定 / JourneyMap 队伍桥接。

每个功能都是独立模块，通过 `config.yml` 单独开关，互不影响。无需改动 WBD 本体，纯附加。

> 已在 **Arclight 1.20.1**（Forge + Bukkit 混合端）环境实测运行。

> 🆕 **自 1.2.1 起，本插件对外暴露 `WbdAddonApi`。** 第三方游戏模式（例如 [WBDTeamDeathmatch](https://github.com/DICEALPHAHU/WBDTeamDeathMatch) 团队死斗）可以实现 `AddonMode` 注册进来，从而直接复用本插件的饥饿锁定、JourneyMap 桥接等功能，并共享同一套伤害账本与枪械名解析。详见 [对外 API](#对外-api)。

## 功能

| 模块 | id | 说明 |
| --- | --- | --- |
| 击杀报告 | `kill-reports` | 替换默认淘汰消息、显示击杀者与武器；回合结束给每个玩家单独发送自己的伤害统计 |
| 比赛状态 | `match-status` | 顶部 ActionBar 显示 T/CT 存活人数；炸弹安放后 BossBar 显示倒计时进度条 |
| 拆弹声音提示 | `bomb-defuse-sound` | CT 开始拆弹时在 C4 位置向全图播放可定位的提示音与滴答声，让 T 能回防博弈 |
| 竞技场饥饿锁定 | `arena-food` | 竞技场玩家饥饿恒定到安全值：跑速恒定、不自然回血 |
| 阵亡掉落 | `arena-drops` | WBD 取消致死伤害、不走原版死亡，本模块补上枪械掉落；炸弹携带者阵亡时 C4 落地（实验性，仅 T 可拾取） |
| 队伍护甲 | `team-armor` | T / CT 穿固定颜色的染色皮革甲，护甲值已抵消为 0；默认只填空槽，不顶掉玩家自己的护甲 |
| 回合事件 | `round-events` | 回合结束清理竞技场掉落物；回合开始时提示「上半场最终局」与「赛点」 |
| 药水效果清理 | `arena-effects` | 进场与每回合开始时清空药水效果，防止带抗性/速度/力量进场 |
| 进场位置兜底 | `position-backup` | 记录进场前的位置，服务器重启/插件卸载时传回，重启后登录再补一次 |
| 技术暂停 | `technical-pause` | `/wbdaddon pause` 管理员即时暂停、`/pause` 玩家每队限次（下一回合开始生效）；顶部 BossBar 倒计时，冻结回合与 C4 计时及玩家操作 |
| 冻结滤镜 | `freeze-filter` | 回合冻结开始先全黑、再黑暗脉动、结束前淡出（服务端近似，非真灰度） |
| JourneyMap 桥接 | `journeymap-bridge` | 同步 WBD 队伍数据到原版 Team，配合 JourneyMap Teams 队友可见、敌军隐藏、观战消失 |
| 模式接入 API | — | 通过 `WbdAddonApi` 让第三方模式复用上面这些能力，不必各写一份 |

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

## 致谢

感谢 [Crazy_Jky](https://www.minebbs.com/members/crazy-jky.88908/) 开发的 [WarZBombDefuse](https://www.minebbs.com/resources/folia-warzbombdefuse-cs-t-ct-tacz.17007/) 插件，并提供的 API 支持。

## 相关项目

- **WBDTeamDeathmatch** —— 基于本插件 `WbdAddonApi` 接入的团队死斗模式（无限复活、限时击杀赛）

## 依赖

- **Java 17**
- **Paper / Spigot / Arclight 1.20.1**
- **WarZBombDefuse**（必装）
- **TacZSpigotBridge**（可选，提供枪械名与更准确的击杀归属）
- **JourneyMap**（仅桥接模块需要）

## 安装

1. 把构建好的 `WBDAddon-<版本>-<git短哈希>-<构建时间戳>.jar` 放进服务端 `plugins`
2. 重启服务器（或 `/reload`）
3. 插件自动生成 `config.yml`，按需修改

> 旧配置升级后建议删除或合并新配置项，让新增模块段落生效。

## 配置

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
  arena-effects:
    enabled: true
    clear-on-join: true             # 进场时清空药水效果
    clear-on-round-start: true      # 每回合开始再清一次
    keep: []                        # 白名单，例如 [NIGHT_VISION]
    debug: false
  position-backup:
    enabled: true
    restore-on-disable: true      # 卸载/关服时把在场玩家传回
    restore-on-rejoin: true       # 重启后登录再补一次
    debug: false
  arena-drops:
    enabled: true
    drop-guns: true                 # 阵亡时掉枪
    drop-mode: held                 # held = 只掉正在用的那把；all = 背包里的枪全掉
    drop-c4: true                   # 炸弹携带者阵亡时 C4 落地
    allow-c4-drop: true             # 放开 WBD 对「手动丢 C4」的限制
    c4-self-pickup-block-ms: 5000   # 丢出后 5 秒内禁止丢包者自己捡回
    c4-display-name: "&cC4 炸弹"    # 地上 C4 的常显名牌
    debug: false
  team-armor:
    enabled: true
    update-interval-ticks: 20       # 补穿检查间隔
    t-color: RED                    # T 方染色
    ct-color: BLUE                  # CT 方染色
    zero-protection: true           # 抵消皮革甲自带的 7 点护甲
    overwrite-existing: false       # false=只填空槽，不顶掉玩家自己的护甲
  technical-pause:
    enabled: true
    admin-default-seconds: 60      # 管理员暂停默认时长
    admin-max-seconds: 300         # 管理员暂停上限
    team-count: 1                  # 每队每场可发起次数
    team-seconds: 60               # 玩家暂停时长
    freeze-timers: true            # 连 WBD 的回合/C4 倒计时一起冻结
    freeze-players: true           # 冻结玩家移动/伤害/交互
    paused-title: "&e技术暂停"
    resumed-title: "&a比赛继续"
  freeze-filter:
    enabled: true
    blind-ticks: 40               # 冻结开始全黑 2 秒
    darkness-until-live: true     # 之后黑暗脉动到回合开始
    fade-ticks: 20                # 提前 1 秒结束
  round-events:
    enabled: true
    clear-drops-on-round-start: true # 新回合开始时清理上一回合的掉落物
    half-final-enabled: true
    half-final-title: "&6&l上半场最终局"
    match-point-enabled: true
    match-point-title: "&c&l赛点"
  journeymap-bridge:
    enabled: true
    sync-interval-ticks: 20         # 同步间隔
    team-prefix: "wbd_"             # 原版队伍名前缀
```

执行 `/wbdaddon reload` 即可生效，无需重启。

## 对外 API

自 1.2.1 起，WBDAddon 不再只服务于 WarZBombDefuse 的爆破模式。它把「一个游戏模式」抽象成
`AddonMode` 接口并发布 `WbdAddonApi` 服务，任何第三方模式都可以注册进来，
让上面那些模块**自动**对新模式的玩家生效。

### 为什么需要这层抽象

改造前，每个模块都用同一句话判断「这个玩家在不在竞技场」：

```java
wbd.getArenaManager().getSession(player) != null
```

这句话把「在竞技场里」和「在 WBD 的爆破模式里」划了等号。一个独立实现的模式
（比如团队死斗）玩家不在 WBD 的 `ArenaSession` 里，于是所有模块都看不见他们 ——
饥饿不锁、防偷看无效、JourneyMap 上队友敌军分不清，而且**不会报任何错**。

现在模块改问 `AddonMode`：参赛了吗、还活着吗、属于哪一队。爆破模式本身也变成了
这个接口的一个实现（内置注册，永远排在第一位）。

### 用法

```java
public final class MyMode implements AddonMode {

    @Override public String getModeId()          { return "my-mode"; }   // 稳定标识
    @Override public String getDisplayName()     { return "我的模式"; }
    @Override public boolean isParticipating(Player p) { return match(p) != null; }
    @Override public boolean isPlaying(Player p)       { return match(p) != null; }
    @Override public boolean isAlive(Player p)         { return match(p) != null && match(p).isAlive(p); }
    @Override public String getTeamId(Player p)        { return match(p).teamId(p); }  // "T" / "CT" / null
    @Override public String getArenaId(Player p)       { return match(p).arenaId(); }
    @Override public String getArenaName(Player p)     { return match(p).arenaName(); }

    /** 可选：申请由 WBDAddon 代渲染顶部状态行（它会处理 Arclight 上的发送兼容问题） */
    @Override public String hudLine(Player p)          { return match(p).scoreLine(); }
}
```

注册与注销：

```java
@Override
public void onEnable() {
    RegisteredServiceProvider<WbdAddonApi> rsp =
            Bukkit.getServicesManager().getRegistration(WbdAddonApi.class);
    if (rsp == null) {
        // WBDAddon 没装，或它的服务还没发布（本插件不在它的 depend/softdepend 里）
        getLogger().info("未检测到 WBDAddon，附加功能不可用。");
        return;
    }
    WbdAddonApi api = rsp.getProvider();
    api.registerMode(new MyMode());
}

@Override
public void onDisable() {
    // 一定要注销：否则 WBDAddon 的定时任务会一直踩到一个已经卸载的模式对象
    Bukkit.getServicesManager().getRegistration(WbdAddonApi.class)
            .getProvider().unregisterMode("my-mode");
}
```

> 在 `plugin.yml` 里写 `softdepend: [WBDAddon]` 就能保证自己的 `onEnable`
> 一定在服务发布之后执行，不必延迟到下一 tick。

### 注册后自动获得什么

| 能力 | 由哪个模块提供 | 触发条件 |
| --- | --- | --- |
| 饥饿锁定（跑速恒定、不回血） | `arena-food` | `isParticipating` 为 true |
| JourneyMap 队友可见 / 敌军隐藏 | `journeymap-bridge` | `isAlive` 且有 `getTeamId` |
| 顶部状态行代渲染 | `match-status` | 实现了 `hudLine`，且该模块启用 |
| 伤害账本（击杀归属校正） | `kill-reports` | 通过 `api.getCombatStats()` 取用 |
| 枪械名解析（TacZ GunId → `P320`） | 内置 | 通过 `api.resolveWeaponName(player)` 取用 |

### 必须注意的两点

1. **`hudLine` 只是「申请」，不保证有人发。** 代渲染取决于 `match-status` 模块是否启用、
   它的顶栏开关有没有被服主关掉。注册后请调用一次 `api.isHudRelayAvailable()`，
   返回 `false` 时自己把 HUD 发出去 —— 否则玩家屏幕上会什么都没有，且不报错。

2. **JourneyMap 桥接受「玩家当前使用哪块计分板」影响。** 原版队伍数据跟着玩家当前所用的
   计分板下发，所以模式如果给玩家 `setScoreboard(自己的独立计分板)`，就会顺带把
   `wbd_*` 队伍数据从该玩家那里屏蔽掉，JourneyMap 于是读不到队友关系。
   想同时要自定义侧边栏和 JourneyMap 桥接，就把侧边栏注册在**主计分板**上
   （`WBDTeamDeathmatch` 就是这么做的）。

### 队伍名与多模式共存

`journeymap-bridge` 生成的原版队伍名形如 `<前缀><模式id>_<阵营id>`（例：`wbd_bomb_def_T`）。
带上模式 id 是必要的：爆破与死斗都有 T/CT，若只按阵营拼名，两个模式的玩家会挤进
同一个原版队伍，在 JourneyMap 上互相看得见 —— 那等于把敌军位置显示给了对手。
名字会被裁剪到原版 16 字符上限以内。

## ⌨️ 命令

- `/wbdaddon reload` —— 重载配置并重新启停模块
- `/wbdaddon modules` —— 查看各模块启用状态
- `/wbdaddon modes` —— 查看已接入的游戏模式与各自的在线人数
- `/wbdaddon sound <音效名> [音调]` —— 试听拆弹提示音（连播三次）；不带参数列出推荐音效清单

> 排查「第三方模式接进来了但附加功能没生效」时先看 `/wbdaddon modes`：
> 模式没出现说明注册没成功（多半是加载顺序）；模式在、人数为 0 说明它的
> `isParticipating` 判定写错了。

## 构建

> ⚠️ 构建**前必须先自己把 `wbd.jar` 放进 `libs/` 目录**。

WBDAddon 依赖的是**闭源收费**的 WarZBombDefuse API（`pom.xml` 里是 system-scope，直接指向 `libs/wbd.jar`）。这个 jar **不随本项目代码分发**,你需要自行获取（简而言之就是得去[Minebbs](https://www.minebbs.com/resources/folia-warzbombdefuse-cs-t-ct-tacz.17007/)买）：

1. 从 WarZBombDefuse 作者处取得 `wbd.jar` ,放入 `libs/`
2.（可选，推荐）放入 `taczspigotbridge.jar`,以获得枪械名显示与更准确的击杀归属
3. 然后再构建:

```bash
mvn clean package
```

产物在 `target/WBDAddon-<版本>-<git短哈希>-<构建时间戳>.jar`，例如 `WBDAddon-1.2.1-0e65b2d-20261013-0250.jar`。

> 产物名里的短哈希来自 git-commit-id 插件，时间戳是 UTC。这样同一版本的多次构建不会互相覆盖，也能一眼对应到是哪份代码；拿不到 git 信息时（例如源码被拷出仓库）会显示 `nogit`，构建不会失败。

## 许可证

开源协议GPL-3.0。互联网共享精神，所以**不准拿我的代码搞闭源！** 详见仓库 LICENSE 文件。

## 反馈

- 反馈 QQ：2387629002
- 欢迎提 Issue 与 Pull Request
