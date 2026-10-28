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
import java.util.LinkedHashSet;
import java.util.List;
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
 * <h2>多人掉线</h2>
 *
 * <p>一次暂停可以同时等好几个人，而不是谁先掉谁独占：
 *
 * <ul>
 *   <li>同一时间掉好几个人（网络抖动、同一队集体掉线）：<b>只停一次表</b>，
 *       所有人一起等；回来一个就把他从名单里划掉，顶栏显示还剩谁。</li>
 *   <li>名额按「阵营」算，不按人头：同一队三个人一起掉，只消耗该队这一次待遇。</li>
 *   <li>暂停期间又有人掉线，算进同一次等待（暂停总时长不会因此延长）。</li>
 * </ul>
 *
 * <h2>两个时间窗</h2>
 *
 * <p>「等」和「算不算重连」是两件事：
 *
 * <ul>
 *   <li>{@code hold-max-seconds}（默认 60 秒）：比赛最多为他们停多久。到点就继续，
 *       不能让九个人陪一个人干等。</li>
 *   <li>{@code pending-max-seconds}（默认 300 秒）：掉线后多久之内回来还算「重连」。
 *       这段时间里回来，哪怕比赛早就继续了，也会自动放回原阵营并写回数据；
 *       超过就按弃赛处理，记录清空，他想打只能自己重新 join。</li>
 * </ul>
 *
 * @author AlphaHu
 */
public class ReconnectModule implements AddonModule, Listener {

    private final WBDAddon plugin;

    /** 掉线待归的玩家，key = 玩家 UUID。 */
    private final Map<UUID, Reconnect> pending = new ConcurrentHashMap<>();

    /** 本次等待覆盖的玩家，key = 竞技场 ID。集合存在就说明这次「等待」还没结束。 */
    private final Map<String, Set<UUID>> waiting = new ConcurrentHashMap<>();

    /** 已经真正把 tick 停住的竞技场。 */
    private final Set<String> holding = ConcurrentHashMap.newKeySet();

    /** 每场每队是否已经用过重连等待，key = 竞技场 ID。 */
    private final Map<String, Set<Team>> used = new ConcurrentHashMap<>();

    private BukkitTask ticker;

    private int holdMaxSeconds;
    private int pendingMaxSeconds;
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
                .getInt("modules.reconnect.hold-max-seconds", 60);
        this.pendingMaxSeconds = plugin.getConfig()
                .getInt("modules.reconnect.pending-max-seconds", 300);
        this.autoRejoin = plugin.getConfig()
                .getBoolean("modules.reconnect.auto-rejoin", true);
        this.debug = plugin.getConfig().getBoolean("modules.reconnect.debug", false);

        Bukkit.getPluginManager().registerEvents(this, plugin);
        // 一秒一次：发现「等待被超时 / 管理员 / 投票提前结束」，顺便清理过期的重联记录
        this.ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);

        plugin.getLogger().info("断线重联已启动：每队每场 1 次等待，最多等 "
                + (holdMaxSeconds <= 0 ? "不限时" : holdMaxSeconds + " 秒")
                + "；" + (pendingMaxSeconds <= 0 ? "不限时" : pendingMaxSeconds + " 秒")
                + "内回来仍算重连并继承经济与战绩（多人掉线共用同一次暂停）。");
    }

    @Override
    public void onDisable() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        for (String arenaId : new ArrayList<>(holding)) {
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

        Set<UUID> covered = cover(r);
        if (covered == null) {
            broadcast(r.arenaId, notice("&f" + r.name + " &7断开连接"
                    + " &8(&7本场重连名额已用尽，比赛继续&8)"));
            return;
        }
        covered.add(r.uuid);

        broadcast(r.arenaId, notice("&f" + r.name + " &7断开连接 — "
                + "回合结束后暂停等待" + (covered.size() > 1 ? " " + covered.size() + " 人" : "")
                + "重连 &8(&7" + teamLabel(r.team) + "本场首次"
                + (holdMaxSeconds <= 0 ? " · 不限时" : " · 上限 " + holdMaxSeconds + "s")
                + (pendingMaxSeconds <= 0 ? "" : " · " + (pendingMaxSeconds / 60) + " 分钟内有效")
                + "&8)"));
        if (state == GameState.ROUND_END) {
            startHold(r.arenaId);
        }
        debug("掉线记录 " + r.name + " @ " + r.arenaId + "，经济 " + r.money
                + "，战绩 " + r.kills + "/" + r.deaths + "，本次等待 " + covered.size() + " 人");
    }

    /**
     * 把这次掉线纳入等待。
     *
     * <p>名额按阵营算：只要他所在的阵营这一场还没用过等待，就消耗掉；
     * 而只要此刻已经有一次等待在进行，本次掉线就直接并进去——
     * 一波掉几个人不重复消耗名额，也不会让后来掉的人被关在门外。
     *
     * @return 本次等待的玩家集合；不该为他停表时返回 null
     */
    private Set<UUID> cover(Reconnect r) {
        boolean fresh = claim(r.arenaId, r.team);

        Set<UUID> existing = waiting.get(r.arenaId);
        if (existing != null) {
            return existing;
        }
        if (!fresh) {
            return null;
        }
        Set<UUID> created = ConcurrentHashMap.newKeySet();
        Set<UUID> previous = waiting.putIfAbsent(r.arenaId, created);
        return previous != null ? previous : created;
    }

    /** 回合打完、下一回合还没开始的这一瞬间，是唯一能把人塞回去的位置。 */
    @EventHandler
    public void onRoundEnd(WbdRoundEndEvent event) {
        String arenaId = event.getArenaId();
        if (!waiting.containsKey(arenaId)) {
            return;
        }
        if (!hasPending(arenaId)) {
            waiting.remove(arenaId);
            return;
        }
        startHold(arenaId);
    }

    /** 回合真的开始了：说明等待已被绕过（管理员恢复 / 超时），不再重复守。 */
    @EventHandler
    public void onRoundStart(WbdRoundStartEvent event) {
        String arenaId = event.getArenaId();
        if (event.getRound() <= 1) {
            used.remove(arenaId);
        }
        if (waiting.containsKey(arenaId) && !holding.contains(arenaId)) {
            waiting.remove(arenaId);
            debug("等待被绕过 @" + arenaId);
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
        if (expired(r)) {
            // 超过重联窗口：按弃赛处理，记录一删，回来就是个普通玩家
            forget(r);
            player.sendMessage(color(noticePrefix() + "&7你已超过重连时限，本场按弃赛处理。"));
            player.sendMessage(color("&8  &7重新加入请使用 &f/wbd join"));
            debug("超过重联窗口，放弃 " + r.name);
            return;
        }
        if (!autoRejoin) {
            forgive(r);
            return;
        }

        ArenaSession session = session(r.arenaId);
        if (session == null || session.getState() == GameState.WAITING) {
            forgive(r);
            return;
        }
        // 已经自己挤进别的房间了，就别硬拉回来
        if (plugin.getApi() != null && plugin.getApi().getModeFor(player) != null) {
            forgive(r);
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
            forgive(r);
            player.sendMessage(color("&c重连失败：原比赛已经无法加入。"));
            return;
        }

        restore(session, player, r);
        forgive(r);

        broadcast(r.arenaId, notice("&a" + r.name + " &7已重连，归队 &f"
                + teamLabel(r.team) + " &8(&7经济与战绩已恢复&8)"));
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

    private void startHold(String arenaId) {
        List<String> names = namesOf(arenaId);
        if (names.isEmpty()) {
            waiting.remove(arenaId);
            return;
        }
        TechnicalPauseModule pause = pauseModule();
        if (pause == null
                || !pause.startReconnectHold(arenaId, label(names), holdMaxSeconds)) {
            waiting.remove(arenaId);
            debug("开始等待失败（可能已经在暂停中）：" + arenaId);
            return;
        }
        holding.add(arenaId);
        debug("开始等待重连 " + names + " @ " + arenaId);
    }

    /**
     * 人回来了（或者不打算再等他）：把他从名单里划掉。
     * 名单空了才真正恢复比赛——所以两个人一起掉、回来一个，比赛不会提前开。
     */
    private void forgive(Reconnect r) {
        pending.remove(r.uuid, r);
        Set<UUID> set = waiting.get(r.arenaId);
        if (set != null) {
            set.remove(r.uuid);
        }
        if (!hasPending(r.arenaId)) {
            endHold(r.arenaId, "玩家已重连");
        } else {
            updateLabel(r.arenaId);
        }
    }

    /** 彻底不再等他（超出重联窗口 / 比赛结束）。 */
    private void forget(Reconnect r) {
        pending.remove(r.uuid, r);
        Set<UUID> set = waiting.get(r.arenaId);
        if (set != null) {
            set.remove(r.uuid);
        }
        if (!hasPending(r.arenaId)) {
            endHold(r.arenaId, "已按弃赛处理");
        } else {
            updateLabel(r.arenaId);
        }
    }

    private void endHold(String arenaId, String reason) {
        waiting.remove(arenaId);
        if (holding.remove(arenaId)) {
            TechnicalPauseModule pause = pauseModule();
            if (pause != null) {
                pause.endReconnectHold(arenaId, reason);
            }
        }
    }

    /** 名单变了就刷新顶栏，让人知道还在等谁。 */
    private void updateLabel(String arenaId) {
        if (!holding.contains(arenaId)) {
            return;
        }
        TechnicalPauseModule pause = pauseModule();
        if (pause != null) {
            pause.updateReconnectHold(arenaId, label(namesOf(arenaId)));
        }
    }

    /**
     * 每秒核对一次。
     *
     * <p>三件事：清理超过重联窗口的记录；等待被超时 / 管理员 / 投票结束了就收干净；
     * 以及刷新顶栏上还在等的人。
     */
    private void tick() {
        expirePending();

        if (holding.isEmpty()) {
            return;
        }
        TechnicalPauseModule pause = pauseModule();
        for (String arenaId : new ArrayList<>(holding)) {
            if (pause == null || !pause.isHolding(arenaId)) {
                holding.remove(arenaId);
                Reconnect r = firstPending(arenaId);
                waiting.remove(arenaId);
                broadcast(arenaId, notice("&7等待超时"
                        + (r == null ? "" : " — &f" + r.name + " &7未重返，比赛继续")));
                debug("等待结束 @" + arenaId);
                continue;
            }
            updateLabel(arenaId);
        }
    }

    /** 清理超过 {@code pending-max-seconds} 还没回来的记录。 */
    private void expirePending() {
        if (pending.isEmpty()) {
            return;
        }
        for (Reconnect r : new ArrayList<>(pending.values())) {
            if (!expired(r) || !pending.remove(r.uuid, r)) {
                continue;
            }
            Set<UUID> set = waiting.get(r.arenaId);
            if (set != null) {
                set.remove(r.uuid);
            }
            if (!hasPending(r.arenaId)) {
                endHold(r.arenaId, "已按弃赛处理");
            } else {
                updateLabel(r.arenaId);
            }
            debug("超过重联窗口，放弃等待 " + r.name + " @ " + r.arenaId);
        }
    }

    // ------------------------------------------------------------------
    // 名单
    // ------------------------------------------------------------------

    /** 本次等待里还在掉线状态的名字。 */
    private List<String> namesOf(String arenaId) {
        List<String> names = new ArrayList<>();
        Set<UUID> set = waiting.get(arenaId);
        if (set == null) {
            return names;
        }
        for (UUID id : set) {
            Reconnect r = pending.get(id);
            if (r != null) {
                names.add(r.name);
            }
        }
        return names;
    }

    private boolean hasPending(String arenaId) {
        Set<UUID> set = waiting.get(arenaId);
        if (set == null || set.isEmpty()) {
            return false;
        }
        for (UUID id : set) {
            if (pending.containsKey(id)) {
                return true;
            }
        }
        return false;
    }

    private Reconnect firstPending(String arenaId) {
        Set<UUID> set = waiting.get(arenaId);
        if (set == null) {
            return null;
        }
        for (UUID id : set) {
            Reconnect r = pending.get(id);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    /** {@code AlphaHu} / {@code AlphaHu 等 3 人}。 */
    private String label(List<String> names) {
        if (names.isEmpty()) {
            return "玩家";
        }
        if (names.size() == 1) {
            return names.get(0);
        }
        return names.get(0) + " 等 " + names.size() + " 人";
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 领取本队本场的重连等待名额。 */
    private boolean claim(String arenaId, Team team) {
        return used.computeIfAbsent(arenaId, k -> ConcurrentHashMap.newKeySet()).add(team);
    }

    /** 掉线后是否已经超过重联窗口（0 表示不限）。 */
    private boolean expired(Reconnect r) {
        return pendingMaxSeconds > 0
                && System.currentTimeMillis() - r.createdAt > pendingMaxSeconds * 1000L;
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

    /** 统一的比赛公告前缀。 */
    private String noticePrefix() {
        return "&8[&6比赛&8] ";
    }

    private String notice(String body) {
        return noticePrefix() + body;
    }

    private String teamLabel(Team team) {
        if (team == Team.T) return "T 方";
        if (team == Team.CT) return "CT 方";
        return "观战";
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
        /** 掉线时刻：重联窗口从这里开始算。 */
        final long createdAt = System.currentTimeMillis();

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
