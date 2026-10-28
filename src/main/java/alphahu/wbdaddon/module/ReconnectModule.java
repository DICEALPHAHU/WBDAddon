package alphahu.wbdaddon.module;

import alphahu.wbdaddon.WBDAddon;
import alphahu.wbdaddon.api.AddonMode;
import alphahu.wbdaddon.mode.BombDefuseMode;
import com.warz.bombdefuse.api.WbdMatchEndEvent;
import com.warz.bombdefuse.api.WbdRoundEndEvent;
import com.warz.bombdefuse.api.WbdRoundStartEvent;
import com.warz.bombdefuse.arena.ArenaSession;
import com.warz.bombdefuse.arena.PlayerRecord;
import com.warz.bombdefuse.model.GameState;
import com.warz.bombdefuse.model.JoinMode;
import com.warz.bombdefuse.model.Team;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 断线重联。
 *
 * <h2>掉线时</h2>
 *
 * <p>比赛进行中（{@code LIVE / FREEZE / ROUND_END}）有参赛玩家掉线，
 * 本模块会记下他的队伍、经济与战绩，并为他<b>守住下一回合</b>：
 * 不是立刻暂停，而是等这一回合正常打完，在回合结算的间隙把 WBD 的 tick 停住，
 * 于是下一回合永远「还没开始」——直到他回来。
 *
 * <p>之所以选在回合结算间歇而不是下一回合冻结期：WBD 在 {@code FREEZE / LIVE}
 * 期间加入的玩家会被记成「排队等下一回合」，即使在冻结点暂停也救不回来；
 * 而在 {@code ROUND_END} 加入的玩家正好是排队状态，一恢复就跟着下一回合出生。
 *
 * <p>每支队伍每场只有一次这样的待遇（CS 里的战术暂停思路），
 * 用过之后再有人掉线只会提示一句，不会再把比赛定住。
 *
 * <h2>回来时</h2>
 *
 * <p>玩家重新连上服务器后会自动放回原比赛的原阵营，并把掉线前的
 * 经济、击杀、死亡、下包、拆包数据原样写回新的 {@code PlayerRecord}
 * （WBD 掉线时会直接删记录，不写回就相当于全部清零）。
 *
 * @author AlphaHu
 */
public class ReconnectModule implements AddonModule, Listener {

    private final WBDAddon plugin;

    /** 掉线待归的玩家，key = 玩家 UUID。 */
    private final Map<UUID, Reconnect> pending = new ConcurrentHashMap<>();

    /** 等待重连的竞技场，key = 竞技场 ID。同一时间每张图只守一个人。 */
    private final Map<String, Reconnect> waiting = new ConcurrentHashMap<>();

    /** 已经真正暂停住的竞技场，key = 竞技场 ID。 */
    private final Map<String, Reconnect> holding = new ConcurrentHashMap<>();

    /** 每场每队是否已经用过重连等待，key = 竞技场 ID。 */
    private final Map<String, Set<Team>> used = new ConcurrentHashMap<>();

    private BukkitTask ticker;

    private int holdMaxSeconds;
    private boolean autoRejoin;
    private boolean debug;

    public ReconnectModule(WBDAddon plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getId() {
        return "reconnect";
    }

    @Override
    public String getDisplayName() {
        return "断线重联";
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }

    @Override
    public void onEnable() {
        this.holdMaxSeconds = plugin.getConfig()
                .getInt("modules.reconnect.hold-max-seconds", 300);
        this.autoRejoin = plugin.getConfig()
                .getBoolean("modules.reconnect.auto-rejoin", true);
        this.debug = plugin.getConfig().getBoolean("modules.reconnect.debug", false);

        Bukkit.getPluginManager().registerEvents(this, plugin);
        // 只用来发现「等待被超时 / 管理员 / 投票提前结束」，一秒一次足够
        this.ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);

        plugin.getLogger().info("断线重联已启动：每队每场 1 次等待，最长 "
                + (holdMaxSeconds <= 0 ? "不限时" : holdMaxSeconds + " 秒")
                + "，重连后自动回到原阵营并继承经济与战绩。");
    }

    @Override
    public void onDisable() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        for (String arenaId : new ArrayList<>(holding.keySet())) {
            TechnicalPauseModule pause = pauseModule();
            if (pause != null) {
                pause.endReconnectHold(arenaId, "插件卸载");
            }
        }
        holding.clear();
        waiting.clear();
        pending.clear();
        used.clear();
        HandlerList.unregisterAll(this);
        plugin.getLogger().info("断线重联已卸载。");
    }

    // ------------------------------------------------------------------
    // 掉线
    // ------------------------------------------------------------------

    /**
     * 掉线时先把数据抓下来。
     *
     * <p>用 {@code LOWEST} 是刻意的：WBD 自己在默认优先级处理退出并
     * {@code leave()}，一旦它先跑，{@code PlayerRecord} 就没了，什么都抓不到。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (plugin.getApi() == null) {
            return;
        }
        AddonMode mode = plugin.getApi().getModeFor(player);
        if (mode == null || !BombDefuseMode.MODE_ID.equals(mode.getModeId())) {
            return;
        }
        Team team = teamOf(player);
        if (team == null) {
            return;
        }
        ArenaSession session = sessionOf(player);
        if (session == null) {
            return;
        }
        GameState state = session.getState();
        if (state != GameState.LIVE && state != GameState.FREEZE
                && state != GameState.ROUND_END) {
            return;
        }
        PlayerRecord record = session.getRecord(player);
        if (record == null) {
            return;
        }

        Reconnect r = new Reconnect(player.getUniqueId(), player.getName(),
                session.getConfig().getId(), team, record.getMoney(),
                record.getKills(), record.getDeaths(), record.getPlants(), record.getDefuses());
        pending.put(r.uuid, r);

        if (!claim(session.getConfig().getId(), team)) {
            broadcast(r.arenaId, "&7[断线重联] " + r.name + " 掉线了&7（本队本场已经用过等待重连）。");
            return;
        }

        broadcast(r.arenaId, "&e[断线重联] " + r.name + " 掉线了，"
                + "下一回合会暂停等待他回来&7（本队本场第 1 次）。");
        waiting.put(r.arenaId, r);
        if (state == GameState.ROUND_END) {
            startHold(r);
        }
        debug("掉线记录 " + r.name + " @ " + r.arenaId + "，经济 " + r.money
                + "，战绩 " + r.kills + "/" + r.deaths);
    }

    /** 回合打完、下一回合还没开始的这一瞬间，是唯一能把人塞回去的位置。 */
    @EventHandler
    public void onRoundEnd(WbdRoundEndEvent event) {
        Reconnect r = waiting.get(event.getArenaId());
        if (r == null || !pending.containsKey(r.uuid)) {
            waiting.remove(event.getArenaId());
            return;
        }
        startHold(r);
    }

    /** 回合真的开始了：说明等待已被绕过（管理员恢复 / 超时），不再重复守。 */
    @EventHandler
    public void onRoundStart(WbdRoundStartEvent event) {
        if (event.getRound() <= 1) {
            used.remove(event.getArenaId());
        }
        Reconnect r = waiting.get(event.getArenaId());
        if (r != null && !holding.containsKey(event.getArenaId())) {
            waiting.remove(event.getArenaId());
            debug("等待被绕过：" + r.name + " @ " + event.getArenaId());
        }
    }

    @EventHandler
    public void onMatchEnd(WbdMatchEndEvent event) {
        String arenaId = event.getArenaId();
        used.remove(arenaId);
        waiting.remove(arenaId);
        holding.remove(arenaId);
        pending.values().removeIf(r -> r.arenaId.equals(arenaId));
        debug("比赛结束，清理 " + arenaId + " 的重联记录");
    }

    // ------------------------------------------------------------------
    // 重连
    // ------------------------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!pending.containsKey(player.getUniqueId())) {
            return;
        }
        // 等两秒：客户端还在加载世界，这时候传送 / 改模式容易白忙
        Bukkit.getScheduler().runTaskLater(plugin, () -> tryRejoin(player), 40L);
    }

    private void tryRejoin(Player player) {
        Reconnect r = pending.get(player.getUniqueId());
        if (r == null || !player.isOnline()) {
            return;
        }
        if (!autoRejoin) {
            pending.remove(r.uuid);
            clearWait(r);
            return;
        }

        ArenaSession session = session(r.arenaId);
        if (session == null || session.getState() == GameState.WAITING) {
            pending.remove(r.uuid);
            clearWait(r);
            return;
        }
        // 已经自己挤进别的房间了，就别硬拉回来
        if (plugin.getApi() != null && plugin.getApi().getModeFor(player) != null) {
            pending.remove(r.uuid);
            clearWait(r);
            return;
        }

        boolean joined;
        try {
            joined = session.join(player, r.team == Team.T ? JoinMode.T : JoinMode.CT);
        } catch (Throwable t) {
            plugin.getLogger().warning("断线重联：放回竞技场失败：" + t);
            joined = false;
        }
        if (!joined) {
            pending.remove(r.uuid);
            clearWait(r);
            player.sendMessage(color("&c重连失败：原比赛已经无法加入。"));
            return;
        }

        restore(session, player, r);
        pending.remove(r.uuid);
        clearWait(r);

        broadcast(r.arenaId, "&a[断线重联] " + r.name + " 已重连，回到原阵营。");
        debug("重连成功 " + r.name + " @ " + r.arenaId + "，已写回经济与战绩");
    }

    /** 把掉线前的经济与战绩写回 WBD 新建的记录里。 */
    private void restore(ArenaSession session, Player player, Reconnect r) {
        PlayerRecord record = session.getRecord(player);
        if (record == null) {
            return;
        }
        record.setTeam(r.team);
        record.setMoney(r.money);
        for (int i = 0; i < r.kills; i++) {
            record.addKill();
        }
        for (int i = 0; i < r.deaths; i++) {
            record.addDeath();
        }
        for (int i = 0; i < r.plants; i++) {
            record.addPlant();
        }
        for (int i = 0; i < r.defuses; i++) {
            record.addDefuse();
        }
    }

    // ------------------------------------------------------------------
    // 等待
    // ------------------------------------------------------------------

    private void startHold(Reconnect r) {
        TechnicalPauseModule pause = pauseModule();
        if (pause == null) {
            waiting.remove(r.arenaId);
            return;
        }
        if (!pause.startReconnectHold(r.arenaId, r.name, holdMaxSeconds)) {
            waiting.remove(r.arenaId);
            debug("开始等待失败（可能已经在暂停中）：" + r.arenaId);
            return;
        }
        holding.put(r.arenaId, r);
        debug("开始等待重连 " + r.name + " @ " + r.arenaId);
    }

    /** 等待不再需要（人回来了 / 比赛结束）。 */
    private void clearWait(Reconnect r) {
        waiting.remove(r.arenaId, r);
        if (holding.remove(r.arenaId, r)) {
            TechnicalPauseModule pause = pauseModule();
            if (pause != null) {
                pause.endReconnectHold(r.arenaId, "玩家已重连");
            }
        }
    }

    /** 每秒核对一次：等待被超时 / 管理员 / 投票结束了就把状态收干净。 */
    private void tick() {
        if (holding.isEmpty()) {
            return;
        }
        TechnicalPauseModule pause = pauseModule();
        for (Map.Entry<String, Reconnect> entry : new ArrayList<>(holding.entrySet())) {
            String arenaId = entry.getKey();
            if (pause != null && pause.isHolding(arenaId)) {
                continue;
            }
            holding.remove(arenaId);
            Reconnect r = waiting.remove(arenaId);
            if (r != null) {
                broadcast(arenaId, "&7[断线重联] 等待已结束，" + r.name + " 仍未回来。");
                debug("等待结束 " + r.name + " @ " + arenaId);
            }
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 领取本队本场的重连等待名额。 */
    private boolean claim(String arenaId, Team team) {
        return used.computeIfAbsent(arenaId, k -> ConcurrentHashMap.newKeySet()).add(team);
    }

    private TechnicalPauseModule pauseModule() {
        AddonModule module = plugin.getModuleManager().getModule("technical-pause");
        if (module instanceof TechnicalPauseModule pause
                && plugin.getModuleManager().isEnabled(module)) {
            return pause;
        }
        return null;
    }

    private Team teamOf(Player player) {
        if (player == null || plugin.getApi() == null) {
            return null;
        }
        AddonMode mode = plugin.getApi().getModeFor(player);
        if (mode == null || !BombDefuseMode.MODE_ID.equals(mode.getModeId())) {
            return null;
        }
        String teamId = mode.getTeamId(player);
        if ("T".equals(teamId)) {
            return Team.T;
        }
        if ("CT".equals(teamId)) {
            return Team.CT;
        }
        return null;
    }

    private ArenaSession session(String arenaId) {
        if (arenaId == null || plugin.getWbd() == null
                || plugin.getWbd().getArenaManager() == null) {
            return null;
        }
        return plugin.getWbd().getArenaManager().getSession(arenaId);
    }

    private ArenaSession sessionOf(Player player) {
        if (player == null || plugin.getWbd() == null
                || plugin.getWbd().getArenaManager() == null) {
            return null;
        }
        return plugin.getWbd().getArenaManager().getSession(player);
    }

    private void broadcast(String arenaId, String message) {
        String text = color(message);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (arenaId.equals(arenaIdOf(player))) {
                player.sendMessage(text);
            }
        }
    }

    private String arenaIdOf(Player player) {
        if (player == null || plugin.getApi() == null) {
            return null;
        }
        AddonMode mode = plugin.getApi().getModeFor(player);
        if (mode == null || !BombDefuseMode.MODE_ID.equals(mode.getModeId())) {
            return null;
        }
        return mode.getArenaId(player);
    }

    private void debug(String message) {
        if (debug) {
            plugin.getLogger().info("[reconnect] " + message);
        }
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }

    // ------------------------------------------------------------------
    // 内部类
    // ------------------------------------------------------------------

    private static final class Reconnect {
        final UUID uuid;
        final String name;
        final String arenaId;
        final Team team;
        final int money;
        final int kills;
        final int deaths;
        final int plants;
        final int defuses;

        Reconnect(UUID uuid, String name, String arenaId, Team team, int money,
                  int kills, int deaths, int plants, int defuses) {
            this.uuid = uuid;
            this.name = name;
            this.arenaId = arenaId;
            this.team = team;
            this.money = money;
            this.kills = kills;
            this.deaths = deaths;
            this.plants = plants;
            this.defuses = defuses;
        }
    }
}
