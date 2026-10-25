package alphahu.wbdaddon.module;

import alphahu.wbdaddon.WBDAddon;
import alphahu.wbdaddon.api.AddonMode;
import com.warz.bombdefuse.api.WbdPlayerJoinArenaEvent;
import com.warz.bombdefuse.api.WbdRoundStartEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 竞技场药水效果清理。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>WarZBombDefuse <b>不会清除玩家进场前身上的药水效果</b>：
 * {@code ArenaSession} 里唯一的 {@code removePotionEffect} 只针对 {@code GLOWING}；
 * {@code PlayerSnapshot} 只是把 {@code getActivePotionEffects()} 记录下来，
 * 等离场时再还原，进场时并不清空。
 *
 * <p>结果就是：带着抗性提升、速度、力量、生命提升进来的人，整局都白赚 —— 对其他人不公平。
 *
 * <h2>为什么清掉不会让玩家永久丢效果</h2>
 *
 * <p>WBD 的进场流程是「先 {@code new PlayerSnapshot(player)} 记录，再发
 * {@link WbdPlayerJoinArenaEvent}」。快照里已经保存了进场前的药水效果，
 * 离场时 {@code PlayerSnapshot.restore()} 会把它们加回来。所以这里清掉是安全的。
 *
 * <h2>清理时机</h2>
 *
 * <ul>
 *   <li>进入竞技场（快照之后）</li>
 *   <li>每回合开始（防止回合中途吃药/被命令加效果后带到下一回合）</li>
 *   <li>重生后（下一 tick）</li>
 * </ul>
 *
 * <p>白名单里的效果不清（默认空），用于「夜视」这类纯辅助效果。
 *
 * @author AlphaHu
 */
public class ArenaEffectsModule implements AddonModule, Listener {

    private final WBDAddon plugin;

    /** 白名单：这些效果不清除。 */
    private final Set<PotionEffectType> keep = new HashSet<>();

    public ArenaEffectsModule(WBDAddon plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getId() {
        return "arena-effects";
    }

    @Override
    public String getDisplayName() {
        return "药水效果清理";
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }

    @Override
    public void onEnable() {
        keep.clear();
        for (String name : plugin.getConfig()
                .getStringList("modules.arena-effects.keep")) {
            if (name == null || name.isBlank()) continue;
            PotionEffectType type = PotionEffectType.getByName(name.trim().toUpperCase());
            if (type != null) {
                keep.add(type);
            } else {
                plugin.getLogger().warning("modules.arena-effects.keep 里的 " + name
                        + " 不是合法药水效果名，已忽略。");
            }
        }

        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("药水效果清理已启动"
                + (keep.isEmpty() ? "（无白名单）" : "，白名单 " + keep.size() + " 项") + "。");
    }

    @Override
    public void onDisable() {
        HandlerList.unregisterAll(this);
        keep.clear();
        plugin.getLogger().info("药水效果清理已卸载。");
    }

    // ------------------------------------------------------------------
    // 清理时机
    // ------------------------------------------------------------------

    /**
     * 进场时清理。
     *
     * <p>用 {@code MONITOR}：此时 WBD 已经完成 {@code PlayerSnapshot} 记录，
     * 清掉效果不会影响离场还原。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(WbdPlayerJoinArenaEvent event) {
        if (!plugin.getConfig().getBoolean(
                "modules.arena-effects.clear-on-join", true)) {
            return;
        }
        Player player = event.getPlayer();
        if (player != null) {
            clear(player);
        }
    }

    /** 每回合开始再清一次，防止回合中途获得的效果跨回合保留。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRoundStart(WbdRoundStartEvent event) {
        if (!plugin.getConfig().getBoolean(
                "modules.arena-effects.clear-on-round-start", true)) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (inArena(player, event.getArenaId())) {
                clear(player);
            }
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (isParticipant(player)) {
                clear(player);
            }
        });
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private void clear(Player player) {
        if (player == null || !player.isOnline()) return;

        List<PotionEffect> active = new ArrayList<>(player.getActivePotionEffects());
        if (active.isEmpty()) return;

        int removed = 0;
        for (PotionEffect effect : active) {
            if (keep.contains(effect.getType())) continue;
            player.removePotionEffect(effect.getType());
            removed++;
        }

        if (removed > 0 && plugin.getConfig().getBoolean("modules.arena-effects.debug", false)) {
            plugin.getLogger().info("[arena-effects] 清除 " + player.getName()
                    + " 的 " + removed + " 个药水效果。");
        }
    }

    private boolean inArena(Player player, String arenaId) {
        if (plugin.getApi() == null) return false;
        AddonMode mode = plugin.getApi().getModeFor(player);
        if (mode == null || !mode.isParticipating(player)) return false;
        return arenaId.equals(mode.getArenaId(player));
    }

    private boolean isParticipant(Player player) {
        if (plugin.getApi() == null) return false;
        AddonMode mode = plugin.getApi().getModeFor(player);
        return mode != null && mode.isParticipating(player);
    }
}
