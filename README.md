# WBDAddon

WarZBombDefuse 的第三方附加组件合集 —— 击杀报告 / 单人伤害统计 / 比赛状态显示 / 拆弹声音提示 / 竞技场饥饿锁定 / JourneyMap 队伍桥接 / 装备槽守卫 / 比赛投票 / 断线重联。

每个功能都是独立模块，通过 `config.yml` 单独开关，互不影响。无需改动 WBD 本体，纯附加。

> 📘 **完整配置说明**见 [配置说明.md](src/main/resources/配置说明.md)，插件首次启动也会把它释放到 `plugins/WBDAddon/配置说明.md`。换服务器前请先核对「必须核对的几处」（武器分类 / 商品落点 / 内部字段反射）。

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
| 冻结倒计时音效 | `freeze-countdown` | 回合冻结最后 4 秒每秒一声轻「哒」，音调逐秒升高；冻结不足时只取放得下的最后几秒 |
| 主菜单 | `menu` | `/wbda` 或 Shift+F 打开箱子菜单：模式选择（爆破/死斗）、匹配、当前比赛、比赛总结、个人资料；比赛历史（含每名玩家 K/D）落盘到 `menu-results.yml`，生涯数据在 `player-stats.yml` |
| 回合结算展示 | `round-summary` | 回合结束标题显示胜方 + MVP，副标题随机挑一条本场趣闻（全歼用时 / 首杀 / 命中率 / 爆头等） |
| JourneyMap 桥接 | `journeymap-bridge` | 同步 WBD 队伍数据到原版 Team，配合 JourneyMap Teams 队友可见、敌军隐藏、观战消失 |
| 断线重联 | `reconnect` | 比赛中有人掉线时替他守住下一回合（回合结算处暂停，等他回来）；每队每场 1 次；重连自动回原阵营并继承经济与战绩 |
| 比赛投票 | `vote` | `/vyes` / `/vno` 表态，BossBar 实时显示事项与票数；`/unpause` 提前解除暂停、`/vkick` 踢人、`/vmap` 换图、`/vsdr` 投降 |
| 装备槽守卫 | `loadout-guard` | 主武器 / 副武器 / 近战各限 1 把并锁定到 1/2/3 号格；投掷物最多 4 颗并锁定 4 号格；购买同类替换旧武器并退款；拾取同类必须先丢掉现有那把 |
| 模式接入 API | — | 通过 `WbdAddonApi` 让第三方模式复用上面这些能力，不必各写一份 |

### 击杀报告（kill-reports）
- 淘汰提示显示击杀者与武器（TacZ 枪械按 GunId 人性化改名：`tacz:p320 → P320`）
- **武器名转换表** `weapon-names.yml`：把 `twx:twx_pxbq → TWX PXBQ` 改成「VK47 平行步枪」这类名字，击杀播报、回合趣闻、`%weapon%` 占位符统一生效；首次启动自动生成含全部 GunId 的模板
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

### 装备槽守卫（loadout-guard）
- **主武器** 1 把 → 快捷栏第 1 格；**副武器（手枪）** 1 把 → 第 2 格；**近战武器** 1 把 → 第 3 格
- **投掷物** 最多 4 颗 → 第 4 格放一堆，其余自动留在背包；第 5 颗起拒绝并退款
- **购买同类不同武器**：旧武器按 WBD `shop.yml` 的售价退款，新武器替换进对应槽位
- **购买刀**：不用管同款拦截，直接换掉手里那把（刀不要钱，也不退旧刀），背包里不会堆旧刀
- **购买同类同一把枪**：默认取消本次购买并退款，防止用「买同一把枪」免费补弹；可用 `same-gun-policy` 改成付费换新或替换退款
- **拾取同类武器**：身上已经有一把时直接拦截，必须先丢掉现有那把才能捡新的
- **分类对照表** `item-categories.yml`：`guns`（按 GunId）/ `names`（按显示名）/ `materials`（按材质）/ `types`（按 TacZ 类型）四张表，腐竹自己决定谁归哪类，自带注释；首次启动按现有 config 生成
- 分类来源：对照表 > 物品标签 > TacZ `type`；确保非 TacZ 物品也能归类
- **分类结果写在物品上**（PDC `loadout: primary/secondary/melee/throwable/utility`）：认一次就固定，之后换 TacZ 版本、改 `gun-overrides` 都不会让已发出的武器忽然换类别；认错了用 `/wbdaddon retag` 洗掉重认
- 线上 8 把 Apex 刀在 TACZ index 里是 `sniper`，配置里已用 `gun-overrides` 覆盖成 `melee`
- 不改 WBD 的 `shop.yml`，也不影响 `/wbd shopedit`；价格在打开商店时按文件修改时间自动刷新
- **绿宝石保护**：商店开物品不能丢弃、不能换到副手，被拖走/顶掉/清掉后自动放回固定槽位
- **连按开商店**：默认 450ms 内连按两下 F（`double-swap`），单按 F 仍是原版换手；也可换成单按 F、连按两下 Shift，或关掉。`sneak-swap-hands`（Shift+F）留给 `/wbda` 菜单用
- **购买兜底**：WBD 发物品不看「发不出去」——背包 36 格全满时 `addItem` 的剩余堆叠被直接丢弃，配了 `give-slot` 的商品还会 `setItem` 顶掉那一格，两种情况钱都照扣。本模块在扣钱瞬间抓一份背包快照，事后核对：东西没到手就按比例退款，落点格被顶掉就把旧物品还回去（放不下丢脚下）
- 权限 `wbdaddon.loadout.bypass` 可绕过（默认 OP）
- **需要 TacZSpigotBridge**：本模块的类直接引用桥接 API，缺桥接时会自动跳过（不会拖垮其他模块）

### 比赛投票（vote）
- **BossBar 实时计票**：发起后顶部显示 `投票 | 踢出 Steve | 同意 3 / 反对 0 | 剩余 21s`，通过/否决/超时后立刻收起
- `/vyes` 同意、`/vno` 反对；够票立即通过，票数已经不可能达标就立即否决，不必等到超时
- **`/unpause`**：技术暂停期间发起全场投票，T + CT **全员同意**才提前解除暂停
- **`/vkick <队友>`**：同阵营投票踢人。被踢者**默认反对**（发起时就算进反对票，他不能给自己投同意），其余队友**全员同意**才生效；踢出后本场不能再加入（菜单与加入事件双重拦截），下一场自动解禁
- **`/vmap <地图>`**：全场投票换图，**超过 2/3** 同意后提前结算本场比赛，3 秒后把场上所有人（含观战）搬到新地图并自动开新的一场
- **`/vsdr`**：同阵营投票投降，**全队一致同意**后提前结束比赛，判对方阵营获胜
- 提前结算走 WBD 自己的 `endMatch`，不会多算一分、不会打乱比分，照常广播胜方与整场总结
- 同一房间同时只允许一个投票；同一类投票结束有冷却（默认 15 秒），防止连着刷

### 断线重联（reconnect）
- 比赛进行中（`LIVE` / `FREEZE` / `ROUND_END`）有参赛玩家掉线，**当前回合照常打完**，在回合结算的间隙把 WBD 的 tick 停住，于是下一回合永远「还没开始」——直到他回来
- 为什么不在下一回合的冻结期暂停：WBD 在 `FREEZE` / `LIVE` 中途加入的玩家只会排队等**再下一个**回合，冻结点暂停也救不回来；而在 `ROUND_END` 加入的正好排队，一恢复就跟着下一回合出生
- **每支队伍每场只有一次**这样的待遇，用过之后再有人掉线只提示一句，不再定住比赛
- **多人掉线共用同一次暂停**：同一波掉几个人（网络抖动、一队集体掉线）只停一次表，回来一个就把他从名单里划掉，顶栏显示 `等待 AlphaHu 等 3 人`；名额按阵营算，同一队三个人一起掉也只消耗该队这一次
- **两个时间窗**：比赛只等 **60 秒**（一个技术暂停），到点自动继续；但掉线后 **5 分钟内**回来仍然算重连，会自动放回原比赛并继承数据。超过 5 分钟就当弃赛，记录清空
- 管理员 `/wbdaddon resume`、`/unpause` 投票也可以提前结束等待
- 玩家重连后**自动放回原阵营**，并把掉线前的**经济、击杀、死亡、下包、拆包**写回新记录（WBD 掉线时会直接删记录，不写回就等于清零）
- 顶栏显示 `等待重连 | AlphaHu | 剩余 04:12`，等待期间与其他技术暂停一样冻结玩家操作

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
  freeze-countdown:
    enabled: true
    seconds: 4                    # 最后 4 秒开始（不足时自动取最后几秒）
    sound: BLOCK_NOTE_BLOCK_HAT   # 轻「哒」声
    volume: 0.35                  # 音量压小
    base-pitch: 1.6               # 起始音调
    pitch-step: 0.15              # 每秒升调
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
  loadout-guard:
    enabled: true
    lock-interval-ticks: 10         # 槽位锁定兜底检查间隔
    slots:                          # 0-8 为快捷栏；四项必须互不相同
      primary: 0
      secondary: 1
      melee: 2
      throwable: 3
    limits:
      primary: 1                    # 固定 1，改大也不会放宽
      secondary: 1                  # 固定 1
      melee: 1                      # 固定 1
      throwable: 4                  # 总颗数；0 = 关闭上限
    same-gun-policy: block          # block / replace-no-refund / replace-refund
    refund-old-weapon: true         # 替换不同武器时退旧武器商店价
    drop-duplicates: true           # 多出的同类丢到脚下
    gun-overrides:                  # 8 把 Apex 刀在 TACZ index 里是 sniper
      "twx:twx_kw": melee
      "twx:twx_nbejd": melee
      "twx:twx_hdd": melee
      "twx:twx_sc": melee
      "twx:twx_nlj": melee
      "twx:twx_mqzz": melee
      "twx:twx_xsd": melee
      "twx:twx_mk": melee
  vote:
    enabled: true
    duration-seconds: 30            # 一次投票持续多久
    cooldown-seconds: 15            # 同类投票结束后的冷却
    include-spectators: false       # 全场投票是否算上观战者
  reconnect:
    enabled: true
    hold-max-seconds: 60            # 比赛最多等多久；0 = 不限时
    pending-max-seconds: 300        # 多久内回来还算重连；0 = 永不失效
    auto-rejoin: true               # 重连后自动放回原比赛
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
