package alphahu.wbdaddon.module;

import alphahu.wbdaddon.WBDAddon;
import alphahu.wbdaddon.api.AddonMode;
import alphahu.wbdaddon.mode.BombDefuseMode;
import com.warz.bombdefuse.api.WbdMatchEndEvent;
import com.warz.bombdefuse.api.WbdPlayerJoinArenaEvent;
import com.warz.bombdefuse.api.WbdPlayerLeaveArenaEvent;
import com.warz.bombdefuse.api.WbdRoundStartEvent;
import com.warz.bombdefuse.arena.ArenaSession;
import com.warz.bombdefuse.model.GameState;
import com.warz.bombdefuse.scheduler.TaskHandle;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 技术暂停（类似 CS 的 tactical timeout）。
 *
 * <h2>CS 式时机</h2>
 *
 * <p>玩家 {@code /pause} 之后，<b>当前回合继续打完</b>；请求会被记下来，
 * 等下一回合开始的 {@link WbdRoundStartEvent} 才真正暂停。
 * 这样不会把正在交火的回合拦腰打断，也符合「一方叫了暂停，下一回合开局停」的习惯。
 *
 * <p>管理员 {@code /wbdaddon pause} 是即时暂停，用于处理突发技术问题；
 * 两者用的是同一套 BossBar 倒计时与恢复逻辑。
 *
 * <h2>能真正做到「停表」</h2>
 *
 * <p>WBD 的回合、冻结倒计时、C4 倒计时都由 {@code ArenaSession.tickTask} 推进。
 * 暂停时反射拿到该字段并 {@code cancel()}，恢复时调用公开的 {@code startTasks()}，
 * 计时器会从暂停时的值继续。反射失败时退化为只冻结玩家，并打警告。
 *
 * <h2>显示</h2>
 *
 * <ul>
 *   <li>顶部 BossBar：{@code 技术暂停 00:59}</li>
 *   <li>聊天栏：请求时「T 方 / CT 方 发起了技术暂停」</li>
 *   <li>暂停期间玩家不能移动（允许转视角）、不结算伤害、不能交互/破坏/放置</li>
 * </ul>
 *
 * @author AlphaHu
 */
public class TechnicalPauseModule implements AddonModule, Listener {

    private final WBDAddon plugin;

    /** 当前正在进行的暂停，key = 竞技场 ID。 */
    private final Map<String, Pause> pauses = new ConcurrentHashMap<>();

    /** 已请求、等待下一回合开始的暂停。 */
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    /** 每队已用掉的玩家暂停次数，key = arenaId + "|" + teamId。 */
    private final Map<String, Integer> teamUses = new ConcurrentHashMap<>();

    private Field tickTaskField;

    private boolean freezeTimers;
    private boolean freezePlayers;
    private int adminDefaultSeconds;
    private int adminMaxSeconds;
    private int teamCount;
    private int teamSeconds;
    private String pausedTitle;
    private String pausedSubtitle;
    private String resumedTitle;
    private String holdTitle;
    private String holdResumedTitle;

    public TechnicalPauseModule(WBDAddon plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getId() {
        return "technical-pause";
    }

    @Override
    public String getDisplayName() {
        return "技术暂停";
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }

    @Override
    public void onEnable() {
        this.freezeTimers = plugin.getConfig()
                .getBoolean("modules.technical-pause.freeze-timers", true);
        this.freezePlayers = plugin.getConfig()
                .getBoolean("modules.technical-pause.freeze-players", true);
        this.adminDefaultSeconds = Math.max(5, plugin.getConfig()
                .getInt("modules.technical-pause.admin-default-seconds", 60));
        this.adminMaxSeconds = Math.max(this.adminDefaultSeconds, plugin.getConfig()
                .getInt("modules.technical-pause.admin-max-seconds", 300));
        this.teamCount = Math.max(0, plugin.getConfig()
                .getInt("modules.technical-pause.team-count", 1));
        this.teamSeconds = Math.max(5, plugin.getConfig()
                .getInt("modules.technical-pause.team-seconds", 60));
        this.pausedTitle = plugin.getConfig()
                .getString("modules.technical-pause.paused-title", "&e技术暂停");
        this.pausedSubtitle = plugin.getConfig()
                .getString("modules.technical-pause.paused-subtitle",
                        "&7{team} 请求暂停，剩余 {seconds} 秒");
        this.resumedTitle = plugin.getConfig()
                .getString("modules.technical-pause.resumed-title", "&a比赛继续");
        this.holdTitle = plugin.getConfig()
                .getString("modules.technical-pause.hold-title", "&b等待重连");
        this.holdResumedTitle = plugin.getConfig()
                .getString("modules.technical-pause.hold-resumed-title", "&a玩家已重连，比赛继续");

        resolveTickTaskField();
        Bukkit.getPluginManager().registerEvents(this, plugin);

        plugin.getLogger().info("技术暂停已启动：管理员不限次，每队 "
                + teamCount + " 次，默认 " + teamSeconds + " 秒"
                + (freezeTimers && tickTaskField != null ? "，计时器会一起冻结" : "，计时器不冻结")
                + "。");
    }

    @Override
    public void onDisable() {
        for (String arenaId : pauses.keySet().toArray(new String[0])) {
            resume(arenaId, "插件卸载");
        }
        pauses.clear();
        pending.clear();
        teamUses.clear();
        HandlerList.unregisterAll(this);
        plugin.getLogger().info("技术暂停已卸载。");
    }

    // ------------------------------------------------------------------
    // 对外入口
    // ------------------------------------------------------------------

    /** 玩家 /pause：只预约，当前回合继续，下一回合开始才暂停。 */
    public void requestTeamPause(Player player) {
        if (teamCount <= 0) {
            player.sendMessage(color("&c本服没有开放玩家暂停。"));
            return;
        }
        if (plugin.getApi() == null) return;

        AddonMode mode = plugin.getApi().getModeFor(player);
        if (mode == null || !BombDefuseMode.MODE_ID.equals(mode.getModeId())
                || !mode.isPlaying(player)) {
            player.sendMessage(color("&c你不在爆破模式的比赛中。"));
            return;
        }

        String arenaId = mode.getArenaId(player);
        String teamId = mode.getTeamId(player);
        if (!"T".equals(teamId) && !"CT".equals(teamId)) {
            player.sendMessage(color("&c只有参赛的 T / CT 可以请求暂停。"));
            return;
        }
        if (pauses.containsKey(arenaId)) {
            player.sendMessage(color("&c当前已经处于技术暂停中。"));
            return;
        }
        if (pending.containsKey(arenaId)) {
            player.sendMessage(color("&c已经有一个待生效的技术暂停了。"));
            return;
        }

        ArenaSession session = session(arenaId);
        if (session == null) {
            player.sendMessage(color("&c找不到竞技场 " + arenaId + " 的会话。"));
            return;
        }
        GameState state = session.getState();
        if (state != GameState.LIVE && state != GameState.FREEZE) {
            player.sendMessage(color("&c只能在回合进行中或冻结时间请求暂停。"));
            return;
        }

        String key = arenaId + "|" + teamId;
        int used = teamUses.getOrDefault(key, 0);
        if (used >= teamCount) {
            player.sendMessage(color("&c你们队本场已经没有暂停次数了。"));
            return;
        }
        teamUses.put(key, used + 1);

        String label = "T".equals(teamId) ? "T 方" : "CT 方";
        pending.put(arenaId, new Pending(teamId, teamSeconds, label));

        broadcast(arenaId, "&e[技术暂停] &f" + label + " 发起了技术暂停"
                + "&7（下一回合开始时生效）");
        player.sendMessage(color("&a已预约技术暂停，本队剩余 "
                + (teamCount - used - 1) + " 次。"));
    }

    /** 管理员 /wbdaddon pause [竞技场] [秒数]：即时暂停。 */
    public void requestAdminPause(CommandSender sender, String arenaId, Integer seconds) {
        if (arenaId == null) {
            if (!(sender instanceof Player player) || plugin.getApi() == null) {
                sender.sendMessage(color("&c控制台需要指定竞技场：/wbdaddon pause <竞技场ID> [秒数]"));
                return;
            }
            AddonMode mode = plugin.getApi().getModeFor(player);
            if (mode == null || !mode.isPlaying(player)) {
                sender.sendMessage(color("&c你不在竞技场里，请指定竞技场：/wbdaddon pause <竞技场ID> [秒数]"));
                return;
            }
            arenaId = mode.getArenaId(player);
        }

        ArenaSession session = session(arenaId);
        if (session == null) {
            sender.sendMessage(color("&c找不到竞技场 " + arenaId + "。"));
            return;
        }
        if (pauses.containsKey(arenaId)) {
            sender.sendMessage(color("&c竞技场 " + arenaId + " 已经处于暂停中。"));
            return;
        }

        int secs = seconds == null ? adminDefaultSeconds : seconds;
        secs = Math.max(5, Math.min(adminMaxSeconds, secs));
        if (!startPause(arenaId, session, null, secs, "管理员")) {
            sender.sendMessage(color("&c暂停失败。"));
            return;
        }
        sender.sendMessage(color("&a已暂停竞技场 &e" + arenaId + " &a，&e" + secs + " &a秒。"));
    }

    /** 管理员 /wbdaddon resume [竞技场]：恢复，或取消尚未生效的预约。 */
    public void requestAdminResume(CommandSender sender, String arenaId) {
        if (arenaId == null) {
            if (!(sender instanceof Player player) || plugin.getApi() == null) {
                sender.sendMessage(color("&c控制台需要指定竞技场：/wbdaddon resume <竞技场ID>"));
                return;
            }
            AddonMode mode = plugin.getApi().getModeFor(player);
            if (mode == null || !mode.isPlaying(player)) {
                sender.sendMessage(color("&c你不在竞技场里，请指定竞技场：/wbdaddon resume <竞技场ID>"));
                return;
            }
            arenaId = mode.getArenaId(player);
        }

        Pending cancelled = pending.remove(arenaId);
        if (cancelled != null) {
            refund(arenaId, cancelled.teamId);
            broadcast(arenaId, "&e[技术暂停] &f已取消待生效的暂停。");
            sender.sendMessage(color("&a已取消竞技场 &e" + arenaId + " &a的暂停预约。"));
            return;
        }

        if (!resume(arenaId, "管理员恢复")) {
            sender.sendMessage(color("&c竞技场 " + arenaId + " 当前没有暂停。"));
            return;
        }
        sender.sendMessage(color("&a已恢复竞技场 &e" + arenaId + "&a。"));
    }

    public boolean isPaused(String arenaId) {
        return pauses.containsKey(arenaId);
    }

    /**
     * 外部（例如 {@code /unpause} 投票通过）请求提前恢复。
     *
     * <p>与管理员 {@code /wbdaddon resume} 的区别：这里只管把正在进行的暂停收掉，
     * 不去动「已预约、等下一回合生效」的那份暂停。
     *
     * @return 确实恢复了一场暂停返回 true；本来就没在暂停返回 false
     */
    public boolean forceResume(String arenaId, String reason) {
        return resume(arenaId, reason);
    }

    // ------------------------------------------------------------------
    // 断线重联等待
    // ------------------------------------------------------------------

    /**
     * 掉线等待：把比赛定住，等某名玩家重新连回来。
     *
     * <p>和普通技术暂停用的是同一套「停 WBD tick + 冻结玩家」的机制，
     * 区别在于它<b>没有固定时长</b>——只有重连（{@link #endReconnectHold}）、
     * 管理员恢复或到达上限才会继续。顶栏显示的是「等待重连」而不是倒计时。
     *
     * @param playerName  掉线玩家的名字，只用于显示
     * @param maxSeconds  最长等待秒数；{@code <= 0} 表示不限时（内部按 1 小时兜底）
     * @return 已经处于暂停中、或竞技场不存在时返回 false
     */
    public boolean startReconnectHold(String arenaId, String playerName, int maxSeconds) {
        if (pauses.containsKey(arenaId)) {
            return false;
        }
        ArenaSession session = session(arenaId);
        if (session == null) {
            return false;
        }
        int seconds = maxSeconds <= 0 ? 3600 : Math.max(30, maxSeconds);
        boolean timersFrozen = freezeTimers && cancelTick(session);

        Pause p = new Pause(session, arenaId, null, seconds,
                System.currentTimeMillis() + seconds * 1000L, timersFrozen);
        p.hold = true;
        p.holdName = playerName;
        p.bar = Bukkit.createBossBar(holdBarTitle(playerName, seconds),
                BarColor.BLUE, BarStyle.SOLID);
        p.bar.setProgress(1.0);

        pauses.put(arenaId, p);
        if (freezePlayers) {
            freezeArena(arenaId, p);
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (inArena(player, arenaId)) {
                p.bar.addPlayer(player);
            }
        }

        broadcast(arenaId, "&8[&6比赛&8] &7比赛暂停 — 等待 &f" + playerName
                + " &7重连 &8(&7上限 " + clock(seconds) + "&8)");
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!inArena(player, arenaId)) continue;
            player.sendTitle(color(holdTitle), color("&7等待 &f" + playerName + " &7重连"),
                    10, 70, 20);
        }

        p.ticker = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(p), 0L, 20L);
        if (plugin.getConfig().getBoolean("modules.technical-pause.debug", false)) {
            plugin.getLogger().info("[technical-pause] " + arenaId + " 等待重连 " + playerName
                    + "，上限 " + seconds + " 秒，计时器冻结=" + timersFrozen);
        }
        return true;
    }

    /** 重连等待结束（玩家回来了）。不是等待中的暂停时返回 false。 */
    public boolean endReconnectHold(String arenaId, String reason) {
        Pause p = pauses.get(arenaId);
        if (p == null || !p.hold) {
            return false;
        }
        return resume(arenaId, reason);
    }

    /** 该竞技场此刻是否正在等待重连。 */
    public boolean isHolding(String arenaId) {
        Pause p = pauses.get(arenaId);
        return p != null && p.hold;
    }

    /**
     * 刷新等待重连的显示文本。
     *
     * <p>多人同时掉线时，名单会随着有人回来而变短，顶栏要跟着改
     * （{@code AlphaHu 等 3 人} → {@code Steve 等 2 人}）。
     */
    public void updateReconnectHold(String arenaId, String label) {
        Pause p = pauses.get(arenaId);
        if (p == null || !p.hold || label == null || label.isBlank()) {
            return;
        }
        p.holdName = label;
        long remaining = p.endMillis - System.currentTimeMillis();
        if (p.bar != null && remaining > 0) {
            p.bar.setTitle(holdBarTitle(label, (int) ((remaining + 999) / 1000)));
        }
    }

    // ------------------------------------------------------------------
    // 暂停 / 恢复
    // ------------------------------------------------------------------

    private boolean startPause(String arenaId, ArenaSession session, String teamId,
                               int seconds, String label) {
        boolean timersFrozen = freezeTimers && cancelTick(session);

        Pause p = new Pause(session, arenaId, teamId, seconds,
                System.currentTimeMillis() + seconds * 1000L, timersFrozen);
        p.bar = Bukkit.createBossBar(color(pausedTitle) + " " + clock(seconds),
                BarColor.RED, BarStyle.SOLID);
        p.bar.setProgress(1.0);

        pauses.put(arenaId, p);
        if (freezePlayers) {
            freezeArena(arenaId, p);
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (inArena(player, arenaId)) {
                p.bar.addPlayer(player);
            }
        }

        broadcast(arenaId, "&e[技术暂停] &f" + label + " 发起了技术暂停，"
                + "&e" + seconds + " &f秒后继续。");
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!inArena(player, arenaId)) continue;
            player.sendTitle(color(pausedTitle),
                    color(pausedSubtitle
                            .replace("{team}", label)
                            .replace("{seconds}", String.valueOf(seconds))),
                    10, 70, 20);
        }

        p.ticker = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(p), 0L, 20L);
        if (plugin.getConfig().getBoolean("modules.technical-pause.debug", false)) {
            plugin.getLogger().info("[technical-pause] " + arenaId + " 暂停 " + seconds
                    + " 秒，计时器冻结=" + timersFrozen + "，冻结玩家=" + p.frozen.size());
        }
        return true;
    }

    private boolean resume(String arenaId, String reason) {
        Pause p = pauses.remove(arenaId);
        if (p == null) return false;

        if (p.ticker != null) {
            p.ticker.cancel();
        }
        if (p.bar != null) {
            p.bar.setVisible(false);
            p.bar.removeAll();
        }
        if (p.timersFrozen) {
            try {
                p.session.startTasks();
            } catch (Throwable t) {
                plugin.getLogger().warning("技术暂停：恢复 WBD tick 失败：" + t);
            }
        }
        p.frozen.clear();

        if (p.hold) {
            broadcast(arenaId, "&8[&6比赛&8] &a" + p.holdName + " &7已重连 — 比赛继续"
                    + ("等待超时".equals(reason) ? " &8(&7等待超时&8)" : ""));
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!inArena(player, arenaId)) continue;
                player.sendTitle(color(holdResumedTitle), "", 5, 30, 10);
            }
            return true;
        }

        broadcast(arenaId, "&a[技术暂停] 比赛继续&7（" + reason + "）。");
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!inArena(player, arenaId)) continue;
            player.sendTitle(color(resumedTitle), "", 5, 30, 10);
        }
        return true;
    }

    private void tick(Pause p) {
        long remaining = p.endMillis - System.currentTimeMillis();
        if (remaining <= 0) {
            resume(p.arenaId, p.hold ? "等待超时" : "时间到");
            return;
        }
        int seconds = (int) ((remaining + 999) / 1000);
        if (p.bar != null) {
            p.bar.setTitle(p.hold
                    ? holdBarTitle(p.holdName, seconds)
                    : color(pausedTitle) + " " + clock(seconds));
            p.bar.setProgress(Math.max(0.0,
                    Math.min(1.0, remaining / (double) (p.totalSeconds * 1000L))));
        }
    }

    /** 等待重连的顶部标题：{@code 等待重连 | AlphaHu | 剩余 04:12}。 */
    private String holdBarTitle(String playerName, int seconds) {
        return color(holdTitle) + " &7| &f" + playerName + " &7| 剩余 &e" + clock(seconds);
    }

    private boolean cancelTick(ArenaSession session) {
        if (tickTaskField == null) return false;
        try {
            Object handle = tickTaskField.get(session);
            if (handle instanceof TaskHandle task) {
                task.cancel();
                return true;
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("技术暂停：停止 WBD tick 失败：" + t);
        }
        return false;
    }

    private void resolveTickTaskField() {
        try {
            tickTaskField = ArenaSession.class.getDeclaredField("tickTask");
            tickTaskField.setAccessible(true);
        } catch (Throwable t) {
            tickTaskField = null;
            plugin.getLogger().warning("技术暂停：无法定位 ArenaSession.tickTask，"
                    + "freeze-timers 将退化为只冻结玩家（回合 / C4 计时继续走）。");
        }
    }

    // ------------------------------------------------------------------
    // 冻结 / 工具
    // ------------------------------------------------------------------

    private void freezeArena(String arenaId, Pause p) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (inArena(player, arenaId)) {
                p.frozen.add(player.getUniqueId());
            }
        }
    }

    private boolean isFrozen(Entity entity) {
        if (!(entity instanceof Player player)) return false;
        for (Pause p : pauses.values()) {
            if (p.frozen.contains(player.getUniqueId())) {
                return true;
            }
        }
        return false;
    }

    private void removeFrozen(Player player) {
        for (Pause p : pauses.values()) {
            p.frozen.remove(player.getUniqueId());
            if (p.bar != null) {
                p.bar.removePlayer(player);
            }
        }
    }

    private void refund(String arenaId, String teamId) {
        if (teamId == null) return;
        String key = arenaId + "|" + teamId;
        teamUses.computeIfPresent(key, (k, v) -> v > 1 ? v - 1 : null);
    }

    private String clock(int seconds) {
        return String.format("%02d:%02d", seconds / 60, seconds % 60);
    }

    // ------------------------------------------------------------------
    // 事件
    // ------------------------------------------------------------------

    /** 冻结移动：允许转头，位置锁在暂停瞬间。 */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!isFrozen(event.getPlayer())) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;
        event.setTo(new Location(from.getWorld(),
                from.getX(), from.getY(), from.getZ(), to.getYaw(), to.getPitch()));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        if (isFrozen(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (isFrozen(event.getDamager())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (isFrozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (isFrozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (isFrozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (isFrozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** 暂停期间进场的人也要一起冻住，并加进 BossBar。 */
    @EventHandler
    public void onJoin(WbdPlayerJoinArenaEvent event) {
        Pause p = pauses.get(event.getArenaId());
        if (p == null) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (pauses.get(event.getArenaId()) == p
                    && inArena(event.getPlayer(), event.getArenaId())) {
                p.frozen.add(event.getPlayer().getUniqueId());
                if (p.bar != null) {
                    p.bar.addPlayer(event.getPlayer());
                }
            }
        });
    }

    @EventHandler
    public void onLeave(WbdPlayerLeaveArenaEvent event) {
        removeFrozen(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        removeFrozen(event.getPlayer());
    }

    /** 回合开始：重置每队次数，并启动预约的暂停。 */
    @EventHandler
    public void onRoundStart(WbdRoundStartEvent event) {
        if (event.getRound() <= 1) {
            resetTeamUses(event.getArenaId());
        }

        Pending pendingPause = pending.remove(event.getArenaId());
        if (pendingPause == null) return;

        ArenaSession session = session(event.getArenaId());
        if (session == null) return;
        startPause(event.getArenaId(), session, pendingPause.teamId,
                pendingPause.seconds, pendingPause.label);
    }

    @EventHandler
    public void onMatchEnd(WbdMatchEndEvent event) {
        resetTeamUses(event.getArenaId());
        Pending pendingPause = pending.remove(event.getArenaId());
        if (pendingPause != null) {
            refund(event.getArenaId(), pendingPause.teamId);
        }
    }

    private void resetTeamUses(String arenaId) {
        String prefix = arenaId + "|";
        teamUses.keySet().removeIf(key -> key.startsWith(prefix));
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private ArenaSession session(String arenaId) {
        if (plugin.getWbd() == null || plugin.getWbd().getArenaManager() == null) {
            return null;
        }
        return plugin.getWbd().getArenaManager().getSession(arenaId);
    }

    private boolean inArena(Player player, String arenaId) {
        if (plugin.getApi() == null) return false;
        AddonMode mode = plugin.getApi().getModeFor(player);
        if (mode == null || !BombDefuseMode.MODE_ID.equals(mode.getModeId())) return false;
        if (!mode.isPlaying(player)) return false;
        return arenaId.equals(mode.getArenaId(player));
    }

    private void broadcast(String arenaId, String message) {
        String text = color(message);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (inArena(player, arenaId)) {
                player.sendMessage(text);
            }
        }
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }

    private static final class Pending {
        final String teamId;
        final int seconds;
        final String label;

        Pending(String teamId, int seconds, String label) {
            this.teamId = teamId;
            this.seconds = seconds;
            this.label = label;
        }
    }

    private static final class Pause {
        final ArenaSession session;
        final String arenaId;
        final String teamId;
        final int totalSeconds;
        final long endMillis;
        final boolean timersFrozen;
        final Set<UUID> frozen = ConcurrentHashMap.newKeySet();
        /** 是否是「掉线等重连」的暂停：没有固定时长，顶栏文案也不同。 */
        boolean hold;
        String holdName;
        BossBar bar;
        BukkitTask ticker;

        Pause(ArenaSession session, String arenaId, String teamId, int totalSeconds,
              long endMillis, boolean timersFrozen) {
            this.session = session;
            this.arenaId = arenaId;
            this.teamId = teamId;
            this.totalSeconds = totalSeconds;
            this.endMillis = endMillis;
            this.timersFrozen = timersFrozen;
        }
    }
}
