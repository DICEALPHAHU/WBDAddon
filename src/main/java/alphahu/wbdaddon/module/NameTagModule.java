package alphahu.wbdaddon.module;

import alphahu.wbdaddon.WBDAddon;
import alphahu.wbdaddon.api.AddonMode;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 昵称标签控制：队友看得见、敌人看不见。
 *
 * <h2>原理</h2>
 *
 * <p>原版里「谁看得见谁的名字」是<b>按计分板队伍</b>算的：把某个玩家放进一个
 * {@code NAME_TAG_VISIBILITY = NEVER} 的队伍，<b>这块计分板的使用者</b>就看不到他的名牌。
 * 所以这里对每个观察者单独操作他自己那块计分板——给「不是队友的人」建一个隐藏队伍。
 *
 * <p>不能只写主计分板：WBD 会给每名参赛者单独创建一块计分板画侧边栏，
 * 写在主计分板上的队伍对他们完全不生效。
 *
 * <h2>与 WBD 自带前缀的关系</h2>
 *
 * <p>WBD 自己会把队友放进 {@code wbd_teammates} 队伍并加「[队友]」前缀。
 * 本模块<b>只往隐藏队伍里塞敌人</b>，不碰队友，因此两个机制不会互相抢人：
 * 队友留在 WBD 的队伍里带前缀，敌人被塞进隐藏队伍。
 *
 * @author AlphaHu
 */
public class NameTagModule implements AddonModule {

    /** 隐藏敌人名牌的队伍名。 */
    private static final String HIDE_TEAM = "wbd_hide_enemy";

    private final WBDAddon plugin;

    /** 观察者 -> 上一次同步用的计分板；换板时强制重建。 */
    private final Map<UUID, Scoreboard> syncedBoards = new ConcurrentHashMap<>();

    /** 观察者 -> 自己隐藏队伍里当前有哪些人。 */
    private final Map<UUID, Set<String>> hiddenEntries = new ConcurrentHashMap<>();

    /** 观察者 -> 被我们藏起来的「对方观战者」。 */
    private final Map<UUID, Set<UUID>> hiddenSpectators = new ConcurrentHashMap<>();

    private boolean hideEnemySpectators;

    private BukkitTask task;
    private long intervalTicks;
    private boolean hideEnemies;

    public NameTagModule(WBDAddon plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getId() {
        return "name-tag";
    }

    @Override
    public String getDisplayName() {
        return "昵称标签";
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }

    @Override
    public void onEnable() {
        this.intervalTicks = Math.max(5L, plugin.getConfig()
                .getLong("modules.name-tag.interval-ticks", 10L));
        this.hideEnemies = plugin.getConfig()
                .getBoolean("modules.name-tag.hide-enemies", true);
        this.hideEnemySpectators = plugin.getConfig()
                .getBoolean("modules.name-tag.hide-enemy-spectators", true);

        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, intervalTicks);
        plugin.getLogger().info("昵称标签已启动：" + (hideEnemies ? "敌人名牌对你看不到" : "只同步不隐藏")
                + "，每 " + intervalTicks + " tick 同步一次。");
    }

    @Override
    public void onDisable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        cleanupAll();
        plugin.getLogger().info("昵称标签已卸载。");
    }

    // ------------------------------------------------------------------
    // 同步
    // ------------------------------------------------------------------

    private void tick() {
        if (plugin.getApi() == null) return;
        if (Bukkit.getScoreboardManager() == null) return;
        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            // 大厅 / 非参赛者用的是主计分板，大家共用一块，写了会互相打架
            Scoreboard board = viewer.getScoreboard();
            if (board == null || board == main) {
                syncedBoards.remove(viewer.getUniqueId());
                hiddenEntries.remove(viewer.getUniqueId());
                continue;
            }
            syncViewer(viewer, board);
        }
    }

    private void syncViewer(Player viewer, Scoreboard board) {
        AddonMode mode = plugin.getApi().getModeFor(viewer);
        String teamId = mode == null ? null : mode.getTeamId(viewer);
        if (mode == null || teamId == null) {
            clearViewer(viewer);
            return;
        }

        // 换计分板（WBD 重建侧边栏）时，旧队伍已经不存在了，直接重建
        boolean rebuilt = syncedBoards.put(viewer.getUniqueId(), board) != board;
        Set<String> current = rebuilt
                ? new HashSet<>() : hiddenEntries.computeIfAbsent(
                viewer.getUniqueId(), k -> new HashSet<>());

        Set<String> desired = new HashSet<>();
        if (hideEnemies) {
            for (Player target : Bukkit.getOnlinePlayers()) {
                if (target.equals(viewer)) continue;
                AddonMode targetMode = plugin.getApi().getModeFor(target);
                boolean teammate = targetMode == mode
                        && teamId.equals(targetMode.getTeamId(target));
                if (!teammate) {
                    desired.add(target.getName());
                }
            }
        }

        syncHiddenSpectators(viewer, mode, teamId);

        Team team = board.getTeam(HIDE_TEAM);
        if (team == null) {
            team = board.registerNewTeam(HIDE_TEAM);
            team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
            current.clear();
        }

        for (String entry : new HashSet<>(current)) {
            if (!desired.contains(entry)) {
                team.removeEntry(entry);
                current.remove(entry);
            }
        }
        for (String entry : desired) {
            if (current.add(entry)) {
                team.addEntry(entry);
            }
        }
        hiddenEntries.put(viewer.getUniqueId(), current);
    }

    /**
     * 对方阵营的观战者，对彼此直接隐身。
     *
     * <p>光靠隐藏名牌挡不住小地图：JourneyMap 画的是实体列表，两个观战者
     * 都不属于任何队伍时，客户端会把对方当成「路人」画在地图上，于是我方
     * 存活者的位置就被对面的观战者看光了。
     *
     * <p>观战者在游戏里本来就没有实体可见性，直接 {@code hidePlayer} 掉，
     * 客户端连实体都收不到，小地图自然没有可画的东西。
     */
    private void syncHiddenSpectators(Player viewer, AddonMode mode, String teamId) {
        Set<UUID> hidden = hiddenSpectators.computeIfAbsent(viewer.getUniqueId(),
                k -> ConcurrentHashMap.newKeySet());
        if (!hideEnemySpectators) {
            clearHidden(viewer, hidden);
            return;
        }

        Set<UUID> desired = new HashSet<>();
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(viewer)) continue;
            AddonMode otherMode = plugin.getApi().getModeFor(other);
            if (otherMode != mode) continue;
            if (otherMode.isAlive(other)) continue;           // 只管对方的观战者
            String otherTeam = otherMode.getTeamId(other);
            if (otherTeam == null || otherTeam.equals(teamId)) continue;
            desired.add(other.getUniqueId());
        }

        for (UUID id : new HashSet<>(hidden)) {
            if (desired.contains(id)) continue;
            Player other = Bukkit.getPlayer(id);
            if (other != null) viewer.showPlayer(plugin, other);
            hidden.remove(id);
        }
        for (UUID id : desired) {
            if (hidden.contains(id)) continue;
            Player other = Bukkit.getPlayer(id);
            if (other == null) continue;
            viewer.hidePlayer(plugin, other);
            hidden.add(id);
        }
    }

    private void clearHidden(Player viewer, Set<UUID> hidden) {
        for (UUID id : new HashSet<>(hidden)) {
            Player other = Bukkit.getPlayer(id);
            if (other != null) viewer.showPlayer(plugin, other);
        }
        hidden.clear();
    }

    private void clearViewer(Player viewer) {
        Set<UUID> hiddenSpectatorsOfViewer = hiddenSpectators.remove(viewer.getUniqueId());
        if (hiddenSpectatorsOfViewer != null) {
            clearHidden(viewer, hiddenSpectatorsOfViewer);
        }
        Set<String> current = hiddenEntries.remove(viewer.getUniqueId());
        syncedBoards.remove(viewer.getUniqueId());
        if (current == null || current.isEmpty()) return;
        Scoreboard board = viewer.getScoreboard();
        if (board == null) return;
        Team team = board.getTeam(HIDE_TEAM);
        if (team == null) return;
        for (String entry : current) {
            team.removeEntry(entry);
        }
    }

    private void cleanupAll() {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            clearViewer(viewer);
        }
        hiddenEntries.clear();
        syncedBoards.clear();
        hiddenSpectators.clear();
    }
}
