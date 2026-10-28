package alphahu.wbdaddon.module;

import alphahu.wbdaddon.WBDAddon;
import alphahu.wbdaddon.api.AddonMode;
import com.warz.bombdefuse.api.WbdPlayerEliminatedEvent;
import com.warz.bombdefuse.arena.ArenaSession;
import com.warz.bombdefuse.arena.PlayerRecord;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 阵亡后看队友第一视角。
 *
 * <h2>做法</h2>
 *
 * <p>用原版的 {@link Player#setSpectatorTarget(org.bukkit.entity.Entity)}：
 * 死亡玩家本来就是观战模式，把它「挂」到一名存活队友身上，客户端就会切到那个人的
 * 第一视角，能看见他看的方向、开镜、走动。目标一旦阵亡或离场，自动换下一个队友。
 *
 * <h2>关于「看不到队友手里的枪」</h2>
 *
 * <p>这是原版观战模式的限制：观战视角不渲染目标的持物与手部动作，只给画面。
 * 想看到枪只有一个办法——让观战者退出观战模式（冒险模式 + 每 tick 传送到队友眼睛
 * 位置 + 复制他手里的物品），但 WBD 会持续把阵亡玩家强制设回观战模式，
 * 两种写法会互相打架、画面闪烁，所以本模块只做原版第一视角，不碰游戏模式。
 *
 * @author AlphaHu
 */
public class SpectateCameraModule implements AddonModule, Listener {

    private final WBDAddon plugin;

    /** 观战者 -> 上一次挂载的目标，用来判断要不要重挂。 */
    private final Map<UUID, UUID> attached = new ConcurrentHashMap<>();

    /** arenaId|team -> 最后一名阵亡队友的倒下位置。 */
    private final Map<String, DeathSpot> lastDeath = new ConcurrentHashMap<>();

    /** 已经因为「全队阵亡」被拉过去一次的观战者。 */
    private final Set<UUID> wipeTeleported = ConcurrentHashMap.newKeySet();

    /** 左/右键切换的防抖。 */
    private final Map<UUID, Long> lastSwitch = new ConcurrentHashMap<>();

    /** 观战者 -> 顶部「战地记录仪」BossBar。 */
    private final Map<UUID, org.bukkit.boss.BossBar> bars = new ConcurrentHashMap<>();

    /** 刚阵亡、还在「先看自己倒下的地方」缓冲期的观战者。 */
    private final Set<UUID> holding = ConcurrentHashMap.newKeySet();

    /** 玩家最近一次位置（挨打时更新），阵亡时用来还原死亡视角。 */
    private final Map<UUID, Location> lastLocation = new ConcurrentHashMap<>();

    private BukkitTask task;
    private long intervalTicks;
    private boolean auto;
    private boolean onlyTeammates;
    private boolean hud;
    private boolean keySwitch;
    private boolean deathCamera;
    private long deathHoldTicks;
    private String barTitle;
    private org.bukkit.boss.BarColor barColor;

    public SpectateCameraModule(WBDAddon plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getId() {
        return "spectate-camera";
    }

    @Override
    public String getDisplayName() {
        return "观战第一视角";
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }

    @Override
    public void onEnable() {
        this.intervalTicks = Math.max(5L, plugin.getConfig()
                .getLong("modules.spectate-camera.interval-ticks", 20L));
        this.auto = plugin.getConfig().getBoolean("modules.spectate-camera.auto", true);
        this.onlyTeammates = plugin.getConfig()
                .getBoolean("modules.spectate-camera.only-teammates", true);
        this.hud = plugin.getConfig().getBoolean("modules.spectate-camera.hud", true);
        this.barTitle = plugin.getConfig().getString("modules.spectate-camera.bar-title",
                "&6战地记录仪 &8| &7观战 &f%player% &8| &e%weapon%");
        this.barColor = parseColor(plugin.getConfig()
                .getString("modules.spectate-camera.bar-color", "YELLOW"));
        this.keySwitch = plugin.getConfig().getBoolean("modules.spectate-camera.key-switch", true);
        this.deathCamera = plugin.getConfig()
                .getBoolean("modules.spectate-camera.death-camera", true);
        this.deathHoldTicks = Math.max(0L, plugin.getConfig()
                .getLong("modules.spectate-camera.death-hold-ticks", 30L));

        Bukkit.getPluginManager().registerEvents(this, plugin);
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, intervalTicks);
        plugin.getLogger().info("观战第一视角已启动：" + (auto ? "阵亡后自动跟随存活队友" : "手动 /spectate")
                + (onlyTeammates ? "，只跟同阵营" : "") + "。");
    }

    @Override
    public void onDisable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        attached.clear();
        lastDeath.clear();
        holding.clear();
        lastLocation.clear();
        wipeTeleported.clear();
        lastSwitch.clear();
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            hideBar(viewer);
        }
        bars.clear();
        HandlerList.unregisterAll(this);
        plugin.getLogger().info("观战第一视角已卸载。");
    }

    // ------------------------------------------------------------------
    // 同步
    // ------------------------------------------------------------------

    private void tick() {
        if (plugin.getApi() == null) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getGameMode() != GameMode.SPECTATOR) {
                attached.remove(player.getUniqueId());
                hideBar(player);
                continue;
            }
            AddonMode mode = plugin.getApi().getModeFor(player);
            if (mode == null || mode.isAlive(player)) {
                attached.remove(player.getUniqueId());
                hideBar(player);
                continue;
            }

            if (holding.contains(player.getUniqueId())) {
                hideBar(player);
                continue;
            }

            Player current = spectatorTarget(player);
            if (current == null || !valid(player, mode, current)) {
                if (auto) {
                    current = pickTarget(player, mode);
                    if (current != null) {
                        attach(player, current);
                    }
                }
            }
            if (current != null) {
                attached.put(player.getUniqueId(), current.getUniqueId());
                sendHud(player, current);
            }

            // 全队阵亡：把镜头拉到最后一个队友倒下的位置（每人每次团灭只拉一次）
            if (deathCamera && !anyTeammateAlive(player, mode)) {
                if (wipeTeleported.add(player.getUniqueId())) {
                    teleportToDeathSpot(player, mode);
                }
            } else {
                wipeTeleported.remove(player.getUniqueId());
            }
        }
    }

    // ------------------------------------------------------------------
    // 事件
    // ------------------------------------------------------------------

    /** 记录每名阵亡队友倒下时的位置与朝向，供「全队阵亡」时拉镜头。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEliminated(WbdPlayerEliminatedEvent event) {
        Player victim = event.getVictim();
        if (victim == null) return;

        // WBD 在派发事件前就把人传去观战点了，所以死亡位置要用挨打时缓存的那份
        Location spot = lastLocation.get(victim.getUniqueId());
        if (spot == null) spot = victim.getLocation();
        if (spot == null) return;

        String team = event.getVictimTeam() == null ? "?" : event.getVictimTeam().name();
        lastDeath.put(event.getArenaId() + "|" + team,
                new DeathSpot(victim.getName(), spot.getWorld().getName(), spot.clone()));

        // 阵亡者本人：先把镜头留在自己倒下的地方一小会儿，再切队友
        if (!victim.isOnline()) return;
        holdOnDeathSpot(victim, spot);
    }

    /** 阵亡瞬间把镜头按在自己倒下的位置，缓冲一段时间再交给自动跟随。 */
    private void holdOnDeathSpot(Player player, Location spot) {
        if (deathHoldTicks <= 0) return;
        holding.add(player.getUniqueId());
        try {
            player.teleport(spot);
        } catch (Throwable t) {
            plugin.getLogger().warning("观战第一视角：还原死亡视角失败：" + t);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            holding.remove(player.getUniqueId());
        }, deathHoldTicks);
    }

    /** 挨打时刷新位置缓存（比每 tick 记录开销小，精度也够）。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamage(org.bukkit.event.entity.EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.getLocation() == null) return;
        lastLocation.put(player.getUniqueId(), player.getLocation().clone());
    }

    /** 左键看上一个队友、右键看下一个。 */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (!keySwitch) return;
        if (event.getAction() != Action.LEFT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_AIR) {
            return;
        }
        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.SPECTATOR) return;
        AddonMode mode = plugin.getApi() == null ? null : plugin.getApi().getModeFor(player);
        if (mode == null || mode.isAlive(player)) return;

        long now = System.currentTimeMillis();
        Long last = lastSwitch.put(player.getUniqueId(), now);
        if (last != null && now - last < 250L) return;

        boolean forward = event.getAction() == Action.RIGHT_CLICK_AIR;
        cycle(player, forward ? 1 : -1);
        event.setCancelled(true);
    }

    /**
     * 更新观战者的顶部「战地记录仪」。
     *
     * <p>用 BossBar 而不是 ActionBar：观战时屏幕上方一直挂着一条，
     * 标题是「战地记录仪 | 观战 XX | 武器」，进度条显示目标的血量比例，
     * 他被打得越残条越短，一眼看出还能不能救。
     */
    private void sendHud(Player viewer, Player target) {
        if (!hud) {
            hideBar(viewer);
            return;
        }
        String weapon;
        try {
            weapon = alphahu.wbdaddon.util.WeaponNames.resolve(
                    target, plugin.getTacZApi(), plugin.isTacZAvailable());
        } catch (Throwable t) {
            weapon = "未知";
        }
        String title = color(barTitle
                .replace("%player%", target.getName())
                .replace("%weapon%", weapon));

        org.bukkit.boss.BossBar bar = bars.computeIfAbsent(viewer.getUniqueId(), id -> {
            org.bukkit.boss.BossBar created = Bukkit.createBossBar(
                    title, barColor, org.bukkit.boss.BarStyle.SOLID);
            created.setProgress(1.0);
            created.setVisible(true);
            return created;
        });
        bar.setTitle(title);
        double max = Math.max(1.0, target.getMaxHealth());
        bar.setProgress(Math.max(0.0, Math.min(1.0, target.getHealth() / max)));
        if (!bar.getPlayers().contains(viewer)) {
            bar.addPlayer(viewer);
        }
        if (!bar.isVisible()) {
            bar.setVisible(true);
        }
    }

    private void hideBar(Player viewer) {
        org.bukkit.boss.BossBar bar = bars.remove(viewer.getUniqueId());
        if (bar == null) return;
        bar.setVisible(false);
        bar.removeAll();
    }

    private org.bukkit.boss.BarColor parseColor(String raw) {
        try {
            return org.bukkit.boss.BarColor.valueOf(
                    raw == null ? "YELLOW" : raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return org.bukkit.boss.BarColor.YELLOW;
        }
    }

    private boolean anyTeammateAlive(Player viewer, AddonMode mode) {
        String arena = mode.getArenaId(viewer);
        String team = mode.getTeamId(viewer);
        for (Player candidate : Bukkit.getOnlinePlayers()) {
            if (candidate.equals(viewer)) continue;
            AddonMode candidateMode = plugin.getApi().getModeFor(candidate);
            if (candidateMode != mode) continue;
            if (arena != null && !arena.equals(candidateMode.getArenaId(candidate))) continue;
            if (team != null && !team.equals(candidateMode.getTeamId(candidate))) continue;
            if (candidateMode.isAlive(candidate)) return true;
        }
        return false;
    }

    private void teleportToDeathSpot(Player viewer, AddonMode mode) {
        String arena = mode.getArenaId(viewer);
        String team = mode.getTeamId(viewer);
        if (arena == null || team == null) return;
        DeathSpot spot = lastDeath.get(arena + "|" + team);
        if (spot == null) return;
        if (viewer.getWorld() == null || !viewer.getWorld().getName().equals(spot.world())) {
            return;
        }
        try {
            viewer.teleport(spot.location());
            viewer.sendMessage(color("&7全队阵亡，视角已移到 &f" + spot.name() + " &7倒下的位置。"));
        } catch (Throwable t) {
            plugin.getLogger().warning("观战第一视角：传送到阵亡点失败：" + t);
        }
    }

    private Player spectatorTarget(Player player) {
        try {
            return player.getSpectatorTarget() instanceof Player target ? target : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean valid(Player viewer, AddonMode mode, Player target) {
        if (!target.isOnline() || target.getGameMode() == GameMode.SPECTATOR) return false;
        AddonMode targetMode = plugin.getApi().getModeFor(target);
        if (targetMode != mode) return false;
        if (!mode.isAlive(target)) return false;
        if (onlyTeammates) {
            String team = mode.getTeamId(viewer);
            return team != null && team.equals(targetMode.getTeamId(target));
        }
        return true;
    }

    /** 挑一个最适合跟随的队友：优先存活、距离最近。 */
    private Player pickTarget(Player viewer, AddonMode mode) {
        String team = mode.getTeamId(viewer);
        String arena = mode.getArenaId(viewer);
        Player best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Player candidate : Bukkit.getOnlinePlayers()) {
            if (candidate.equals(viewer)) continue;
            if (!valid(viewer, mode, candidate)) continue;
            if (arena != null && !arena.equals(mode.getArenaId(candidate))) continue;
            if (team != null && !team.equals(mode.getTeamId(candidate))) continue;
            double distance = candidate.getLocation().distanceSquared(viewer.getLocation());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    private boolean attach(Player viewer, Player target) {
        try {
            viewer.setSpectatorTarget(target);
            attached.put(viewer.getUniqueId(), target.getUniqueId());
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("观战第一视角：切换目标失败：" + t);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 对外：/spectate
    // ------------------------------------------------------------------

    /** {@code /spectate <玩家>}：直接看指定队友。 */
    public boolean watch(Player viewer, Player target) {
        AddonMode mode = plugin.getApi() == null ? null : plugin.getApi().getModeFor(viewer);
        if (mode == null || viewer.getGameMode() != GameMode.SPECTATOR) {
            viewer.sendMessage(color("&c只有阵亡观战时才能看第一视角。"));
            return false;
        }
        if (target == null) {
            viewer.sendMessage(color("&c找不到该玩家。"));
            return false;
        }
        if (!valid(viewer, mode, target)) {
            viewer.sendMessage(color("&c只能看本局存活"
                    + (onlyTeammates ? "的队友" : "玩家") + "。"));
            return false;
        }
        attach(viewer, target);
        viewer.sendMessage(color("&7正在观战：&f" + target.getName()));
        return true;
    }

    /** {@code /spectate}：切到下一名存活队友。 */
    public boolean next(Player viewer) {
        return cycle(viewer, 1);
    }

    /** 按 step 方向在存活队友之间切换（+1 下一个，-1 上一个）。 */
    private boolean cycle(Player viewer, int step) {
        AddonMode mode = plugin.getApi() == null ? null : plugin.getApi().getModeFor(viewer);
        if (mode == null || viewer.getGameMode() != GameMode.SPECTATOR) {
            viewer.sendMessage(color("&c只有阵亡观战时才能切第一视角。"));
            return false;
        }
        Player current = spectatorTarget(viewer);
        String team = mode.getTeamId(viewer);
        String arena = mode.getArenaId(viewer);

        List<Player> candidates = new ArrayList<>();
        for (Player candidate : Bukkit.getOnlinePlayers()) {
            if (candidate.equals(viewer) || !valid(viewer, mode, candidate)) continue;
            if (arena != null && !arena.equals(mode.getArenaId(candidate))) continue;
            if (team != null && !team.equals(mode.getTeamId(candidate))) continue;
            candidates.add(candidate);
        }
        if (candidates.isEmpty()) {
            viewer.sendMessage(color("&c没有可以观战的队友。"));
            return false;
        }

        int index = current == null ? -1 : candidates.indexOf(current);
        int size = candidates.size();
        Player next;
        if (index < 0) {
            next = candidates.get(step >= 0 ? 0 : size - 1);
        } else {
            next = candidates.get(((index + step) % size + size) % size);
        }
        attach(viewer, next);
        return true;
    }

    /** {@code /spectate off}：退出第一视角。 */
    public boolean stop(Player viewer) {
        try {
            viewer.setSpectatorTarget(null);
        } catch (Throwable ignored) {
            // 某些服务端不接受 null，忽略
        }
        attached.remove(viewer.getUniqueId());
        viewer.sendMessage(color("&7已退出第一视角。"));
        return true;
    }

    /** 一名阵亡队友倒下时的位置。 */
    private record DeathSpot(String name, String world, Location location) {
    }

    private String color(String raw) {
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', raw);
    }
}
