# 装备槽守卫（loadout-guard）

> WBDAddon `1.5.0` 新增模块。适用于 WarZBombDefuse 爆破模式，不改 WBD 本体、不改 `shop.yml`。

## 1. 规则

| 类别 | 槽位 | 上限 | 说明 |
| --- | --- | --- | --- |
| 主武器 `primary` | 快捷栏第 1 格（index 0） | 1 把 | 步枪 / 冲锋枪 / 霰弹枪 / 轻机枪 / 精确射手 / 狙击枪 |
| 副武器 `secondary` | 第 2 格（index 1） | 1 把 | 手枪 |
| 近战 `melee` | 第 3 格（index 2） | 1 把 | 8 把 Apex 刀 + 以后加的 LRTactical 近战 |
| 投掷物 `throwable` | 第 4 格（index 3） | 4 颗 | 第 4 格放一堆，其余在背包 |
| 功能 `utility` | 不固定 | 不限 | 配件、拆弹器、医疗包、C4 |

### 购买

- 买**同类不同武器**：旧武器删除，按 `shop.yml` 的售价退还旧武器的钱，新武器放进对应槽位。
- 买**同类同一把枪**：默认 `block`，取消本次购买并退还本次购买的钱，防止免费补弹。
  - `replace-no-refund`：用新枪替换旧枪，不退旧枪的钱（付费换新）。
  - `replace-refund`：用新枪替换旧枪并退旧枪的钱（可能被用来免费补弹，不推荐）。
- 买投掷物：总数超过 4 颗时，超出的部分移除并退款。
- 背包满、WBD 发了但物品没进背包：退还本次购买的钱，保留旧武器。

### 拾取

- 主/副/近战：身上已有同类时**直接拦截**，必须先丢掉现有那把，才能捡新的。
- 投掷物：`当前总数 + 拾取数量 > 4` 时拦截。
- 捡起来后下一 tick 自动归位到对应槽位。

### 归位 / 锁定

- 购买、拾取、回合开始、重生、背包关闭、手动移动后都会归位。
- 另有 `lock-interval-ticks`（默认 10 tick）的兜底任务，强制把主/副/近战/投掷物放回固定槽位。
- 发现同类多把（`/give`、旧数据、插件冲突）时，默认把多出来的丢到玩家脚下。

## 2. 分类来源

### TACZ 枪械

1. 通过 `TacZSpigotBridge.getItemGunId()` 取 `GunId`；
2. 先查 `gun-overrides`；
3. 再查 `TacZSpigotBridge.getGun(id).type()`，用 `tacz-type-categories` 映射；
4. 没映射的类型按 `primary` 处理，并在控制台提示一次。

线上 8 把 Apex 刀在 TACZ index 里是 `type: sniper`，所以必须在 `gun-overrides` 里覆盖成 `melee`：

```yaml
gun-overrides:
  "twx:twx_kw": melee
  "twx:twx_nbejd": melee
  "twx:twx_hdd": melee
  "twx:twx_sc": melee
  "twx:twx_nlj": melee
  "twx:twx_mqzz": melee
  "twx:twx_xsd": melee
  "twx:twx_mk": melee
```

### LRTactical

按 Bukkit Material 分类：

- `LRTACTICAL_THROWABLE` → 投掷物
- `LRTACTICAL_MELEE` → 近战
- `LRTACTICAL_CONSUMABLE` → 功能

### 功能道具

- `TACZ_ATTACHMENT`、`TACZ_AMMO`、`SHEARS`、`POTION` → 功能
- WBD 的 C4（PDC marker `c4`）和拆弹器（`defuse_kit`）→ 功能，不参与投掷物上限

## 3. 配置

完整配置见 `src/main/resources/config.yml` 的 `modules.loadout-guard`。关键项：

```yaml
modules:
  loadout-guard:
    enabled: true
    lock-interval-ticks: 10
    slots:
      primary: 0
      secondary: 1
      melee: 2
      throwable: 3
    limits:
      primary: 1
      secondary: 1
      melee: 1
      throwable: 4
    same-gun-policy: block
    refund-old-weapon: true
    drop-duplicates: true
    tacz-type-categories:
      pistol: secondary
      rifle: primary
      smg: primary
      shotgun: primary
      sniper: primary
      mg: primary
      rpg: primary
      melee: melee
    gun-overrides:
      "twx:twx_kw": melee
      # ... 另外 7 把
    throwable-materials:
      - LRTACTICAL_THROWABLE
    melee-materials:
      - LRTACTICAL_MELEE
    utility-materials:
      - LRTACTICAL_CONSUMABLE
      - TACZ_ATTACHMENT
      - TACZ_AMMO
      - SHEARS
      - POTION
    tacz-gun-material: TACZ_MODERN_KINETIC_GUN
```

### 槽位冲突

- WBD 的 `c4-slot` 默认是 **4（第 5 格）**，本模块投掷物是 **3（第 4 格）**，不冲突。
- WBD 商店开启物品默认 `slot: 8`（第 9 格），不冲突。
- 启动时如果检测到冲突，会在控制台输出警告。
- 如果某个枪械商品在 `shop.yml` 里写了 `give-slot: 0/1/2/3`，WBD 会直接覆盖旧武器，替换退款可能失效；模块会警告，建议保持 `give-slot: -1`。

## 4. 实现说明

### 为什么购买是“事后同 tick 处理”

WBD 的 `GameListener.onInventoryClick` 在 `LOWEST` 阶段：

1. 无条件 `event.setCancelled(true)`；
2. 调用 `ShopManager.handleClick()` 扣钱、发物品、记录购买次数。

它**不检查事件是否已被取消**，第三方插件无法在扣钱前拦截。因此本模块：

1. 在 `MONITOR` 阶段读取该商品的购买计数是否 +1；
2. 如果 +1，说明 WBD 已经扣钱/发物品；
3. 在同一个 tick 内删除旧武器、退款、把新武器归位。

这样做不会产生“玩家先拿到两把枪”的窗口，因为处理在同一个 tick 完成。

### 为什么价格不写死

`ShopPriceCache` 读取 `plugins/WarZBombDefuse/shop.yml` 的 `price` 和 `give-data`，按 `GunId` 建价格索引。打开商店时会检查文件 `lastModified`，改动后自动刷新。

退款时，TACZ 枪**只认 GunId 对应的精确价格**，不会回退到材质价格。原因是所有 TACZ 枪共用 `TACZ_MODERN_KINETIC_GUN` 材质；如果管理员 `/give` 了一把商店没卖过的枪，按材质回退会把别的枪价格套上来。查不到 GunId 价格就退 0。

### 为什么需要 TacZSpigotBridge

TACZ 的 `GunId` 和 index `type` 都在 Forge NBT / 资源索引里，Bukkit 侧无法可靠读取。没有桥接时：

- TACZ 枪械不会被分类/限购（模块会在控制台警告）；
- 其他材质（LRTactical、功能道具）仍正常工作。

## 5. 已知限制

1. **只作用于 WarZBombDefuse 爆破模式**：通过 `ArenaSession` 判定，不作用于 WBDTeamDeathmatch 等第三方模式。
2. **同一把枪重复购买**：默认 `block`，不会免费补弹；如果改成 `replace-refund`，玩家可以用相同价格反复换新枪补满弹。
3. **WBD 购买计数不会回退**：被拦截/退款的购买仍然计入 WBD 的 `limit-per-round`。线上枪械限购大多是 5 或 0，影响很小；G17（限 1）在“买了又换掉”后可能本轮不能再买。
4. **背包满**：WBD 的 `addItem` 返回值被忽略，物品可能没进背包；模块能检测到“找不到新物品”并退款，但无法凭空造出空间。
5. **直接改 `give-slot` 的商品**：如果枪械商品写了固定 `give-slot`，WBD 会覆盖旧武器，模块可能拿不到旧武器价格；建议保持 `-1`。
6. **没有 TacZSpigotBridge**：本模块的类直接引用了桥接 API。缺桥接时 Bukkit 可能拒绝注册本模块的事件，导致整个模块不生效（控制台会出现 `Failed to register events ... TacZSpigotApi does not exist`）。爆破服已安装桥接；如果要在没有桥接的环境用，需要先补桥接或移除本模块。

## 6. 测试清单

建议在测试服按顺序验证：

1. 空手买主武器 → 出现在第 1 格。
2. 已有主武器买另一把主武器 → 旧枪消失、新枪进第 1 格、钱增加旧枪售价。
3. 已有主武器买同一把主武器 → 购买取消、钱退回、旧枪保留。
4. 买手枪 → 第 2 格；已有手枪买另一把 → 替换/退款。
5. 买近战刀 → 第 3 格；已有刀买另一把 → 替换（旧刀价格 0，不退钱）。
6. 连续买 4 颗投掷物 → 第 4 格一堆 + 背包若干；买第 5 颗 → 拒绝并退款。
7. 已有主武器走到地上的主武器上 → 拾取被拦截；先丢掉主武器再拾取 → 成功并归位。
8. 已有 4 颗投掷物走到投掷物上 → 拾取被拦截。
9. 手动把主武器拖到第 5 格 → 下一次检查被拖回第 1 格。
10. 回合开始保留幸存者背包 → 多把同类只保留固定槽那把，其余丢到脚下。
11. OP 给 `wbdaddon.loadout.bypass` → 不再限购/归位。
12. 打开商店买配件/医疗包/拆弹器 → 不占四类槽位。

## 7. 构建与回滚

```bash
# 在 WBDAddon 项目根目录
mvn clean package

# 产物
target/WBDAddon-1.5.0-<git短哈希>-<时间戳>.jar
```

安装：替换 `plugins/WBDAddon-*.jar`，重启或 `/reload`。

回滚：

1. 删除/替换回旧 `WBDAddon` jar；
2. 或在 `config.yml` 里设 `modules.loadout-guard.enabled: false`，执行 `/wbdaddon reload`；
3. 本模块不改 `shop.yml`，不需要恢复商店配置。
