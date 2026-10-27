package alphahu.wbdaddon.module;

import alphahu.wbdaddon.WBDAddon;
import alphahu.wbdaddon.api.AddonMode;
import alphahu.wbdaddon.mode.BombDefuseMode;
import com.warz.bombdefuse.api.WbdRoundStartEvent;
import com.warz.bombdefuse.arena.ArenaSession;
import com.warz.bombdefuse.model.GameState;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

/**
 * 冻结倒计时音效。
 *
 * <p>回合冻结的最后几秒（默认 3 秒），给参赛玩家每秒播放一声轻短的「哒」，
 * 越接近回合开始音调越高，制造 CS 式的紧张感。
 *
 * <p>音量默认压得很低（0.35），只作为背景节拍，不会盖过枪声/语音。
 *
 * @author AlphaHu
 */
public class FreezeCountdownModule implements AddonModule, Listener {

    private final WBDAddon plugin;

    private Sound sound;
    private int seconds;
    private float volume;
    private float basePitch;
    private float pitchStep;

    public FreezeCountdownModule(WBDAddon plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getId() {
        return "freeze-countdown";
    }

    @Override
    public String getDisplayName() {
        return "冻结倒计时音效";
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }

    @Override
    public void onEnable() {
        this.seconds = Math.max(0, plugin.getConfig()
                .getInt("modules.freeze-countdown.seconds", 3));
        this.volume = (float) plugin.getConfig()
                .getDouble("modules.freeze-countdown.volume", 0.35);
        this.basePitch = (float) plugin.getConfig()
                .getDouble("modules.freeze-countdown.base-pitch", 1.6);
        this.pitchStep = (float) plugin.getConfig()
                .getDouble("modules.freeze-countdown.pitch-step", 0.15);
        this.sound = parseSound(plugin.getConfig()
                .getString("modules.freeze-countdown.sound", "BLOCK_NOTE_BLOCK_HAT"));

        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("冻结倒计时音效已启动：最后 " + seconds + " 秒，音效 "
                + sound.name() + "，音量 " + volume + "。");
    }

    @Override
    public void onDisable() {
        HandlerList.unregisterAll(this);
        plugin.getLogger().info("冻结倒计时音效已卸载。");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRoundStart(WbdRoundStartEvent event) {
        if (seconds <= 0) return;

        ArenaSession session = session(event.getArenaId());
        if (session == null) return;

        // 冻结总时长（tick）。事件触发时 WBD 通常已经设好 stateTicksRemaining，
        // 读不到就退回配置里的 freeze-seconds。
        int freezeTicks = session.getStateTicksRemaining();
        if (freezeTicks <= 0) {
            freezeTicks = Math.max(20, wbdConfig()
                    .getInt("settings.freeze-seconds", 8) * 20);
        }

        for (int i = 1; i <= seconds; i++) {
            long delay = freezeTicks - i * 20L;
            if (delay < 0) continue;

            int index = i; // 1 = 最后一秒
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (session.getState() != GameState.FREEZE) return;

                // 越接近回合开始音调越高：3 秒最低，1 秒最高
                float pitch = basePitch + (seconds - index) * pitchStep;
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (inArena(player, event.getArenaId())) {
                        player.playSound(player.getLocation(), sound, volume, pitch);
                    }
                }
            }, delay);
        }
    }

    private Sound parseSound(String raw) {
        if (raw != null) {
            try {
                return Sound.valueOf(raw.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("modules.freeze-countdown.sound 的值 " + raw
                        + " 不是合法音效名，改用 BLOCK_NOTE_BLOCK_HAT。");
            }
        }
        return Sound.BLOCK_NOTE_BLOCK_HAT;
    }

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
