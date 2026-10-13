package alphahu.wbdaddon.api;

import org.bukkit.entity.Player;

/**
 * 一个「游戏模式」向 WBDAddon 暴露的参赛状态。
 *
 * <p>WBDAddon 原本的每个模块都用
 * {@code wbd.getArenaManager().getSession(player) != null} 来判断「这个玩家在竞技场里」，
 * 于是所有模块都隐式地只认 WarZBombDefuse 的爆破模式。第三方模式（例如团队死斗）
 * 的玩家不在 WBD 的 ArenaSession 里，模块就完全看不到他们。
 *
 * <p>这个接口就是那层缺失的抽象：模式实现它、注册进 {@link WbdAddonApi}，
 * 附加组件便只依赖「参赛 / 存活 / 阵营」这三个语义，而不再关心背后是爆破还是死斗。
 *
 * <p><b>实现约定</b>：所有方法都可能被定时任务高频调用（最快的模块每 2 tick 遍历一次全服玩家），
 * 实现必须是只读、廉价、线程安全的；禁止在其中做传送、发包、改动玩家状态之类的副作用操作。
 *
 * @author AlphaHu
 */
public interface AddonMode {

    /**
     * 模式唯一标识，用于注册与注销，例如 {@code "bomb-defuse"}、{@code "team-deathmatch"}。
     * 同时被 {@link WbdAddonApi#unregisterMode(String)} 与诊断输出使用，必须稳定。
     */
    String getModeId();

    /** 模式显示名，用于日志与 {@code /wbdaddon modes}。 */
    String getDisplayName();

    /**
     * 玩家是否「在这个模式里」——包含仍在比赛的参赛者，以及本回合已阵亡、
     * 正在旁观等待复活的玩家。
     *
     * <p>饥饿锁定、JourneyMap 桥接这类需要一个明确「在模式内 / 不在模式内」边界的模块用它。
     */
    boolean isParticipating(Player player);

    /**
     * 玩家是否是该模式的正式参赛者（分到了某一方阵营），不含纯旁观者。
     *
     * <p>注意它与 {@link #isAlive(Player)} 是两件事：在爆破模式里，已阵亡的 T/CT
     * 仍然是参赛者，只是不存活了。
     */
    boolean isPlaying(Player player);

    /**
     * 玩家此刻是否存活、可以正常参与战斗。
     *
     * <p>需要区分「活着能打」与「已阵亡旁观」的功能依赖它。
     */
    boolean isAlive(Player player);

    /**
     * 玩家所属阵营的**稳定标识**，例如 {@code "T"}、{@code "CT"}、{@code "RED"}。
     *
     * <p>调用方会直接把它拼进原版队伍名（JourneyMap 桥接），因此必须是
     * 只含字母数字下划线的短标识；不在任何阵营时返回 {@code null}。
     */
    String getTeamId(Player player);

    /** 玩家所在场地的 id，不在任何场地时返回 {@code null}。 */
    String getArenaId(Player player);

    /** 玩家所在场地的显示名，不在任何场地时返回 {@code null}。 */
    String getArenaName(Player player);

    /**
     * 两名玩家是否属于同一阵营。
     *
     * <p>默认实现比较 {@link #getTeamId(Player)}；如果模式有更精确的判定
     * （例如旁观者一律不算同队）可以覆盖。
     */
    default boolean isSameTeam(Player first, Player second) {
        String a = getTeamId(first);
        String b = getTeamId(second);
        return a != null && a.equals(b);
    }

    /**
     * 该模式自定义的屏幕顶部状态行（ActionBar）。
     *
     * <p>返回非 null 时，WBDAddon 的 match-status 模块会用它刷新该玩家的 ActionBar，
     * 并沿用自己配置好的刷新间隔；返回 null 则回退到内置的爆破存活人数显示。
     *
     * <p>为什么值得把渲染交给附加组件代劳：在 Arclight 这类服务端上，
     * Paper 的 {@code Player#sendActionBar} 并不存在，唯一稳妥的写法是
     * {@code player.spigot().sendMessage(ChatMessageType.ACTION_BAR, ...)}。
     * 这件事每个模式各写一遍，很容易写出「在开发机上好好的、上服就静默失效」的代码。
     *
     * <p>文本支持 {@code &} 颜色码。内容请保持短小——ActionBar 只有一行。
     * 注意别在这里做传送、发消息之类的副作用，它每个刷新周期都会被调用。
     */
    default String hudLine(Player player) {
        return null;
    }

    /**
     * 该模式下队友头顶名牌的前缀，例如 {@code "&a[队友] &f"}。
     *
     * <p>返回 {@code null}（默认）表示**不改动名牌**。这一条很关键：爆破模式的
     * 名牌显示由 WarZBombDefuse 主插件自己负责，附加组件如果擅自设了前缀，
     * 就会和主插件抢同一个队伍的 prefix —— 最终显示哪个取决于谁后写，
     * 表现出来是「有时对有时错」，这类故障极难归因。
     *
     * <p>前缀是**每个原版队伍**的属性，而队伍按「模式 + 阵营」划分，
     * 所以它天然只加在队友头上 —— 前提是同时打开
     * {@link #hideEnemyNameTags()}，否则敌人那边也会顶着「[队友]」。
     */
    default String nameTagPrefix(Player player) {
        return null;
    }

    /**
     * 该模式是否隐藏敌人的头顶名牌。
     *
     * <p>开启后玩家只能看到同阵营队友的名牌，敌人的名牌完全不显示。
     * 这正是 WBD 在爆破模式里的行为，也是「没有隐藏敌人」这个问题的正解。
     *
     * <p>实现上用的是原版队伍的 {@code NAME_TAG_VISIBILITY}，不需要发包插件。
     */
    default boolean hideEnemyNameTags() {
        return false;
    }
}
