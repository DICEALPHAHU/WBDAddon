package alphahu.wbdaddon.matchstatus;

import alphahu.wbdaddon.WBDAddon;
import com.warz.bombdefuse.arena.ArenaManager;
import com.warz.bombdefuse.arena.ArenaSession;
import com.warz.bombdefuse.arena.BombState;
import com.warz.bombdefuse.model.Team;
import com.warz.bombdefuse.util.ActionBars;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 比赛状态管理器。
 *
 * <p>功能：
 * <ul>
 *   <li>在每个竞技场玩家屏幕顶部（ActionBar）实时显示 T / CT 双方存活人数</li>
 *   <li>炸弹安放后，在该场玩家头顶用 BossBar 显示炸弹倒计时进度条，爆/拆后消失</li>
 * </ul>
 *
 * <p>实现上用一个低频轮询任务（默认每 2 tick）遍历所有在线玩家，
 * 根据其所属竞技场刷新 ActionBar 与炸弹 BossBar。
 *
 * @author AlphaHu
 */
public class StatusManager {

    private final WBDAddon plugin;

    /** 定时刷新任务。 */
    private BukkitTask task;

    /** 每个玩家单独一条炸弹 BossBar（UUID -> Bar）。 */
    private final Map<UUID, BossBar> bombBars = new ConcurrentHashMap<>();

    /** 每个竞技场炸弹的总时长（首次看到安放时记录，用于算进度）。 */
    private final Map<ArenaSession, Integer> bombTotalTicks = new ConcurrentHashMap<>();

    public StatusManager(WBDAddon plugin) {
        this.plugin = plugin;
    }

    public void start() {
        long interval = Math.max(1, plugin.getConfig()
                .getLong("modules.match-status.update-interval-ticks", 2));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, interval);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (BossBar bar : bombBars.values()) {
            bar.removeAll();
        }
        bombBars.clear();
        bombTotalTicks.clear();
    }

    private void tick() {
        ArenaManager am = plugin.getWbd().getArenaManager();
        if (am == null) return;

        boolean aliveBar = plugin.getConfig()
                .getBoolean("modules.match-status.enable-alive-action-bar", true);
        boolean bombBar = plugin.getConfig()
                .getBoolean("modules.match-status.enable-bomb-bar", true);

        // 本 tick 仍处于安放状态的竞技场，用于结束后清理过期的总时长记录
        Set<ArenaSession> plantedSessions = new HashSet<>();

        for (Player player : Bukkit.getOnlinePlayers()) {
            ArenaSession session = am.getSession(player);
            if (session == null) {
                hideBomb(player);
                continue;
            }

            if (aliveBar) {
                // Arclight(Spigot) 没有 Paper 的 Player.sendActionBar，改用 WBD 自带的 ActionBars
                ActionBars.send(player, color(aliveText(session)));
            }

            BombState bomb = session.getBomb();
            if (bombBar && bomb != null && bomb.isPlanted()) {
                plantedSessions.add(session);
                updateBombBar(player, session, bomb);
            } else {
                hideBomb(player);
            }
        }

        // 炸弹已爆/已拆或竞技场已销毁时丢弃总时长记录，避免 Map 无限增长
        // （玩家全部离场时 plantedSessions 为空，同样会被清掉）
        bombTotalTicks.keySet().retainAll(plantedSessions);

        // 玩家离线后其 BossBar 也要丢弃，否则 BossBar 对象会一直留在 Map 里
        Iterator<Map.Entry<UUID, BossBar>> barIt = bombBars.entrySet().iterator();
        while (barIt.hasNext()) {
            Map.Entry<UUID, BossBar> barEntry = barIt.next();
            if (Bukkit.getPlayer(barEntry.getKey()) == null) {
                barEntry.getValue().removeAll();
                barIt.remove();
            }
        }
    }

    /** 生成顶部存活人数文字：按配置选择数字样式或方块样式。 */
    private String aliveText(ArenaSession session) {
        String style = plugin.getConfig()
                .getString("modules.match-status.alive-display-style", "number");
        return "block".equalsIgnoreCase(style) ? blockText(session) : numberText(session);
    }

    /** 数字样式：T 存活 3/5   CT 存活 4/5 */
    private String numberText(ArenaSession session) {
        int tAlive = session.aliveCount(Team.T);
        int ctAlive = session.aliveCount(Team.CT);
        int tTotal = tAlive + session.deadCount(Team.T);
        int ctTotal = ctAlive + session.deadCount(Team.CT);

        String fmt = plugin.getConfig().getString(
                "modules.match-status.alive-line-format",
                "&cT 存活 &f%t_alive%&7/&f%t_total%   &bCT 存活 &f%ct_alive%&7/&f%ct_total%");
        return fmt
                .replace("%t_alive%", String.valueOf(tAlive))
                .replace("%t_total%", String.valueOf(tTotal))
                .replace("%ct_alive%", String.valueOf(ctAlive))
                .replace("%ct_total%", String.valueOf(ctTotal));
    }

    /**
     * 方块样式：每人一个方块，淘汰的排在前面、存活的排在后面。
     * 默认同为「■」字形，靠亮/暗颜色区分存活与淘汰，保证绝对等宽。
     */
    private String blockText(ArenaSession session) {
        String tBlocks = buildBlocks(session, Team.T,
                statusCfg("block-t-color", "&c"), statusCfg("block-t-dead-color", "&4"));
        String ctBlocks = buildBlocks(session, Team.CT,
                statusCfg("block-ct-color", "&9"), statusCfg("block-ct-dead-color", "&1"));

        String fmt = plugin.getConfig().getString(
                "modules.match-status.block-line-format",
                "&cT方 %t_blocks%  &9CT方 %ct_blocks%");
        return fmt
                .replace("%t_blocks%", tBlocks)
                .replace("%ct_blocks%", ctBlocks);
    }

    /** 生成一个队伍的方块串：先淘汰（暗色），后存活（亮色）。 */
    private String buildBlocks(ArenaSession session, Team team, String aliveColor, String deadColor) {
        String aliveChar = statusCfg("block-alive-char", "■");
        String deadChar = statusCfg("block-dead-char", "■");

        int alive = Math.max(0, session.aliveCount(team));
        int dead = Math.max(0, session.deadCount(team));

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < dead; i++) {
            sb.append(deadColor).append(deadChar);
        }
        for (int i = 0; i < alive; i++) {
            sb.append(aliveColor).append(aliveChar);
        }
        return sb.toString();
    }

    /** 读取 match-status 下的配置项。 */
    private String statusCfg(String key, String def) {
        return plugin.getConfig().getString("modules.match-status." + key, def);
    }

    /** 刷新（或首次创建）该玩家的炸弹倒计时进度条。 */
    private void updateBombBar(Player player, ArenaSession session, BombState bomb) {
        BossBar bar = bombBars.computeIfAbsent(player.getUniqueId(), k -> {
            BarColor color = parseColor("modules.match-status.bomb-bar-color", BarColor.RED);
            BarStyle style = parseStyle("modules.match-status.bomb-bar-style", BarStyle.SOLID);
            BossBar created = Bukkit.createBossBar("", color, style);
            created.addPlayer(player);
            return created;
        });

        int total = bombTotalTicks.computeIfAbsent(session, k -> bomb.getTicksRemaining());
        int remaining = Math.max(0, bomb.getTicksRemaining());
        double progress = total <= 0 ? 0.0
                : Math.max(0.0, Math.min(1.0, (double) remaining / total));
        bar.setProgress(progress);

        double seconds = Math.ceil(remaining / 20.0);
        String title = plugin.getConfig().getString(
                "modules.match-status.bomb-bar-title", "&c炸弹 &7还有 &e%sec% &7秒爆炸");
        bar.setTitle(color(title.replace("%sec%", String.valueOf((int) seconds))));
    }

    /** 隐藏并移除某个玩家的炸弹进度条。 */
    private void hideBomb(Player player) {
        BossBar bar = bombBars.remove(player.getUniqueId());
        if (bar != null) {
            bar.removeAll();
        }
    }

    private BarColor parseColor(String path, BarColor def) {
        try {
            return BarColor.valueOf(plugin.getConfig().getString(path, def.name()));
        } catch (Exception e) {
            return def;
        }
    }

    private BarStyle parseStyle(String path, BarStyle def) {
        try {
            return BarStyle.valueOf(plugin.getConfig().getString(path, def.name()));
        } catch (Exception e) {
            return def;
        }
    }

    private String color(String s) {
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', s);
    }
}
