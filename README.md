# WBDAddon

WarZBombDefuse 的第三方附加组件合集：击杀报告、伤害统计、比赛状态、拆弹提示音、饥饿锁定、装备槽守卫、投票、断线重联、昵称标签、观战视角等 22 个独立模块。

每个模块都是独立的开关，在 `config.yml` 里单独启用或关闭，互不影响。**不改 WBD 本体**，纯附加，WBD 更新后一般不受影响。

完整配置说明见 [配置说明.md](src/main/resources/配置说明.md)，插件首次启动也会把它释放到 `plugins/WBDAddon/配置说明.md`。换服务器前建议先核对「必须核对的几处」：武器分类、商品落点、依赖内部字段的反射。

已在 Arclight 1.20.1（Forge + Bukkit 混合端）实测运行。

自 1.2.1 起本插件对外暴露 `WbdAddonApi`：第三方游戏模式（例如 [WBDTeamDeathmatch](https://github.com/DICEALPHAHU/WBDTeamDeathMatch) 团队死斗）可以实现 `AddonMode` 注册进来，直接复用饥饿锁定、JourneyMap 桥接等能力，并共享同一套伤害账本与枪械名解析。详见 [对外 API](#对外-api)。

---

## 目录

- [模块一览](#模块一览)
- [模块说明](#模块说明)
- [安装](#安装)
- [配置](#配置)
- [命令](#命令)
- [对外 API](#对外-api)
- [构建](#构建)
- [致谢](#致谢)
- [许可证](#许可证)

---

## 模块一览

| 模块 | id | 说明 |
| --- | --- | --- |
| 击杀报告 | `kill-reports` | 替换默认淘汰消息、显示击杀者与武器；回合结束给每人单独发送自己的伤害统计 |
| 比赛状态 | `match-status` | 顶部 ActionBar 显示双方存活人数；炸弹安放后 BossBar 显示倒计时 |
| 拆弹声音提示 | `bomb-defuse-sound` | CT 开始拆弹时在 C4 位置播放可定位的提示音与滴答声 |
| 竞技场饥饿锁定 | `arena-food` | 竞技场内饥饿恒定到安全值：跑速恒定、不自然回血 |
| 药水效果清理 | `arena-effects` | 进场与每回合开始时清空药水效果 |
| 进场位置兜底 | `position-backup` | 记录进场前位置，重启或卸载时传回，重启后登录再补一次 |
| 阵亡掉落 | `arena-drops` | 补上枪械掉落；炸弹携带者阵亡时 C4 落地 |
| 队伍护甲 | `team-armor` | T / CT 穿固定颜色的染色皮甲，护甲值抵消为 0 |
| 装备槽守卫 | `loadout-guard` | 武器分类、限购、固定槽位、预留格、购买兜底、开商店按键 |
| 回合事件 | `round-events` | 回合结束清理掉落物；提示「上半场最终局」与「赛点」 |
| 回合结算展示 | `round-summary` | 回合结束标题显示胜方与 MVP，副标题随机挑一条本场趣闻 |
| 冻结倒计时音效 | `freeze-countdown` | 冻结最后 4 秒每秒一声轻「哒」，音调逐秒升高 |
| 主菜单 | `menu` | `/wbda` 或 Shift+F 打开箱子菜单；含管理员面板 |
| 技术暂停 | `technical-pause` | 管理员即时暂停、玩家每队限次；冻结回合、C4 计时与玩家操作 |
| 比赛投票 | `vote` | `/vyes` `/vno` 表态；`/unpause` `/vkick` `/vmap` `/vsdr` |
| 断线重联 | `reconnect` | 有人掉线时替他守住下一回合，回来继承经济与战绩 |
| 少人经济补偿 | `short-handed-compensation` | 从指定回合起，按开局人数差给少人方补偿 |
| 昵称标签 | `name-tag` | 队友看得见名牌、敌人看不见，按观察者计分板逐人控制 |
| 商店价格标签 | `shop-price` | 商品 lore 下方显示售价，买得起绿、买不起红 |
| 观战第一视角 | `spectate-camera` | 阵亡后跟随存活队友的第一视角，含死亡缓冲与团灭镜头 |
| 队友轮廓发光 | `teammate-glow` | 只对同阵营显示队友轮廓，敌人看不到（ProtocolLib 逐人发包） |
| JourneyMap 桥接 | `journeymap-bridge` | 同步队伍到原版 Team，配合 JourneyMap 实现队友可见、敌军隐藏 |

---

## 模块说明

### 击杀报告（kill-reports）

- 淘汰提示显示击杀者与武器，TacZ 枪械按 GunId 转成可读名字
- **手雷 / 燃烧瓶击杀归属**：爆炸、区域云、火焰这类伤害原本没有凶手，本模块解析伤害来源（投射物 shooter、区域云来源、最近的投掷者），致死时按正确凶手结算
- **武器名转换表** `weapon-names.yml`：把 `tacz:ak47` 改成「AK-47」这类自定义名字，击杀播报、回合趣闻、`%weapon%` 占位符统一生效；首次启动生成含全部 GunId 的模板
- **伤害统计单独发给每个玩家**，不全局刷屏
- 修正 TacZ 致死伤害在 Forge 层漏记的问题，血包回血场景下也能正确累计真实总伤害

### 比赛状态（match-status）

- 顶部实时显示双方存活人数：`T 存活 3/5   CT 存活 4/5`
- 炸弹安放后顶部 BossBar 显示倒计时进度条，爆 / 拆后消失，颜色与样式可配置

### 拆弹声音提示（bomb-defuse-sound）

WBD 本体只在拆除成功与被中断时有音效，**开始拆弹与整个拆弹过程是静音的**，T 阵营无法察觉 CT 在拆弹。本模块在 C4 位置播放开始提示音，随后按固定间隔播放滴答声。

- 以 C4 为音源，**带方位与距离衰减**，能听出「有人在拆、大概在哪个方向」
- 音量决定可听距离（约 16 × 音量 格，默认 2.0 约 32 格）
- 听众默认只限该竞技场内玩家（含旁观者），也可切到整个世界
- 中断与成功音效默认留空，避免与 WBD 本体重复

### 竞技场饥饿锁定（arena-food）

跑步消耗饥饿、饿到跑不动，或满饥饿自然回血，都会不公平。把竞技场玩家饥饿恒定到 17、饱和度清零：跑速恒定、不自然回血，只靠血包与治疗。只作用于竞技场玩家。

### 药水效果清理（arena-effects）

进场与每回合开始时清空药水效果，防止带抗性、速度、力量进场。支持白名单（例如只保留夜视）。

### 进场位置兜底（position-backup）

记录玩家进场前的位置，插件卸载 / 关服时把人传回去；服务器重启后登录再补一次，避免玩家被留在竞技场里。

### 阵亡掉落（arena-drops）

WBD 取消致死伤害、不走原版死亡流程，本模块补上掉落：枪械掉落（只掉手里那把或背包里全部可配）、炸弹携带者阵亡时 C4 落地、C4 常显名牌、丢包后短时间禁止自己捡回。

### 队伍护甲（team-armor）

T / CT 穿固定颜色的染色皮革甲，护甲值已抵消为 0（不提供额外防护）。默认只填空槽，不顶掉玩家自己的护甲。

### 装备槽守卫（loadout-guard）

- **主武器** 1 把，固定第 1 格；**副武器** 1 把，第 2 格；**近战** 1 把，第 3 格
- **投掷物** 按 `limits.throwable` 限购（默认 0 表示不限），第 4 格放一堆，其余留在背包
- **预留格**：被分类占用的格子只放对应类别，泥土、面包、别人掉的枪会被挪到空闲格，没空位再进背包
- **购买同类不同武器**：旧武器按 `shop.yml` 售价退款，新武器替换进对应槽位
- **购买同一把枪**：默认取消并退款，防止用「买同一把枪」免费补弹
- **拾取同类武器**：身上已有一把时拦截，必须先丢掉现有的
- **分类对照表** `item-categories.yml`：`guns`（按 GunId）、`names`（按显示名）、`materials`（按材质）、`types`（按 TacZ 类型）四张表，外加 `categories:` 段控制每类的开关、落点、限购与替换策略
- **配件独立成类**（`attachment`）：不占快捷栏，买到直接进背包 9-35
- **功能道具**（`utility`）：默认固定第 6、7 格，不再抢前四格
- **分类结果写在物品上**（PDC）：认一次就固定，之后换 TacZ 版本、改 `gun-overrides` 都不会让已发出的武器忽然换类别；认错了用 `/wbdaddon retag` 洗掉重认
- **购买兜底**：背包满时 WBD 会静默吞掉物品、`setItem` 会顶掉目标格，两种情况钱都照扣。本模块在扣钱瞬间抓背包快照，事后核对：东西没到手按比例退款，落点格被顶掉就把旧物品还回去
- **绿宝石保护**：商店开物品不能丢弃、不能换到副手，被拖走或顶掉后自动放回固定槽位
- **连按开商店**：默认 450ms 内连按两下 F；单按 F 仍是原版换手，Shift+F 留给 `/wbda` 菜单
- 权限 `wbdaddon.loadout.bypass` 可绕过（默认不授予）
- 需要 TacZSpigotBridge；缺桥接时本模块自动跳过，不影响其他模块

### 回合事件（round-events）

回合结束清理竞技场掉落物；回合开始时提示「上半场最终局」与「赛点」。

### 回合结算展示（round-summary）

回合结束用标题显示胜方与 MVP，副标题随机挑一条本场趣闻（全歼用时、首杀、命中率、爆头等）。商品价格从 `shop.yml` 读取，用于「这把枪值不值」之类的播报。

### 冻结倒计时音效（freeze-countdown）

回合冻结最后 4 秒每秒一声轻「哒」，音调逐秒升高；冻结不足 4 秒时只取放得下的最后几秒。

### 主菜单（menu）

`/wbda` 或 Shift+F 打开箱子菜单：模式选择（爆破 / 死斗）、自动匹配、当前比赛、比赛总结、个人资料。

`/wbdaddon admin` 是管理员面板：强制开局（选地图立刻开战）、本回合强制胜利 / 失败（以管理员所在阵营为准）、清除某个玩家的生涯数据或某场比赛记录。破坏性操作要求 Shift + 左键。

比赛历史与生涯数据落盘在 `data/stats.db`（SQLite，驱动缺失时回退 `data/stats.yml`）。

### 技术暂停（technical-pause）

`/wbdaddon pause` 管理员即时暂停（可设时长），`/pause` 玩家每队限次（下一回合开始生效）。顶部 BossBar 倒计时，冻结回合与 C4 计时以及玩家操作。断线重联的等待也复用这套暂停机制。

### 比赛投票（vote）

- **BossBar 实时计票**，通过 / 否决 / 超时后立刻收起
- `/vyes` 同意、`/vno` 反对；够票立即通过，票数不可能达标就立即否决
- `/unpause`：技术暂停期间全场投票，T + CT 全员同意才提前解除
- `/vkick <队友>`：同阵营投票踢人，被踢者默认算反对票，其余队友全员同意才生效；踢出后本场不能再加入
- `/vmap <地图>`：全场投票换图，超过 2/3 同意后提前结算并在 3 秒后开新的一场
- `/vsdr`：同阵营投票投降，全队一致同意后判对方阵营获胜
- 提前结算走 WBD 自己的 `endMatch`，不会多算分、不会打乱比分
- 同一房间同时只允许一个投票，同类投票结束有冷却

### 断线重联（reconnect）

比赛进行中（`LIVE` / `FREEZE` / `ROUND_END`）有参赛玩家掉线，当前回合照常打完，在回合结算的间隙把 WBD 的 tick 停住，下一回合永远「还没开始」——直到他回来。

- 为什么不在冻结期暂停：WBD 在 `FREEZE` / `LIVE` 中途加入的玩家只会排队等再下一个回合，冻结点暂停救不回来；`ROUND_END` 加入的正好排队
- 每支队伍每场只有一次这样的待遇；多人掉线共用同一次暂停，名额按阵营算
- 两个时间窗：比赛只等 60 秒（一个技术暂停），但掉线后 5 分钟内回来仍算重连
- 重连后自动放回原阵营，并把掉线前的经济、击杀、死亡、下包、拆包写回新记录
- 顶栏显示 `等待重连 | AlphaHu | 剩余 04:12`

### 少人经济补偿（short-handed-compensation）

从指定回合起，每回合比较双方实际参赛人数，人数少的一方每人获得一笔补偿（人数相同或有一方为空时不发）。只按开局人数结算一次，不按存活人数反复计算。走 WBD 的 `addMoney`，受金钱上限约束。

### 昵称标签（name-tag）

队友看得见名牌、敌人看不见。按观察者自己那块计分板逐人控制：把「不是队友的人」放进一个 `NAME_TAG_VISIBILITY = NEVER` 的队伍，只有该计分板的使用者看不到他们。

只操作非主计分板（WBD 给参赛者单独建板画侧边栏）；队友不碰，继续留在 WBD 的 `wbd_teammates` 里带 `[队友]` 前缀。不要再同时装 WarZHideName，两者都靠计分板队伍，会互相抢人。

### 商店价格标签（shop-price）

WBD 原生商店只显示物品自己的 lore，价格写在 `shop.yml` 里，玩家看不到就没法比较。本模块在商品 lore 下方补一行售价，余额不足时变红并标注。

匹配用的是 WBD 自己给界面物品打的 `shop:<商品id>` 标记，翻页、切换阵营、同名商品都不会认错；认不出标记就不显示价格。

### 观战第一视角（spectate-camera）

阵亡后把观战视角挂到存活队友身上（原版 `setSpectatorTarget`），目标阵亡或离场自动换人。

- 阵亡瞬间先看自己倒下的位置 1.5 秒，再切队友（`death-hold-ticks`）
- 全队阵亡时把视角拉到最后一个队友倒下的位置
- 顶部「战地记录仪」BossBar 显示观战对象与他的武器，进度条是他的血量
- 鼠标左键 / 右键切换上一个 / 下一个队友，也可用 `/spectate [玩家|off]`
- 原版观战视角不渲染目标的持物与手部动作，这是客户端限制

### 队友轮廓发光（teammate-glow）

只对同阵营显示队友的发光轮廓，敌人看不到。原版发光是全局的，要「只有队友可见」必须逐人发包，因此依赖 ProtocolLib；没装时自动跳过。

---

## 安装

1. 把构建好的 `WBDAddon-<对外版本>-<内部版本>-<git短哈希>-<时间戳>.jar` 放进服务端 `plugins`
2. 重启服务器
3. 插件自动生成 `config.yml`，按需修改
4. 执行 `/wbdaddon modules` 确认各模块状态

旧配置升级后建议删除或合并新增的配置项，让新模块的默认段落生效。

## 配置

配置分两层：

- `config.yml`：模块开关与参数
- `item-categories.yml` / `weapon-names.yml`：装备分类表与武器名转换表，首次启动自动生成

最小配置示例：

```yaml
modules:
  kill-reports:
    enabled: true
    replace-default-death-message: true
    show-round-summary: true
  loadout-guard:
    enabled: true
    slots: { primary: 0, secondary: 1, melee: 2, throwable: 3 }
    limits: { primary: 1, secondary: 1, melee: 1, throwable: 0 }
  name-tag:
    enabled: true
    hide-enemies: true
  shop-price:
    enabled: true
  reconnect:
    enabled: true
    hold-max-seconds: 60
    pending-max-seconds: 300
```

全部模块与全部参数见 [配置说明.md](src/main/resources/配置说明.md)。改完执行 `/wbdaddon reload` 生效，无需重启。

## 命令

| 命令 | 权限 | 说明 |
| --- | --- | --- |
| `/wbdaddon reload` | `wbdaddon.admin` | 重载配置并重新启停模块 |
| `/wbdaddon modules` | `wbdaddon.admin` | 查看各模块启用状态 |
| `/wbdaddon modes` | `wbdaddon.admin` | 查看已接入的游戏模式与在线人数 |
| `/wbdaddon admin` | `wbdaddon.admin` | 管理员面板：强制开局、强制结算回合、清除数据 |
| `/wbdaddon sound <音效名> [音调]` | `wbdaddon.admin` | 试听拆弹提示音；不带参数列出推荐清单 |
| `/wbdaddon retag` | `wbdaddon.admin` | 清掉手持物品的分类标签，重新识别 |
| `/wbdaddon shopscan` | `wbdaddon.admin` | 扫描商店商品并输出分类表片段 |
| `/wbdaddon pause` / `resume` | `wbdaddon.admin` | 技术暂停 / 提前恢复 |
| `/wbda` | `wbdaddon.use` | 打开主菜单（Shift+F 同效） |
| `/spectate [玩家\|off]` | `wbdaddon.use` | 观战第一视角切换 |
| `/vyes` `/vno` `/vkick` `/vmap` `/vsdr` `/unpause` | `wbdaddon.use` | 参与各类投票 |

排查「第三方模式接进来了但附加功能没生效」时先看 `/wbdaddon modes`：模式没出现说明注册没成功（多半是加载顺序）；模式在但人数为 0 说明它的 `isParticipating` 判定写错了。

## 对外 API

自 1.2.1 起，WBDAddon 把「一个游戏模式」抽象成 `AddonMode` 接口并发布 `WbdAddonApi` 服务，任何第三方模式都可以注册进来，让上面那些模块自动对新模式的玩家生效。

### 为什么需要这层抽象

改造前，每个模块都用同一句话判断「这个玩家在不在竞技场」：

```java
wbd.getArenaManager().getSession(player) != null
```

这句话把「在竞技场里」和「在 WBD 的爆破模式里」划了等号。一个独立实现的模式（比如团队死斗）玩家不在 WBD 的 `ArenaSession` 里，于是所有模块都看不见他们——饥饿不锁、JourneyMap 上队友敌军分不清，而且不会报任何错。

现在模块改问 `AddonMode`：参赛了吗、还活着吗、属于哪一队。爆破模式本身也变成了这个接口的一个实现（内置注册，永远排在第一位）。

### 用法

```java
public final class MyMode implements AddonMode {

    @Override public String getModeId()          { return "my-mode"; }
    @Override public String getDisplayName()     { return "我的模式"; }
    @Override public boolean isParticipating(Player p) { return match(p) != null; }
    @Override public boolean isPlaying(Player p)       { return match(p) != null; }
    @Override public boolean isAlive(Player p)         { return match(p) != null && match(p).isAlive(p); }
    @Override public String getTeamId(Player p)        { return match(p).teamId(p); }   // "T" / "CT" / null
    @Override public String getArenaId(Player p)       { return match(p).arenaId(); }
    @Override public String getArenaName(Player p)     { return match(p).arenaName(); }

    /** 可选：申请由 WBDAddon 代渲染顶部状态行 */
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
        getLogger().info("未检测到 WBDAddon，附加功能不可用。");
        return;
    }
    rsp.getProvider().registerMode(new MyMode());
}

@Override
public void onDisable() {
    // 一定要注销：否则 WBDAddon 的定时任务会一直踩到已卸载的模式对象
    Bukkit.getServicesManager().getRegistration(WbdAddonApi.class)
            .getProvider().unregisterMode("my-mode");
}
```

在 `plugin.yml` 里写 `softdepend: [WBDAddon]`，就能保证自己的 `onEnable` 一定在服务发布之后执行。

### 注册后自动获得什么

| 能力 | 由哪个模块提供 | 触发条件 |
| --- | --- | --- |
| 饥饿锁定（跑速恒定、不回血） | `arena-food` | `isParticipating` 为 true |
| JourneyMap 队友可见 / 敌军隐藏 | `journeymap-bridge` | `isAlive` 且有 `getTeamId` |
| 顶部状态行代渲染 | `match-status` | 实现了 `hudLine`，且该模块启用 |
| 伤害账本（击杀归属校正） | `kill-reports` | 通过 `api.getCombatStats()` 取用 |
| 枪械名解析 | 内置 | 通过 `api.resolveWeaponName(player)` 取用 |

### 必须注意的两点

1. **`hudLine` 只是申请，不保证有人发。** 代渲染取决于 `match-status` 是否启用、它的顶栏开关有没有被关掉。注册后请调用一次 `api.isHudRelayAvailable()`，返回 `false` 时自己把 HUD 发出去，否则玩家屏幕上什么都没有且不报错。

2. **JourneyMap 桥接受「玩家当前使用哪块计分板」影响。** 原版队伍数据跟着玩家当前所用的计分板下发，所以模式如果给玩家 `setScoreboard(自己的独立计分板)`，就会把 `wbd_*` 队伍数据从该玩家那里屏蔽掉。想同时要自定义侧边栏和 JourneyMap 桥接，就把侧边栏注册在主计分板上（WBDTeamDeathmatch 就是这么做的）。

### 队伍名与多模式共存

`journeymap-bridge` 生成的原版队伍名形如 `<前缀><模式id>_<阵营id>`（例：`wbd_bomb_def_T`）。带上模式 id 是必要的：爆破与死斗都有 T/CT，若只按阵营拼名，两个模式的玩家会挤进同一个原版队伍，在 JourneyMap 上互相看得见。名字会被裁剪到原版 16 字符上限以内。

## 依赖

| 依赖 | 类型 | 说明 |
| --- | --- | --- |
| Java 17 | 必需 | |
| Paper / Spigot / Arclight 1.20.1 | 必需 | 实测 Arclight 1.20.1 |
| WarZBombDefuse | 必需 | 本插件的服务对象 |
| TacZSpigotBridge | 可选 | 枪械名显示、分类、更准确的击杀归属 |
| ProtocolLib | 可选 | 仅「队友轮廓发光」需要 |
| JourneyMap | 可选 | 仅「JourneyMap 桥接」的客户端侧需要 |

## 构建

构建前必须自己把 `wbd.jar` 放进 `libs/` 目录。WBDAddon 依赖闭源收费的 WarZBombDefuse API（`pom.xml` 里是 system-scope，直接指向 `libs/wbd.jar`），这个 jar 不随本项目分发，需要从作者处获取（[Minebbs](https://www.minebbs.com/resources/folia-warzbombdefuse-cs-t-ct-tacz.17007/)）。

1. 把 `wbd.jar` 放进 `libs/`
2. 可选：放进 `taczspigotbridge.jar`，获得枪械名显示与更准确的击杀归属
3. 可选：放进 `ProtocolLib-5.4.0.jar`，编译「队友轮廓发光」
4. 构建：

```bash
./build.sh        # javac --release 17，不依赖 mvn
# 或
mvn clean package
```

产物命名：

```
WBDAddon-<对外版本>-<内部版本>-<git短哈希>-<时间戳>.jar
例：WBDAddon-1.2.2-1.31.1-2c890b4-20261028-1427.jar
```

两个版本号分工：

| 版本 | 来源 | 用途 |
| --- | --- | --- |
| 对外版本 | `public-version.txt` | 玩家看到的：`plugin.yml` 的 `version`、`/plugins` 列表、发布帖 |
| 内部版本 | `pom.xml` 的 `<version>` | 每次更新自增，只体现在产物文件名与 `git.properties` 里 |

产物名里的短哈希与时间戳用于区分同版本的多次构建；拿不到 git 信息时显示 `nogit`，构建不会失败。

## 致谢

感谢 [Crazy_Jky](https://www.minebbs.com/members/crazy-jky.88908/) 开发的 [WarZBombDefuse](https://www.minebbs.com/resources/folia-warzbombdefuse-cs-t-ct-tacz.17007/) 插件与 API 支持。

相关项目：[WBDTeamDeathmatch](https://github.com/DICEALPHAHU/WBDTeamDeathMatch)，基于本插件 `WbdAddonApi` 接入的团队死斗模式。

## 许可证

GPL-3.0，详见仓库 LICENSE 文件。互联网共享精神，不准拿本项目代码搞闭源。

## 反馈

- 反馈 QQ：2387629002
- 欢迎提 Issue 与 Pull Request
