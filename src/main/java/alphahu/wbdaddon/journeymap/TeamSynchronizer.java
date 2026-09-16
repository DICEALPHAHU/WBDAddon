package alphahu.wbdaddon.journeymap;

import alphahu.wbdaddon.WBDAddon;
import com.warz.bombdefuse.arena.ArenaSession;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

/**
 * 队伍同步逻辑。
 *
 * <p>把 WarZBombDefuse 的队伍数据同步到原版 Scoreboard Team。
 * 活着的 T/CT 玩家加入对应原版队伍，死亡/观战玩家移出队伍。
 * JourneyMap 客户端读取原版 Team 数据实现队友可见、敌军隐藏。
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
     *   <li>从 WBD 获取玩家的 ArenaSession 和队伍</li>
     *   <li>判断玩家是否存活。死亡/观战玩家不加入任何队伍</li>
     *   <li>先把玩家从所有本模块创建的原版队伍中移除</li>
     *   <li>只有活着的 T/CT 玩家才加入对应队伍</li>
     * </ol>
     */
    private void syncPlayer(Scoreboard sb, Player player) {
        ArenaSession session = plugin.getWbd().getArenaManager().getSession(player);
        String targetTeamName = null;

        if (session != null) {
            // 先判断玩家是否存活，死亡的观战玩家不加入任何队伍
            boolean alive = session.getRecord(player).isAlive();

            if (alive) {
                Object team = session.getTeam(player);
                if (team != null) {
                    String teamStr = team.toString();
                    if ("T".equalsIgnoreCase(teamStr)) {
                        targetTeamName = teamPrefix + "T";
                    } else if ("CT".equalsIgnoreCase(teamStr)) {
                        targetTeamName = teamPrefix + "CT";
                    }
                }
            }
            // 如果 alive == false，targetTeamName 保持 null，玩家会被移出所有原版队伍
        }

        // 先把玩家从所有本模块创建的原版队伍中移除
        for (Team t : sb.getTeams()) {
            if (t.getName().startsWith(teamPrefix)) {
                if (t.hasEntry(player.getName())) {
                    t.removeEntry(player.getName());
                }
            }
        }

        // 只有活着的 T/CT 玩家才加入对应队伍
        if (targetTeamName != null) {
            Team team = sb.getTeam(targetTeamName);
            if (team == null) {
                team = sb.registerNewTeam(targetTeamName);
            }
            team.addEntry(player.getName());
        }
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
}