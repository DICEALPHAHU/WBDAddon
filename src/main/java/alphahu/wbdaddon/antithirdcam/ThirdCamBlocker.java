package alphahu.wbdaddon.antithirdcam;

import alphahu.wbdaddon.WBDAddon;
import com.warz.bombdefuse.arena.ArenaManager;
import com.warz.bombdefuse.arena.ArenaSession;
import com.warz.bombdefuse.model.Team;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 防第三人称偷看遮挡器。
 *
 * <p>有些玩家会切到第三人称视角，隔着掩体看到不该看的位置。第三人称是
 * <b>客户端按键</b>，服务端<b>无法真正禁用、也无法侦测</b>，所以这里采用
 * 业界通用的「遮挡」思路：给参赛玩家挂一个巨大的黑色实心
 * {@link TextDisplay}，且<b>只对该玩家本人可见</b>；切到第三人称时
 * 遮挡面会糊住视野，第三人称就失去偷看价值了。
 *
 * <p>实现方案参考自开源插件 AntiF5：https://github.com/ladakx/AntiF5
 * <ul>
 *   <li>遮挡面用 <code>player.addPassenger()</code> 骑在玩家身上，位置与朝向自动跟随，无需每 tick 传送</li>
 *   <li>内容为「黑色实心方块」字形 <code>§0█</code>，默认缩放到 128 倍</li>
 *   <li>只使用 Spigot 级 API；Paper 独有的 <code>setVisibleByDefault</code>、<code>setViewRange</code>
 *       都用反射安全调用，不支持则自动退化</li>
 * </ul>
 *
 * @author AlphaHu
 */
public class ThirdCamBlocker {

    /** 标记本插件创建的遮挡面，便于清理残留。 */
    private static final String TAG = "wbdaddon_antithirdcam";

    private final WBDAddon plugin;

    private BukkitTask task;

    /** 玩家 UUID -> 其专属遮挡面。 */
    private final Map<UUID, TextDisplay> overlays = new ConcurrentHashMap<>();

    /** setVisibleByDefault 是否可用；null 表示尚未探测。 */
    private Boolean visibleByDefaultSupported;

    /**
     * 已经对「当前所有遮挡面」执行过 hideEntity 的观察者。
     * 仅在缺少 setVisibleByDefault 的服务端（Spigot / Arclight）上使用，
     * 用于把「每个 tick 对全体玩家重新隐藏」降为「只隐藏新加入的玩家」。
     */
    private final Set<UUID> hiddenObservers = ConcurrentHashMap.newKeySet();

    /** 上次全量重隐藏的时间戳（毫秒）。 */
    private volatile long lastFullSweep = 0L;

    /** 全量重隐藏的兜底间隔：兜住切世界、重生后客户端实体状态错位。 */
    private static final long FULL_SWEEP_INTERVAL_MS = 30_000L;

    public ThirdCamBlocker(WBDAddon plugin) {
        this.plugin = plugin;
    }

    public void start() {
        long interval = Math.max(1, plugin.getConfig()
                .getLong("modules.antithirdcam.update-interval-ticks", 20));
        // 遮挡面靠乘客机制自动跟随，这里只做低频的「检查并在丢失时重建」
        hiddenObservers.clear();
        lastFullSweep = 0L;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, interval);
        cleanupLeftovers();
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (TextDisplay display : overlays.values()) {
            removeEntity(display);
        }
        overlays.clear();
        hiddenObservers.clear();
    }

    private void tick() {
        ArenaManager am = plugin.getWbd().getArenaManager();
        if (am == null) return;

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (shouldBlock(am, player)) {
                ensure(player);
            } else {
                remove(player.getUniqueId());
            }
        }

        // 清理已离线玩家留下的遮挡面
        Iterator<Map.Entry<UUID, TextDisplay>> it = overlays.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, TextDisplay> entry = it.next();
            if (Bukkit.getPlayer(entry.getKey()) == null) {
                removeEntity(entry.getValue());
                it.remove();
            }
        }

        // 退化模式下增量维护可见性，避免每 tick 对全体玩家重复 hideEntity（O(n²)）
        if (Boolean.FALSE.equals(visibleByDefaultSupported)) {
            syncHiddenObservers();
        }
    }

    /** 是否需要给该玩家挂遮挡面。 */
    private boolean shouldBlock(ArenaManager am, Player player) {
        if (!plugin.getConfig().getBoolean("modules.antithirdcam.only-in-arena", true)) {
            return true;
        }
        ArenaSession session = am.getSession(player);
        if (session == null) return false;
        Team team = session.getTeam(player);
        return team != null && team.isPlaying();
    }

    /** 确保该玩家有一个有效的遮挡面（丢了就重建）。 */
    private void ensure(Player player) {
        TextDisplay display = overlays.get(player.getUniqueId());
        // 仍在骑乘状态说明一切正常
        if (display != null && display.isValid() && player.getPassengers().contains(display)) {
            // 可见性由 tick() 中的 syncHiddenObservers() 增量维护，这里不必重复隐藏
            return;
        }
        remove(player.getUniqueId());
        overlays.put(player.getUniqueId(), spawn(player));
    }

    private TextDisplay spawn(Player player) {
        TextDisplay display = player.getWorld().spawn(player.getLocation(), TextDisplay.class);

        String text = plugin.getConfig()
                .getString("modules.antithirdcam.overlay-text", "§0█");
        display.setText(ChatColor.translateAlternateColorCodes('&', text));
        display.setDefaultBackground(false);
        display.setBackgroundColor(Color.fromARGB(parseArgb(plugin.getConfig()
                .getString("modules.antithirdcam.background-argb", "0xFF000000"))));
        display.setTextOpacity((byte) plugin.getConfig()
                .getInt("modules.antithirdcam.text-opacity", 255));
        display.setShadowed(plugin.getConfig()
                .getBoolean("modules.antithirdcam.shadowed", true));
        display.setSeeThrough(plugin.getConfig()
                .getBoolean("modules.antithirdcam.see-through", true));
        display.setBillboard(Display.Billboard.CENTER);
        display.setPersistent(false);
        display.setGravity(false);
        display.setInvulnerable(true);
        display.setSilent(true);
        display.addScoreboardTag(TAG);

        float tx = (float) plugin.getConfig().getDouble("modules.antithirdcam.translation-x", 0.0);
        float ty = (float) plugin.getConfig().getDouble("modules.antithirdcam.translation-y", -16.0);
        float tz = (float) plugin.getConfig().getDouble("modules.antithirdcam.translation-z", 1.0);
        float sx = (float) plugin.getConfig().getDouble("modules.antithirdcam.scale-x", 128.0);
        float sy = (float) plugin.getConfig().getDouble("modules.antithirdcam.scale-y", 128.0);
        float sz = (float) plugin.getConfig().getDouble("modules.antithirdcam.scale-z", 128.0);
        display.setTransformation(new Transformation(
                new Vector3f(tx, ty, tz),
                new Quaternionf(),
                new Vector3f(sx, sy, sz),
                new Quaternionf()));

        applyViewRange(display, (float) plugin.getConfig()
                .getDouble("modules.antithirdcam.view-range", 8.0));

        // 骑在玩家身上：位置与朝向自动跟随，无需每 tick 传送
        player.addPassenger(display);
        player.showEntity(plugin, display);

        // 可见性：优先用 setVisibleByDefault（Paper / 部分服务端），
        // 不支持则退化为「对其他玩家逐个 hideEntity」
        if (!applyDefaultInvisibility(display)) {
            // 新遮挡面：先对当前所有其他在线玩家隐藏并登记为已处理，
            // 之后由 syncHiddenObservers() 只补后续新加入的玩家
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (other.equals(player)) continue;
                other.hideEntity(plugin, display);
                hiddenObservers.add(other.getUniqueId());
            }
        }
        return display;
    }

    /**
     * 让遮挡面默认对所有玩家不可见，之后由 {@code showEntity} 单独给本人显示。
     *
     * @return 是否成功（false 表示当前服务端不支持该方法）
     */
    private boolean applyDefaultInvisibility(TextDisplay display) {
        if (visibleByDefaultSupported == null) {
            visibleByDefaultSupported = invokeSetVisibleByDefault(display);
        } else if (visibleByDefaultSupported) {
            invokeSetVisibleByDefault(display);
        }
        return visibleByDefaultSupported;
    }

    private boolean invokeSetVisibleByDefault(TextDisplay display) {
        try {
            Entity.class.getMethod("setVisibleByDefault", boolean.class).invoke(display, false);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 退化模式下维护遮挡面的可见性。
     *
     * <p>只对「本插件尚未处理过」的观察者执行 hideEntity，因此稳态下几乎零开销；
     * 另外每 {@link #FULL_SWEEP_INTERVAL_MS} 毫秒做一次全量重隐藏，
     * 兜住切世界、重生等场景下客户端实体重新可见的情况。
     */
    private void syncHiddenObservers() {
        long now = System.currentTimeMillis();
        boolean fullSweep = now - lastFullSweep >= FULL_SWEEP_INTERVAL_MS;
        if (fullSweep) {
            lastFullSweep = now;
        }

        Set<UUID> online = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            online.add(player.getUniqueId());
        }
        // 已下线的玩家不必再记，避免集合无限增长
        hiddenObservers.retainAll(online);

        for (Player observer : Bukkit.getOnlinePlayers()) {
            UUID observerId = observer.getUniqueId();
            if (fullSweep) {
                // 兜底轮：对所有观察者重来一遍，修正客户端可能错位的实体状态
                hiddenObservers.add(observerId);
            } else if (!hiddenObservers.add(observerId)) {
                // 稳态：只处理本插件还没见过的观察者
                continue;
            }

            for (Map.Entry<UUID, TextDisplay> entry : overlays.entrySet()) {
                // 自己那份遮挡面要留着，别把本人也挡住
                if (entry.getKey().equals(observerId)) continue;
                observer.hideEntity(plugin, entry.getValue());
            }
        }
    }

    /**
     * 设置渲染距离上限。
     * <p>{@code setViewRange} 是 Paper 增加的方法，不支持时忽略即可。
     */
    private void applyViewRange(TextDisplay display, float range) {
        try {
            Display.class.getMethod("setViewRange", float.class).invoke(display, range);
        } catch (Throwable ignored) {
            // 服务端不支持该扩展方法，忽略
        }
    }

    private void remove(UUID uuid) {
        removeEntity(overlays.remove(uuid));
    }

    private void removeEntity(TextDisplay display) {
        if (display != null && display.isValid()) {
            display.remove();
        }
    }

    /** 启动时清理上次异常退出残留的遮挡面。 */
    private void cleanupLeftovers() {
        Set<World> worlds = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            worlds.add(player.getWorld());
        }
        for (World world : worlds) {
            for (TextDisplay display : world.getEntitiesByClass(TextDisplay.class)) {
                if (display.getScoreboardTags().contains(TAG)) {
                    display.remove();
                }
            }
        }
    }

    /** 解析 "0xAARRGGBB" / "AARRGGBB" 形式的颜色。 */
    private int parseArgb(String raw) {
        try {
            String s = raw.trim();
            if (s.startsWith("0x") || s.startsWith("0X")) {
                s = s.substring(2);
            }
            return (int) Long.parseLong(s, 16);
        } catch (Exception e) {
            return 0xFF000000;
        }
    }
}
