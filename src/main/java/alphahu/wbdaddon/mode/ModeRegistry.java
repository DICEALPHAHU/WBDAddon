package alphahu.wbdaddon.mode;

import alphahu.wbdaddon.WBDAddon;
import alphahu.wbdaddon.api.AddonMode;
import alphahu.wbdaddon.api.CombatStats;
import alphahu.wbdaddon.api.WbdAddonApi;
import alphahu.wbdaddon.module.AddonModule;
import alphahu.wbdaddon.module.KillReportsModule;
import alphahu.wbdaddon.module.MatchStatusModule;
import alphahu.wbdaddon.util.WeaponNames;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 模式注册表，{@link WbdAddonApi} 的唯一实现。
 *
 * <p>它是 WBDAddon 从「只认爆破模式的附加组件」走向「模式无关的附加组件平台」
 * 的那一步：内置的爆破模式与第三方模式在这里平起平坐。
 *
 * <p><b>防御性设计</b>：所有对第三方 {@link AddonMode} 的调用都包在 try/catch 里。
 * 这些实现来自别的插件，可能是任意质量的代码；一次 {@code NullPointerException}
 * 或 {@code NoClassDefFoundError} 不该让饥饿锁定的定时任务整个死掉——
 * 那会表现为「装了新模式之后，老的爆破模式也一起失灵了」，极难排查。
 * 踩到异常时只记一次日志并把该玩家当作不属于这个模式，继续往下走。
 *
 * @author AlphaHu
 */
public final class ModeRegistry implements WbdAddonApi {

    /** API 版本。新增向后兼容的能力时递增次版本号，破坏性改动递增主版本号。 */
    private static final String API_VERSION = "1.2";

    private final WBDAddon plugin;

    /**
     * 已注册的模式，按注册顺序排列。
     * 用 CopyOnWriteArrayList：读取（每个定时任务、每 tick、每个玩家）远多于写入（模式加载/卸载）。
     */
    private final List<AddonMode> modes = new CopyOnWriteArrayList<>();

    /** 已经因为抛异常被记过日志的模式 id，避免刷屏。 */
    private final List<String> warnedModes = new CopyOnWriteArrayList<>();

    public ModeRegistry(WBDAddon plugin) {
        this.plugin = plugin;
    }

    /**
     * 注册 WBDAddon 自带的爆破模式。
     * 由主类在 onEnable 时最先调用，保证它在归属判定中始终排在最前——
     * 否则一个「把所有玩家都认成自己人」的第三方模式会把爆破模式的玩家抢走。
     */
    public void registerBuiltIn() {
        registerMode(new BombDefuseMode(plugin.getWbd()));
    }

    // ------------------------------------------------------------------
    // 注册与查询
    // ------------------------------------------------------------------

    @Override
    public void registerMode(AddonMode mode) {
        if (mode == null) {
            return;
        }

        String id = safeModeId(mode);
        if (id == null || id.isBlank()) {
            plugin.getLogger().warning("收到一个 getModeId() 为空的模式实现（"
                    + mode.getClass().getName() + "），已忽略。");
            return;
        }

        // 同 id 覆盖：模式插件重载时不必先注销，避免出现「忘了注销导致注册表里堆两份」的情况
        modes.removeIf(existing -> id.equals(safeModeId(existing)));
        modes.add(mode);
        plugin.getLogger().info("已注册游戏模式：" + id + "（" + safeDisplayName(mode) + "）");
    }

    @Override
    public boolean unregisterMode(String modeId) {
        if (modeId == null) {
            return false;
        }
        boolean removed = modes.removeIf(mode -> modeId.equals(safeModeId(mode)));
        if (removed) {
            plugin.getLogger().info("已注销游戏模式：" + modeId);
        }
        return removed;
    }

    @Override
    public List<AddonMode> getModes() {
        return Collections.unmodifiableList(modes);
    }

    @Override
    public AddonMode getMode(String modeId) {
        if (modeId == null) {
            return null;
        }
        for (AddonMode mode : modes) {
            if (modeId.equals(safeModeId(mode))) {
                return mode;
            }
        }
        return null;
    }

    @Override
    public AddonMode getModeFor(Player player) {
        if (player == null) {
            return null;
        }
        for (AddonMode mode : modes) {
            if (query(() -> mode.isParticipating(player), mode, player)) {
                return mode;
            }
        }
        return null;
    }

    @Override
    public boolean isInAnyMode(Player player) {
        return getModeFor(player) != null;
    }

    // ------------------------------------------------------------------
    // 复用能力
    // ------------------------------------------------------------------

    @Override
    public String resolveWeaponName(Player killer) {
        return WeaponNames.resolve(killer, plugin.getTacZApi(), plugin.isTacZAvailable());
    }

    @Override
    public CombatStats getCombatStats() {
        if (plugin.getModuleManager() == null) {
            return null;
        }
        AddonModule module = plugin.getModuleManager().getModule("kill-reports");
        if (module instanceof KillReportsModule killReports) {
            // 模块被禁用时这个 getter 返回 null，调用方按「无伤害记录能力」处理
            return killReports.getDamageTracker();
        }
        return null;
    }

    /**
     * HUD 代渲染是否真的可用。
     *
     * <p>要同时满足三件事：match-status 模块注册了、它处于启用状态、
     * 并且存活人数顶栏这个开关没有被服主关掉。少任何一条，
     * {@link AddonMode#hudLine} 都不会有人来取，模式插件必须自己发。
     */
    @Override
    public boolean isHudRelayAvailable() {
        if (plugin.getModuleManager() == null) {
            return false;
        }
        AddonModule module = plugin.getModuleManager().getModule("match-status");
        if (!(module instanceof MatchStatusModule) || !plugin.getModuleManager().isEnabled(module)) {
            return false;
        }
        return plugin.getConfig()
                .getBoolean("modules.match-status.enable-alive-action-bar", true);
    }

    @Override
    public String getApiVersion() {
        return API_VERSION;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * 跑一次可能抛异常的查询。
     *
     * <p>第三方模式实现里的异常不该中断调用方（通常是遍历全服玩家的定时任务），
     * 所以这里吞掉异常并返回兜底值，只对每个模式记一次日志。
     */
    private boolean query(java.util.function.BooleanSupplier action, AddonMode mode, Player player) {
        try {
            return action.getAsBoolean();
        } catch (Throwable t) {
            String id = safeModeId(mode);
            if (id != null && !warnedModes.contains(id)) {
                warnedModes.add(id);
                plugin.getLogger().warning("查询模式 " + id + " 的参赛状态时抛出异常，"
                        + "已把相关玩家视为不属于该模式（此模式的后续异常不再重复报告）：" + t);
            }
            return false;
        }
    }

    /** 取模式 id，连取 id 本身抛异常也兜住。 */
    private String safeModeId(AddonMode mode) {
        try {
            return mode.getModeId();
        } catch (Throwable t) {
            return null;
        }
    }

    private String safeDisplayName(AddonMode mode) {
        try {
            String name = mode.getDisplayName();
            return name == null ? mode.getClass().getSimpleName() : name;
        } catch (Throwable t) {
            return mode.getClass().getSimpleName();
        }
    }
}
