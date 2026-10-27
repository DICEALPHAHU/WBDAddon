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
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
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
 * <h2>能真正做到「停表」</h2>
 *
 * <p>WarZBombDefuse 的回合、冻结倒计时、C4 倒计时都由 {@code ArenaSession.tickTask}
 * 这一个每秒 20 次的 tick 推进。暂停时通过反射拿到该字段并 {@code cancel()}，
 * 恢复时调用公开的 {@code startTasks()} 重建 —— 计时器会从暂停时的值继续，
 * 而不是带着暂停期间的时间继续往前跑。
 *
 * <p>如果反射失败（WBD 改字段名），会退化成「只冻结玩家、计时器继续走」，
 * 并在控制台打一条警告，不会因此把整局卡死。
 *
 * <h2>暂停期间</h2>
 *
 * <ul>
 *   <li>参赛玩家不能移动（允许转视角）</li>
 *   <li>不结算伤害、不能丢物品、不能交互/破坏/放置</li>
 *   <li>ActionBar 显示剩余秒数，时间到自动恢复</li>
 * </ul>
 *
 * <h2>两种发起方式</h2>
 *
 * <ul>
 *   <li>管理员：{@code /wbdaddon pause [竞技场] [秒数]}，不限次数</li>
 *   <li>玩家：{@code /pause}，每队每场限 {@code team-count} 次</li>
 * </ul>
 *
 * @author AlphaHu
 */
public class TechnicalPauseModule implements AddonModule, Listener {

    private final WBDAddon plugin;

    /** 按竞技场 ID 记录当前暂停；一个竞技场同时只允许一个暂停。 */
    private final Map<String, Pause> pauses = new ConcurrentHashMap<>();

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
        teamUses.clear();
        HandlerList.unregisterAll(this);
        plugin.getLogger().info("技术暂停已卸载。");
    }

    // ------------------------------------------------------------------
    // 对外入口
    // ------------------------------------------------------------------

    /** 玩家 /pause：每队每场一次。 */
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
        if (!pause(arenaId, session, teamId, teamSeconds, label)) {
            teamUses.put(key, used);
            player.sendMessage(color("&c暂停失败。"));
            return;
        }
        player.sendMessage(color("&a已请求技术暂停，本队剩余 "
                + (teamCount - used - 1) + " 次。"));
    }

    /** 管理员 /wbdaddon pause [竞技场] [秒数]。 */
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
        if (!pause(arenaId, session, null, secs, "管理员")) {
            sender.sendMessage(color("&c暂停失败。"));
            return;
        }
        sender.sendMessage(color("&a已暂停竞技场 &e" + arenaId + " &a，&e" + secs + " &a秒。"));
    }

    /** 管理员 /wbdaddon resume [竞技场]。 */
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

        if (!resume(arenaId, "管理员恢复")) {
            sender.sendMessage(color("&c竞技场 " + arenaId + " 当前没有暂停。"));
            return;
        }
        sender.sendMessage(color("&a已恢复竞技场 &e" + arenaId + "&a。"));
    }

    public boolean isPaused(String arenaId) {
        return pauses.containsKey(arenaId);
    }

    // ------------------------------------------------------------------
    // 暂停 / 恢复
    // ------------------------------------------------------------------

    private boolean pause(String arenaId, ArenaSession session, String teamId,
                          int seconds, String label) {
        boolean timersFrozen = freezeTimers && cancelTick(session);

        Pause p = new Pause(session, arenaId, teamId,
                System.currentTimeMillis() + seconds * 1000L, timersFrozen);
        pauses.put(arenaId, p);

        if (freezePlayers) {
            freezeArena(arenaId, p);
        }

        broadcast(arenaId, "&e[技术暂停] &f" + label + " 请求暂停，&e" + seconds + " &f秒。");
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
        if (p.timersFrozen) {
            try {
                p.session.startTasks();
            } catch (Throwable t) {
                plugin.getLogger().warning("技术暂停：恢复 WBD tick 失败：" + t);
            }
        }
        p.frozen.clear();

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
            resume(p.arenaId, "时间到");
            return;
        }
        int seconds = (int) ((remaining + 999) / 1000);
        String text = color(pausedSubtitle
                .replace("{team}", teamLabel(p.teamId))
                .replace("{seconds}", String.valueOf(seconds)));

        for (UUID id : p.frozen) {
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) continue;
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(text));
        }
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
    // 冻结
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
        }
    }

    private String teamLabel(String teamId) {
        if (teamId == null) return "管理员";
        return "T".equals(teamId) ? "T 方" : "CT 方";
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

    /** 暂停期间进场的人也要一起冻住。 */
    @EventHandler
    public void onJoin(WbdPlayerJoinArenaEvent event) {
        Pause p = pauses.get(event.getArenaId());
        if (p == null) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (pauses.get(event.getArenaId()) == p
                    && inArena(event.getPlayer(), event.getArenaId())) {
                p.frozen.add(event.getPlayer().getUniqueId());
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

    /** 新比赛开始时重置每队暂停次数。 */
    @EventHandler
    public void onRoundStart(WbdRoundStartEvent event) {
        if (event.getRound() <= 1) {
            resetTeamUses(event.getArenaId());
        }
    }

    @EventHandler
    public void onMatchEnd(WbdMatchEndEvent event) {
        resetTeamUses(event.getArenaId());
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

    private static final class Pause {
        final ArenaSession session;
        final String arenaId;
        final String teamId;
        final long endMillis;
        final boolean timersFrozen;
        final Set<UUID> frozen = ConcurrentHashMap.newKeySet();
        BukkitTask ticker;

        Pause(ArenaSession session, String arenaId, String teamId,
              long endMillis, boolean timersFrozen) {
            this.session = session;
            this.arenaId = arenaId;
            this.teamId = teamId;
            this.endMillis = endMillis;
            this.timersFrozen = timersFrozen;
        }
    }
}
