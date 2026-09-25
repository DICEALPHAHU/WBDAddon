package alphahu.wbdaddon.defusesound;

import alphahu.wbdaddon.WBDAddon;
import com.warz.bombdefuse.api.WbdBombDefuseCancelEvent;
import com.warz.bombdefuse.api.WbdBombDefuseCompleteEvent;
import com.warz.bombdefuse.api.WbdBombDefuseStartEvent;
import com.warz.bombdefuse.arena.ArenaManager;
import com.warz.bombdefuse.arena.ArenaSession;
import com.warz.bombdefuse.arena.BombState;
import com.warz.bombdefuse.arena.PlayerRecord;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 拆弹声音提示。
 *
 * <p>WBD 本体只在「拆除成功」（{@code bomb-defused}）与「进度被中断」（{@code cancel}）
 * 时有音效，<b>开始拆弹与整个拆弹过程是完全静音的</b>。于是 T 阵营无法察觉 CT 正在拆弹，
 * 也就失去了回防博弈的信息。
 *
 * <p>本类补上的正是这一段：监听开始拆弹事件后，在 C4 所在位置播放「开始拆弹」提示音，
 * 并按固定间隔持续播放滴答声，让 T 能凭声音判断「有人在拆弹」以及大致方位；
 * 拆弹被中断或完成时立即停止。
 *
 * <p>为什么用事件 + 定时器而不是轮询状态：WBD 的 API 中 {@link BombState} 只暴露了
 * {@code isPlanted()} 与剩余秒数，携带拆弹进度的 {@code ProgressAction} 并未从
 * {@link ArenaSession} 暴露出来，因此「是否正在拆弹」根本无法查询，
 * 只能靠 {@link WbdBombDefuseStartEvent} / {@link WbdBombDefuseCancelEvent} /
 * {@link WbdBombDefuseCompleteEvent} 三个事件维护状态机。
 *
 * <p>音效以 C4 的位置为音源播放（{@code playSound(Location, ...)}），
 * 因此是带方位与距离衰减的 3D 声音，而不是贴在耳边响；
 * 音量用于延长可听距离，客户端会对音量限幅，所以调大不会把近处玩家震聋。
 *
 * @author AlphaHu
 */
public class DefuseSoundNotifier implements Listener {

    private final WBDAddon plugin;

    /** 竞技场 ID -> 该场正在进行的拆弹。同一竞技场同一时刻只会有一颗被拆的 C4。 */
    private final Map<String, ActiveDefuse> active = new ConcurrentHashMap<>();

    /** 滴答任务。只在存在进行中的拆弹时运行，空闲时完全不占调度。 */
    private BukkitTask tickTask;

    public DefuseSoundNotifier(WBDAddon plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    // 事件：拆弹开始 / 中断 / 完成
    // ------------------------------------------------------------------

    /**
     * 开始拆弹。
     *
     * <p>用 MONITOR + ignoreCancelled：等其它插件（含 WBD 自身）决定完是否放行之后，
     * 只对真正生效的拆弹出声；被取消的拆弹不该有声音。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDefuseStart(WbdBombDefuseStartEvent event) {
        Player defuser = event.getPlayer();
        if (defuser == null) return;

        String arenaId = resolveArenaId(event.getArenaId(), defuser);
        if (arenaId == null) {
            // arenaId 是 ConcurrentHashMap 的 key，为 null 会直接抛 NPE，必须先挡住
            plugin.getLogger().warning("拆弹开始事件缺少竞技场 ID，本次不播放拆弹声音。");
            return;
        }

        Location source = resolveSoundSource(arenaId, defuser);

        int interval = Math.max(1, cfgInt("tick-interval-ticks", 10));
        int maxTicks = Math.max(20, cfgInt("max-duration-seconds", 30) * 20);

        // 音效参数在此读取一次并缓存，避免滴答任务每个周期重复读配置
        active.put(arenaId, new ActiveDefuse(
                source,
                interval,
                maxTicks,
                parseSound(cfgString("tick-sound", "BLOCK_NOTE_BLOCK_HAT")),
                (float) cfgDouble("tick-volume", 16.0),
                (float) cfgDouble("tick-pitch", 1.5)));

        if (cfgBool("debug", false)) {
            plugin.getLogger().info("[debug] 检测到开始拆弹：玩家=" + defuser.getName()
                    + "，竞技场=" + arenaId + "，炸弹点=" + event.getSiteId()
                    + "，音源=" + describe(source));
        }

        playConfigured(arenaId, source, "start");
        startTickTask();
    }

    /** 拆弹被中断：松手、被打断、拆弹者死亡等。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDefuseCancel(WbdBombDefuseCancelEvent event) {
        finish(event.getArenaId(), "cancel", "拆弹中断（" + event.getReason() + "）");
    }

    /** 拆弹完成。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDefuseComplete(WbdBombDefuseCompleteEvent event) {
        finish(event.getArenaId(), "complete", "拆弹完成");
    }

    /** 收尾：停掉滴答，并按配置决定是否补一声中断/完成音。 */
    private void finish(String arenaId, String soundKey, String reason) {
        ActiveDefuse defuse = active.remove(arenaId);
        if (defuse == null) {
            // 没有记录说明本模块是在拆弹开始之后才启用的，无需处理
            return;
        }

        if (cfgBool("debug", false)) {
            plugin.getLogger().info("[debug] " + reason + "，竞技场=" + arenaId + "，停止拆弹滴答");
        }

        playConfigured(arenaId, defuse.soundSource, soundKey);
        if (active.isEmpty()) {
            stopTickTask();
        }
    }

    // ------------------------------------------------------------------
    // 滴答任务
    // ------------------------------------------------------------------

    private void startTickTask() {
        if (tickTask != null) return;
        // 每 tick 走一次，真正出声的节奏由 ActiveDefuse.tickInterval 控制
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    private void stopTickTask() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
    }

    private void tick() {
        ArenaManager am = plugin.getWbd().getArenaManager();

        for (Iterator<Map.Entry<String, ActiveDefuse>> it = active.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, ActiveDefuse> entry = it.next();
            ActiveDefuse defuse = entry.getValue();

            defuse.elapsedTicks++;

            // 兜底一：C4 已经不在了（被拆除、已爆炸、回合结束重置）。
            // Cancel/Complete 事件是正常收尾路径，这一条兜的是事件没派发的场景，
            // 最典型的就是 T 没被打断、C4 直接倒计时爆炸。
            if (bombGone(am, entry.getKey())) {
                if (cfgBool("debug", false)) {
                    plugin.getLogger().info("[debug] C4 已不在安放状态，停止拆弹滴答，竞技场=" + entry.getKey());
                }
                it.remove();
                continue;
            }

            // 兜底二：超时。防止任何一种状态下滴答停不下来
            if (defuse.elapsedTicks > defuse.maxTicks) {
                if (cfgBool("debug", false)) {
                    plugin.getLogger().info("[debug] 拆弹滴答超时保护触发，竞技场=" + entry.getKey());
                }
                it.remove();
                continue;
            }

            if (--defuse.ticksUntilNext > 0) continue;
            defuse.ticksUntilNext = defuse.tickInterval;

            if (defuse.tickSound != null) {
                broadcast(entry.getKey(), defuse.soundSource,
                        defuse.tickSound, defuse.tickVolume, defuse.tickPitch);
            }
        }

        if (active.isEmpty()) {
            stopTickTask();
        }
    }

    /**
     * 判断该竞技场的 C4 是否已经不在安放状态。
     *
     * <p>查不到竞技场时返回 false：宁可让超时兜底收尾，也不要因为
     * {@code getSession} 取不到对象就误停滴答。
     */
    private boolean bombGone(ArenaManager am, String arenaId) {
        if (am == null) return false;

        ArenaSession session = am.getSession(arenaId);
        if (session == null) return false;

        BombState bomb = session.getBomb();
        return bomb == null || !bomb.isPlanted();
    }

    // ------------------------------------------------------------------
    // 播音
    // ------------------------------------------------------------------

    /** 按 {@code <key>-sound / -volume / -pitch} 读取配置并播放。音效名为空表示关闭。 */
    private void playConfigured(String arenaId, Location source, String key) {
        Sound sound = parseSound(cfgString(key + "-sound", ""));
        if (sound == null) return;

        float volume = (float) cfgDouble(key + "-volume", 16.0);
        float pitch = (float) cfgDouble(key + "-pitch", 1.0);
        broadcast(arenaId, source, sound, volume, pitch);
    }

    /**
     * 把音效播给听众。
     *
     * <p>两种听众范围：
     * <ul>
     *   <li>{@code arena}（默认）：仅该竞技场内的玩家，含已淘汰的旁观者
     *       —— 与 WBD 自身 {@code bomb-planted} 的做法一致，不会串到大厅或其他竞技场</li>
     *   <li>{@code world}：炸弹所在世界的全部玩家，连大厅里的人也能听见</li>
     * </ul>
     *
     * <p>两者都以 C4 的位置为音源，因此都带方位感。
     */
    private void broadcast(String arenaId, Location source, Sound sound, float volume, float pitch) {
        if (source == null || source.getWorld() == null) return;

        if ("world".equalsIgnoreCase(cfgString("audience", "arena"))) {
            source.getWorld().playSound(source, sound, volume, pitch);
            return;
        }

        ArenaManager am = plugin.getWbd().getArenaManager();
        ArenaSession session = (am == null) ? null : am.getSession(arenaId);
        if (session == null) {
            // 取不到竞技场就退回全世界上报，宁可听到也不要没声音
            source.getWorld().playSound(source, sound, volume, pitch);
            return;
        }

        for (PlayerRecord record : session.getPlayers()) {
            Player listener = Bukkit.getPlayer(record.getUuid());
            if (listener == null) continue;
            // 音源与听众不在同一世界时播放会失效，直接跳过
            if (!listener.getWorld().equals(source.getWorld())) continue;
            listener.playSound(source, sound, volume, pitch);
        }
    }

    /** 取竞技场 ID：事件里没给时，用拆弹者所在的竞技场反查。 */
    private String resolveArenaId(String fromEvent, Player defuser) {
        if (fromEvent != null) return fromEvent;

        ArenaManager am = plugin.getWbd().getArenaManager();
        if (am == null) return null;

        ArenaSession session = am.getSession(defuser);
        if (session == null || session.getConfig() == null) return null;
        return session.getConfig().getId();
    }

    /** 音源优先取 C4 的实际位置；取不到时退回拆弹者当前位置。 */
    private Location resolveSoundSource(String arenaId, Player defuser) {
        ArenaManager am = plugin.getWbd().getArenaManager();
        if (am != null) {
            ArenaSession session = am.getSession(arenaId);
            if (session != null) {
                BombState bomb = session.getBomb();
                if (bomb != null && bomb.getLocation() != null) {
                    return bomb.getLocation().clone();
                }
            }
        }
        return defuser.getLocation().clone();
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 解析音效名。名字无效或留空时返回 null（静默跳过，不影响其它功能）。 */
    private Sound parseSound(String name) {
        if (name == null || name.isBlank()) return null;
        try {
            return Sound.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("配置里的音效名无效，已跳过：" + name);
            return null;
        }
    }

    private String describe(Location loc) {
        if (loc == null || loc.getWorld() == null) return "未知";
        return loc.getWorld().getName() + "(" + (int) loc.getX() + "," + (int) loc.getY()
                + "," + (int) loc.getZ() + ")";
    }

    private String cfgString(String key, String def) {
        return plugin.getConfig().getString("modules.bomb-defuse-sound." + key, def);
    }

    private boolean cfgBool(String key, boolean def) {
        return plugin.getConfig().getBoolean("modules.bomb-defuse-sound." + key, def);
    }

    private int cfgInt(String key, int def) {
        return plugin.getConfig().getInt("modules.bomb-defuse-sound." + key, def);
    }

    private double cfgDouble(String key, double def) {
        return plugin.getConfig().getDouble("modules.bomb-defuse-sound." + key, def);
    }

    /** 关闭模块：取消任务并丢弃状态。 */
    public void shutdown() {
        stopTickTask();
        active.clear();
    }

    // ------------------------------------------------------------------
    // 内部状态
    // ------------------------------------------------------------------

    /** 一次进行中的拆弹。 */
    private static final class ActiveDefuse {

        /** 音源位置（C4 所在地）。 */
        private final Location soundSource;

        /** 滴答间隔（tick）。 */
        private final int tickInterval;

        /** 超时保护上限（tick）。 */
        private final int maxTicks;

        /** 滴答音效，null 表示关闭滴答。 */
        private final Sound tickSound;

        private final float tickVolume;
        private final float tickPitch;

        private int elapsedTicks;
        private int ticksUntilNext;

        private ActiveDefuse(Location soundSource, int tickInterval, int maxTicks,
                             Sound tickSound, float tickVolume, float tickPitch) {
            this.soundSource = soundSource;
            this.tickInterval = tickInterval;
            this.maxTicks = maxTicks;
            this.tickSound = tickSound;
            this.tickVolume = tickVolume;
            this.tickPitch = tickPitch;
            this.ticksUntilNext = tickInterval;
        }
    }
}
