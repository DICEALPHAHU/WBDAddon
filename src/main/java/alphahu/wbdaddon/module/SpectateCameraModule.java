package alphahu.wbdaddon.module;

import alphahu.wbdaddon.WBDAddon;
import alphahu.wbdaddon.api.AddonMode;
import com.warz.bombdefuse.arena.ArenaSession;
import com.warz.bombdefuse.arena.PlayerRecord;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
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
public class SpectateCameraModule implements AddonModule {

    private final WBDAddon plugin;

    /** 观战者 -> 上一次挂载的目标，用来判断要不要重挂。 */
    private final Map<UUID, UUID> attached = new ConcurrentHashMap<>();

    private BukkitTask task;
    private long intervalTicks;
    private boolean auto;
    private boolean onlyTeammates;

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
                continue;
            }
            AddonMode mode = plugin.getApi().getModeFor(player);
            if (mode == null || mode.isAlive(player)) {
                attached.remove(player.getUniqueId());
                continue;
            }

            Player current = spectatorTarget(player);
            if (current != null && valid(player, mode, current)) {
                attached.put(player.getUniqueId(), current.getUniqueId());
                continue;
            }
            if (!auto) {
                continue;
            }
            Player next = pickTarget(player, mode);
            if (next != null) {
                attach(player, next);
            }
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
        AddonMode mode = plugin.getApi() == null ? null : plugin.getApi().getModeFor(viewer);
        if (mode == null || viewer.getGameMode() != GameMode.SPECTATOR) {
            viewer.sendMessage(color("&c只有阵亡观战时才能切第一视角。"));
            return false;
        }
        Player current = spectatorTarget(viewer);
        String team = mode.getTeamId(viewer);
        String arena = mode.getArenaId(viewer);

        Player first = null;
        boolean afterCurrent = current == null;
        for (Player candidate : Bukkit.getOnlinePlayers()) {
            if (candidate.equals(viewer) || !valid(viewer, mode, candidate)) continue;
            if (arena != null && !arena.equals(mode.getArenaId(candidate))) continue;
            if (team != null && !team.equals(mode.getTeamId(candidate))) continue;
            if (first == null) first = candidate;
            if (afterCurrent) {
                attach(viewer, candidate);
                viewer.sendMessage(color("&7正在观战：&f" + candidate.getName()));
                return true;
            }
            if (candidate.equals(current)) afterCurrent = true;
        }
        if (first != null) {
            attach(viewer, first);
            viewer.sendMessage(color("&7正在观战：&f" + first.getName()));
            return true;
        }
        viewer.sendMessage(color("&c没有可以观战的队友。"));
        return false;
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

    private String color(String raw) {
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', raw);
    }
}
