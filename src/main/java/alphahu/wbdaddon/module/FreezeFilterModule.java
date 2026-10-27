package alphahu.wbdaddon.module;

import alphahu.wbdaddon.WBDAddon;
import alphahu.wbdaddon.api.AddonMode;
import alphahu.wbdaddon.mode.BombDefuseMode;
import com.warz.bombdefuse.api.WbdRoundStartEvent;
import com.warz.bombdefuse.arena.ArenaSession;
import com.warz.bombdefuse.model.GameState;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 回合冻结滤镜（服务端近似版）。
 *
 * <h2>先说清楚能做到什么</h2>
 *
 * <p>CS 那种「灰白逐渐变彩色」是<b>客户端后处理 shader</b>，服务端插件改不了画面颜色。
 * 这里用原版药水效果做近似：
 *
 * <ol>
 *   <li>冻结开始先来一段 {@code BLINDNESS}（全黑）</li>
 *   <li>接着换成 {@code DARKNESS}（黑暗脉动），一直持续到回合快开始</li>
 *   <li>提前 {@code fade-ticks} 结束黑暗，视觉上比突然变亮柔和一点</li>
 * </ol>
 *
 * <p>效果是「黑 → 暗 → 亮」，不是真正的灰度。想要真灰度只能改客户端资源包/shader。
 *
 * @author AlphaHu
 */
public class FreezeFilterModule implements AddonModule, Listener {

    private final WBDAddon plugin;

    /** 本模块施加过效果的玩家，用于只清理自己加的效果。 */
    private final Set<UUID> affected = ConcurrentHashMap.newKeySet();

    private boolean darknessUntilLive;
    private int blindTicks;
    private int fadeTicks;
    private BukkitTask cleanupTask;

    public FreezeFilterModule(WBDAddon plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getId() {
        return "freeze-filter";
    }

    @Override
    public String getDisplayName() {
        return "冻结滤镜";
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }

    @Override
    public void onEnable() {
        this.blindTicks = Math.max(0, plugin.getConfig()
                .getInt("modules.freeze-filter.blind-ticks", 40));
        this.fadeTicks = Math.max(0, plugin.getConfig()
                .getInt("modules.freeze-filter.fade-ticks", 20));
        this.darknessUntilLive = plugin.getConfig()
                .getBoolean("modules.freeze-filter.darkness-until-live", true);

        Bukkit.getPluginManager().registerEvents(this, plugin);
        this.cleanupTask = Bukkit.getScheduler().runTaskTimer(
                plugin, this::cleanup, 20L, 20L);

        plugin.getLogger().info("冻结滤镜已启动：全黑 " + blindTicks
                + " tick，黑暗持续到回合开始"
                + (fadeTicks > 0 ? "（提前 " + fadeTicks + " tick 结束）" : "") + "。");
    }

    @Override
    public void onDisable() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
        HandlerList.unregisterAll(this);
        clearAll();
        plugin.getLogger().info("冻结滤镜已卸载。");
    }

    // ------------------------------------------------------------------
    // 冻结开始
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRoundStart(WbdRoundStartEvent event) {
        ArenaSession session = session(event.getArenaId());
        if (session == null) return;

        int freezeTicks = Math.max(20, wbdConfig()
                .getInt("settings.freeze-seconds", 8) * 20);
        int blind = Math.min(blindTicks, Math.max(0, freezeTicks - 5));
        int darkness = darknessUntilLive
                ? Math.max(0, freezeTicks - blind - fadeTicks)
                : 0;

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!inArena(player, event.getArenaId())) continue;

            if (blind > 0) {
                apply(player, PotionEffectType.BLINDNESS, blind);
            }
            if (darkness > 0) {
                apply(player, PotionEffectType.DARKNESS, darkness);
            }
            affected.add(player.getUniqueId());
        }
    }

    private void apply(Player player, PotionEffectType type, int ticks) {
        player.addPotionEffect(new PotionEffect(type, ticks, 0,
                false, false, false));
    }

    // ------------------------------------------------------------------
    // 清理
    // ------------------------------------------------------------------

    /** 回合不再是 FREEZE 时，把本模块加的效果撤掉，避免带进 LIVE。 */
    private void cleanup() {
        for (UUID id : affected.toArray(new UUID[0])) {
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) {
                affected.remove(id);
                continue;
            }
            AddonMode mode = plugin.getApi() == null ? null : plugin.getApi().getModeFor(player);
            if (mode == null || !BombDefuseMode.MODE_ID.equals(mode.getModeId())) {
                remove(player);
                affected.remove(id);
                continue;
            }
            ArenaSession session = session(mode.getArenaId(player));
            if (session == null || session.getState() != GameState.FREEZE) {
                remove(player);
                affected.remove(id);
            }
        }
    }

    private void remove(Player player) {
        player.removePotionEffect(PotionEffectType.BLINDNESS);
        player.removePotionEffect(PotionEffectType.DARKNESS);
    }

    private void clearAll() {
        for (UUID id : affected.toArray(new UUID[0])) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) {
                remove(player);
            }
        }
        affected.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        affected.remove(event.getPlayer().getUniqueId());
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

    private org.bukkit.configuration.file.FileConfiguration wbdConfig() {
        return plugin.getWbd() == null ? plugin.getConfig() : plugin.getWbd().getConfig();
    }
}
