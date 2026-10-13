package alphahu.wbdaddon.journeymap;

import alphahu.wbdaddon.WBDAddon;
import alphahu.wbdaddon.api.AddonMode;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

/**
 * 队伍同步逻辑。
 *
 * <p>把游戏模式的队伍数据同步到原版 Scoreboard Team。
 * 活着的参赛者加入对应原版队伍，死亡/观战玩家移出队伍。
 * JourneyMap 客户端读取原版 Team 数据实现队友可见、敌军隐藏。
 *
 * <p>队伍从 {@link AddonMode#getTeamId} 取，而不是从 WBD 的 ArenaSession 取，
 * 因此任何注册进来的模式（例如团队死斗）都能直接获得队友可见性，
 * 且多个模式的队伍标签可以共存（同一模式下同一阵营才同队）。
 *
 * @author AlphaHu
 */
public class TeamSynchronizer {

    private final WBDAddon plugin;

    /** 原版队伍名前缀，从配置读取，避免和其他插件的队伍冲突 */
    private final String teamPrefix;

    public TeamSynchronizer(WBDAddon plugin) {
        this.plugin = plugin;
        this.teamPrefix = plugin.getConfig()
                .getString("modules.journeymap-bridge.team-prefix", "wbd_");
    }

    /**
     * 同步所有在线玩家的队伍状态。
     * 由 JourneyMapModule 的定时任务每秒调用一次。
     */
    public void syncAllPlayers() {
        if (Bukkit.getScoreboardManager() == null) return;
        if (plugin.getApi() == null) return;
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();

        for (Player player : Bukkit.getOnlinePlayers()) {
            syncPlayer(sb, player);
        }
    }

    /**
     * 同步单个玩家的队伍状态。
     *
     * <p>逻辑：
     * <ol>
     *   <li>问模式注册表：该玩家属于哪个模式、哪一方、是否存活</li>
     *   <li>死亡/观战玩家不加入任何队伍</li>
     *   <li>先把玩家从所有本模块创建的原版队伍中移除</li>
     *   <li>只有存活且分到了阵营的玩家才加入对应队伍</li>
     * </ol>
     */
    private void syncPlayer(Scoreboard sb, Player player) {
        String targetTeamName = resolveTargetTeam(player);

        // 找出玩家当前所在的、由本模块管理的队伍
        Team currentTeam = null;
        for (Team t : sb.getTeams()) {
            if (t.getName().startsWith(teamPrefix) && t.hasEntry(player.getName())) {
                currentTeam = t;
                break;
            }
        }
        String currentTeamName = currentTeam == null ? null : currentTeam.getName();

        // 队伍归属没变就什么都不做 —— 这一行是修「计分板闪烁」的关键。
        //
        // 原来的写法是每轮无条件 remove + add 一遍。看着无害，实际每次增删都会
        // 向相关玩家推送原版队伍包：20 tick 一轮、十几个人，就是每秒几十个包，
        // 客户端的队伍数据反复抖动，表现出来就是记分板在闪。
        // 队伍归属不变时，一个包都不该发。
        if (java.util.Objects.equals(currentTeamName, targetTeamName)) {
            return;
        }

        if (currentTeam != null) {
            currentTeam.removeEntry(player.getName());
        }
        if (targetTeamName != null) {
            Team team = sb.getTeam(targetTeamName);
            if (team == null) {
                team = sb.registerNewTeam(targetTeamName);
                applyModeOptions(team, player);
            }
            team.addEntry(player.getName());
        }
    }

    /** 原版队伍 prefix 有 16 字符硬上限，超了 {@code setPrefix} 会直接抛异常。 */
    private static final int MAX_PREFIX_LENGTH = 16;

    /**
     * 按模式配置设置原版队伍的名牌行为：队友前缀 + 隐藏敌人名牌。
     *
     * <p><b>只在队伍新建时设一次</b>，不是每轮都设。这两个值对同一个队伍来说是
     * 静态的，而每轮重写会向客户端推送多余的队伍包（20 tick 一轮、十几个人），
     * 那正是之前修「计分板闪烁」时踩过的坑 —— 那次的原因就是无条件增删条目，
     * 这次是同一个道理。
     *
     * <p>模式没有声明前缀、也没要求隐藏名牌时（爆破模式就是如此），
     * 这里什么都不做，队伍保持原版默认行为。
     */
    private void applyModeOptions(Team team, Player sample) {
        AddonMode mode = plugin.getApi().getModeFor(sample);
        if (mode == null) {
            return;
        }

        String prefix = mode.nameTagPrefix(sample);
        if (prefix != null && !prefix.isEmpty()) {
            String colored = org.bukkit.ChatColor.translateAlternateColorCodes('&', prefix);
            team.setPrefix(colored.length() > MAX_PREFIX_LENGTH
                    ? colored.substring(0, MAX_PREFIX_LENGTH)
                    : colored);
        }

        if (mode.hideEnemyNameTags()) {
            // FOR_OWN_TEAM：名牌只对自己这一方可见。
            // 于是玩家看队友一目了然，看敌人却连名字都没有 —— 这正是
            // 「隐藏敌人」的实现，而且完全走原版机制，不需要发包插件。
            //
            // 枚举名是 FOR_OWN_TEAM 而不是直觉上的 HIDE_FOR_OTHER_TEAMS：
            // 后者的语义是「只对其他队伍可见」，正好是反的。
            team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.FOR_OWN_TEAM);
        }
    }

    /** 该玩家此刻应该属于哪个原版队伍；不属于任何队伍时返回 null。 */
    private String resolveTargetTeam(Player player) {
        AddonMode mode = plugin.getApi().getModeFor(player);
        if (mode == null || !mode.isAlive(player)) {
            return null;
        }
        String teamId = mode.getTeamId(player);
        if (teamId == null || teamId.isBlank()) {
            return null;
        }
        return buildTeamName(mode.getModeId(), teamId);
    }

    /**
     * 清理本模块创建的所有原版队伍。
     * 由 JourneyMapModule 的 onDisable() 调用。
     */
    public void cleanupAllTeams() {
        if (Bukkit.getScoreboardManager() == null) return;
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        for (Team t : sb.getTeams()) {
            if (t.getName().startsWith(teamPrefix)) {
                t.unregister();
            }
        }
    }

    /**
     * 拼出原版队伍名。
     *
     * <p>名字里必须带上模式 id：爆破与死斗都有 T/CT 阵营，
     * 若只按阵营拼名，两个模式的玩家会挤进同一个原版队伍，
     * 在 JourneyMap 上互相看得见——那等于把敌军显示给了对手。
     *
     * <p>原版队伍名有 16 字符硬上限，超了 {@code registerNewTeam} 会直接抛异常。
     * 前缀是服主配的、模式 id 来自第三方插件，两边都不可控，所以这里统一截断兜底。
     */
    private String buildTeamName(String modeId, String teamId) {
        String name = teamPrefix + sanitize(modeId, 8) + "_" + sanitize(teamId, 6);
        return name.length() > 16 ? name.substring(0, 16) : name;
    }

    /**
     * 把模式 id / 阵营 id 洗成合法的原版队伍名片段。
     *
     * <p>第三方模式完全可能返回带点、带冒号甚至带空格的 id，
     * 这里统一过滤成字母数字下划线，免得注册队伍时抛 IllegalArgumentException
     * 把整个同步任务打断。
     */
    private String sanitize(String raw, int maxLength) {
        if (raw == null) return "unknown";
        String cleaned = raw.replaceAll("[^A-Za-z0-9_]", "_");
        if (cleaned.isEmpty()) return "unknown";
        return cleaned.length() > maxLength ? cleaned.substring(0, maxLength) : cleaned;
    }
}