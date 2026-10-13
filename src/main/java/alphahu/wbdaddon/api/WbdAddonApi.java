package alphahu.wbdaddon.api;

import org.bukkit.entity.Player;

import java.util.List;

/**
 * WBDAddon 对外暴露的服务接口。
 *
 * <p>通过 Bukkit 的 ServicesManager 取得：
 * <pre>{@code
 * RegisteredServiceProvider<WbdAddonApi> rsp =
 *         Bukkit.getServicesManager().getRegistration(WbdAddonApi.class);
 * if (rsp != null) {
 *     WbdAddonApi api = rsp.getProvider();
 *     api.registerMode(myMode);
 * }
 * }</pre>
 *
 * <p>拿到它的典型用法是：一个独立的第三方模式（例如团队死斗）实现 {@link AddonMode}
 * 并注册进来，从而让 WBDAddon 的击杀报告、饥饿锁定、JourneyMap 队伍桥接
 * 等功能自动对自己的玩家生效——不需要 WBDAddon 反过来认识这个模式。
 *
 * <p><b>注册时机</b>：WBDAddon 的 onEnable 在自己的 onEnable 里发布服务。
 * 若第三方插件在 plugin.yml 里声明了 {@code softdepend: [WBDAddon]}，
 * 它的 onEnable 一定在服务发布之后执行，直接取即可；否则请延迟到下一 tick 再取。
 * 注销请在模式插件自己的 onDisable 里调用 {@link #unregisterMode(String)}，
 * 避免服务里留下已经卸载的模式的引用（那会让 WBDAddon 的定时任务反复踩到死对象）。
 *
 * @author AlphaHu
 */
public interface WbdAddonApi {

    /** API 版本号，形如 {@code "1.2"}。第三方插件可据此判断是否存在自己要用的能力。 */
    String getApiVersion();

    /**
     * 注册一个模式。
     *
     * <p>同一个 {@link AddonMode#getModeId() modeId} 重复注册会覆盖旧的实现，
     * 这样模式插件重载时不必先注销。玩家归属的判定按注册顺序先到先得，
     * 因此内置的爆破模式始终排在前面。
     *
     * @param mode 模式实现，null 会被忽略
     */
    void registerMode(AddonMode mode);

    /**
     * 注销一个模式。
     *
     * @param modeId 模式标识
     * @return 确实移除掉了返回 true；本来就不存在返回 false
     */
    boolean unregisterMode(String modeId);

    /** 当前已注册的全部模式（不可修改视图），按注册顺序排列。 */
    List<AddonMode> getModes();

    /** 按 id 取模式，不存在返回 null。 */
    AddonMode getMode(String modeId);

    /**
     * 找出该玩家所属的模式。
     *
     * <p>按注册顺序返回第一个 {@link AddonMode#isParticipating(Player)} 为 true 的模式；
     * 玩家不属于任何模式时返回 null。
     */
    AddonMode getModeFor(Player player);

    /** 玩家是否属于任一已注册的模式。等价于 {@code getModeFor(player) != null}。 */
    boolean isInAnyMode(Player player);

    /**
     * 解析玩家手中武器的人类可读名称。
     *
     * <p>TacZSpigotBridge 可用时优先取枪械 GunId 并人性化显示（{@code tacz:p320 → P320}），
     * 否则依次回退到物品自定义名、lore 首行、材质名。
     *
     * <p>击杀播报需要它，因此它不依赖 kill-reports 模块是否启用。
     * 这也正是第三方模式值得复用的部分：自己实现一遍枪械改名很容易，但要把
     * TacZ 桥接、自定义显示名、颜色码剥离这些边界都处理对，就没那么轻松了。
     */
    String resolveWeaponName(Player killer);

    /**
     * 取伤害记录服务，用于击杀归属校正与自建伤害统计。
     *
     * @return kill-reports 模块启用时返回可用实例；模块被禁用时返回 null，
     *         调用方必须做好空值处理
     */
    CombatStats getCombatStats();

    /**
     * WBDAddon 此刻是否真的会代第三方模式渲染顶部状态行。
     *
     * <p>实现 {@link AddonMode#hudLine(Player)} 只是「申请」由 WBDAddon 渲染，
     * 但这件事最终取决于 match-status 模块是否启用、以及它的存活人数顶栏开关
     * 有没有被服主关掉。只按「我实现了 hudLine」就假定 HUD 一定有人发，
     * 结果会是玩家屏幕上什么都没有——而且不报任何错。
     *
     * <p>所以模式插件应当在注册之后问一次这个方法，返回 false 时
     * 自己把 HUD 发出去。
     */
    boolean isHudRelayAvailable();
}
