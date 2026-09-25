# 依赖变更告知：防第三人称现在需要 ProtocolLib

> 适用于 **1.0.1 及以后**。如果你不用「防第三人称」这个模块，可以忽略本文件。

## 一句话

**`antithirdcam`（防第三人称）模块新增一个依赖：[ProtocolLib](https://hangar.papermc.io/dmulloy2/ProtocolLib/versions)。**

没装也能正常启动——该模块会**自动禁用**，其余模块不受影响。

## 为什么需要它

防第三人称的原理是给参赛玩家挂一块「**只对本人可见**」的巨大遮挡面：切到第三人称时视野被糊住，隔着掩体偷看就失去意义。

问题就出在「只对本人可见」——这件事只有两条路：

| 方案 | 可靠性 |
| --- | --- |
| **ProtocolLib** 在数据包层拦截 | ✅ 可靠。客户端的实体包直接被拦下，对方根本收不到这个实体 |
| Bukkit 的 `Player#hideEntity` | ❌ 见下 |

实测数据（用 `/wbdaddon diag` 在 Arclight 1.20.1 上得到）：

```
服务端：Arclight arclight-1.20.1-1.0.6-SNAPSHOT (MC: 1.20.1)
Player#hideEntity(Plugin,Entity)     -> 存在      ← 方法存在、调用不报错，但完全不生效
Entity#setVisibleByDefault(boolean)  -> 存在      ← 同样不生效
Display#setViewRange(float)          -> 存在
Player#teleportAsync(Location)       -> 不存在
```

**Arclight 对 Paper 扩展 API 的兼容是残缺的**：这几个方法「有签名、无行为」，调用它们既不会抛异常、也不会有任何效果。结果就是遮挡面对**所有人**可见，玩起来像「黑块挂在对手身上、还跟着他跑」。

所以在 Arclight 这类服务端上，**只能走协议层**。

## 不装会怎样

从 1.0.1 起，插件会主动检测并在缺失时**直接禁用该模块**，避免出现「功能没生效、黑块却挂在所有人身上」这种最糟的组合。

启动日志里会看到：

```
[WBDAddon] 未检测到 ProtocolLib，防第三人称已自动禁用。缺少它时遮挡面无法做到
只对本人可见，会在 Arclight 这类服务端上对所有人显示（「黑块挂在别人身上」）。
装上 ProtocolLib 后执行 /wbdaddon reload 即可恢复；若你在 Paper 系服务端上且确认
Player#hideEntity 确实有效，可把 modules.antithirdcam.require-protocol-lib 设为 false。
```

**不会报错、不会崩、也不会有黑块。** 击杀报告、比赛状态、拆弹声音、饥饿锁定、JourneyMap 桥接照常工作。

## 怎么装

1. 下载 ProtocolLib（MC 1.20.1 请用 **5.4.0**）
   - [Hangar](https://hangar.papermc.io/dmulloy2/ProtocolLib/versions)（推荐）
   - [GitHub Releases](https://github.com/dmulloy2/ProtocolLib/releases)
2. 把 jar 放进服务端 `plugins/`
3. 重启服务器，或执行 `/wbdaddon reload`
4. 确认日志出现这一行即成功：

```
遮挡面已改用 ProtocolLib 在数据包层隐藏，只对本人可见。
```

## 如果你用的是 Paper / Folia

Paper 系服务端上 `hideEntity` 是**真实有效**的，因此可以不装 ProtocolLib，只改配置：

```yaml
modules:
  antithirdcam:
    require-protocol-lib: false
```

> ⚠️ **Arclight / Spigot 上不要这样设。** 那边的 `hideEntity` 是空壳，关掉这个保护会让遮挡面对所有人可见。
> 不确定的话先跑一次 `/wbdaddon diag`，看 `Player#hideEntity` 是否真的可用。

## 排查

| 现象 | 检查 |
| --- | --- |
| 没看到「已改用 ProtocolLib」也没看到「已自动禁用」 | 确认 `modules.antithirdcam.enabled: true` |
| 模块被自动禁用 | 装 ProtocolLib 后 `/wbdaddon reload` |
| 装了 ProtocolLib 但仍被禁用 | 用 `/wbdaddon diag` 看末行是否显示「已安装」 |
| 仍有黑块挂在别人身上 | 用 `/wbdaddon diag` 确认 ProtocolLib 状态，并把结果反馈给作者 |
