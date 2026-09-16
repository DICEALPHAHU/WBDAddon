# WBDAddon

WarZBombDefuse 的第三方附加组件合集 —— 击杀报告 / 单人伤害统计 / 比赛状态显示 / 竞技场饥饿锁定 / JourneyMap 队伍桥接。

每个功能都是独立模块，通过 `config.yml` 单独开关，互不影响。无需改动 WBD 本体，纯附加。

> 已在 **Arclight 1.20.1**（Forge + Bukkit 混合端）环境实测运行。

## ✨ 功能

| 模块 | id | 说明 |
| --- | --- | --- |
| 击杀报告 | `kill-reports` | 替换默认淘汰消息、显示击杀者与武器；回合结束给每个玩家单独发送自己的伤害统计 |
| 比赛状态 | `match-status` | 顶部 ActionBar 显示 T/CT 存活人数；炸弹安放后 BossBar 显示倒计时进度条 |
| 竞技场饥饿锁定 | `arena-food` | 竞技场玩家饥饿恒定到安全值：跑速恒定、不自然回血 |
| JourneyMap 桥接 | `journeymap-bridge` | 同步 WBD 队伍数据到原版 Team，配合 JourneyMap Teams 队友可见、敌军隐藏、观战消失 |

### 击杀报告（kill-reports）
- 淘汰提示显示击杀者与武器（TacZ 枪械按 GunId 人性化改名：`tacz:p320 → P320`）
- **伤害统计单独发给每个玩家**：「你对 XX 造成了 X 伤害」，不全局刷屏
- 修正 TacZ 致死伤害在 Forge 层漏记的问题，**血包回血场景下也能正确累计真实总伤害**

### 比赛状态（match-status）
- 屏幕顶部实时显示双方存活人数：`T 存活 3/5   CT 存活 4/5`
- 炸弹安放后头顶 BossBar 显示倒计时进度条，爆 / 拆后消失，颜色与样式可配置

### 竞技场饥饿锁定（arena-food）
- 跑步消耗饥饿、饿到跑不动，或满饥饿自然回血，都会不公平
- 把竞技场玩家饥饿恒定到 17（跑速恒定、不自然回血），只靠血包 / 治疗，公平竞技
- 只作用于竞技场玩家，不影响大厅与全局

### JourneyMap 桥接（journeymap-bridge）
- 把 WBD 队伍数据写入原版 Scoreboard Team，配合客户端 JourneyMap Teams 实现队友可见、敌军隐藏、观战消失

## 🙏 致谢

感谢 [Crazy_Jky](https://www.minebbs.com/members/crazy-jky.88908/) 开发的 [WarZBombDefuse](https://www.minebbs.com/resources/folia-warzbombdefuse-cs-t-ct-tacz.17007/) 插件，并提供的 API 支持。

## 📦 依赖

- **Java 17**
- **Paper / Spigot / Arclight 1.20.1**
- **WarZBombDefuse**（必装）
- **TacZSpigotBridge**（可选，提供枪械名与更准确的击杀归属）
- **JourneyMap**（仅桥接模块需要）

## 🔧 安装

1. 把 `WBDAddon-1.0.0.jar` 放进服务端 `plugins`
2. 重启服务器（或 `/reload`）
3. 插件自动生成 `config.yml`，按需修改

> 旧配置升级后建议删除或合并新配置项，让新增模块段落生效。

## ⚙️ 配置

```yaml
modules:
  kill-reports:
    enabled: true
    show-round-summary: true        # 是否发送每人伤害统计
  match-status:
    enabled: true
    enable-alive-action-bar: true   # 存活人数顶部显示
    enable-bomb-bar: true           # 炸弹倒计时条
  arena-food:
    enabled: true
    food-level: 17                  # 恒定饥饿值（跑速恒定 + 不回血）
  journeymap-bridge:
    enabled: true
```

执行 `/wbdaddon reload` 即可生效，无需重启。

## ⌨️ 命令

- `/wbdaddon reload` —— 重载配置并重新启停模块
- `/wbdaddon modules` —— 查看各模块启用状态

## 🔨 构建

> ⚠️ 构建**前必须先自己把 `wbd.jar` 放进 `libs/` 目录**。

WBDAddon 依赖的是**闭源收费**的 WarZBombDefuse API（`pom.xml` 里是 system-scope，直接指向 `libs/wbd.jar`）。这个 jar **不随本项目代码分发**,你需要自行获取：

1. 从 WarZBombDefuse 作者处取得 `wbd.jar`（闭源,需购买授权）,放入 `libs/`
2.（可选）放入 `taczspigotbridge.jar`,以获得枪械名显示与更准确的击杀归属
3. 然后再构建:

```bash
mvn clean package
```

产物在 `target/WBDAddon-1.0.0.jar`。

## 📜 许可证

开源。详见仓库 LICENSE 文件。

## 💬 反馈

- 反馈 QQ：2387629002
- 欢迎提 Issue 与 Pull Request
