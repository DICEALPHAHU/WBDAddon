package alphahu.wbdaddon.antithirdcam;

import alphahu.wbdaddon.WBDAddon;
import com.warz.bombdefuse.arena.ArenaManager;
import com.warz.bombdefuse.arena.ArenaSession;
import com.warz.bombdefuse.arena.PlayerRecord;
import com.warz.bombdefuse.model.Team;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
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
 *   <li>可见性每个周期对全体玩家重算一遍、不做增量记录：重生、换世界、传送都会
 *       让服务端重发实体并令 {@code hideEntity} 失效，增量方案漏一个场景就会糊脸</li>
 * </ul>
 *
 * @author AlphaHu
 */
public class ThirdCamBlocker implements Listener {

    /** 标记本插件创建的遮挡面，便于清理残留。 */
    private static final String TAG = "wbdaddon_antithirdcam";

    private final WBDAddon plugin;

    private BukkitTask task;

    /** 非乘客模式下的位置跟随任务（每 tick 一次）。 */
    private BukkitTask followTask;

    /**
     * 是否用「乘客」机制跟随。
     *
     * <p>乘客机制让遮挡面骑在玩家身上，由服务端自动同步位置与朝向，开销最低；
     * 但它会把玩家变成「载具」，改变玩家的实体状态。在 Arclight 这类
     * Forge + Bukkit 混合端上，这种非常规状态有与传送、回合位置重置互相干扰的风险，
     * 因此默认改用「每 tick 主动传送」——多几次实体包，但不碰玩家自身状态。
     */
    private final boolean usePassenger;

    /** 玩家 UUID -> 其专属遮挡面。 */
    private final Map<UUID, TextDisplay> overlays = new ConcurrentHashMap<>();

    /** setVisibleByDefault 是否可用；null 表示尚未探测。 */
    private Boolean visibleByDefaultSupported;

    public ThirdCamBlocker(WBDAddon plugin) {
        this.plugin = plugin;
        this.usePassenger = "passenger".equalsIgnoreCase(plugin.getConfig()
                .getString("modules.antithirdcam.follow-mode", "teleport"));
    }

    public void start() {
        long interval = Math.max(1, plugin.getConfig()
                .getLong("modules.antithirdcam.update-interval-ticks", 20));
        // 遮挡面的位置更新方式由 follow-mode 决定，这里只做低频的「检查并在丢失时重建」
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, interval);

        // 非乘客模式必须每 tick 主动把遮挡面挪到玩家身上，否则跟不住移动
        if (!usePassenger) {
            followTask = Bukkit.getScheduler().runTaskTimer(plugin, this::followTick, 20L, 1L);
        }

        cleanupLeftovers();
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (followTask != null) {
            followTask.cancel();
            followTask = null;
        }
        for (TextDisplay display : overlays.values()) {
            removeEntity(display);
        }
        overlays.clear();
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

        // 可见性：每个周期都老老实实对所有人重算一遍，不做增量记录。
        //
        // 重生、切换世界、以及回合出生点那种长距离传送，都会让服务端重新发送实体，
        // 之前的 hideEntity 会一并失效。增量方案只要漏掉其中一个场景，别人的遮挡面
        // 就会重新可见——表现为「一个大黑方块糊脸」，而且极难排查。
        // 代价只是每周期最多 n² 次 hideEntity（幂等操作），小规模服务器无压力。
        if (Boolean.FALSE.equals(visibleByDefaultSupported)) {
            for (Player observer : Bukkit.getOnlinePlayers()) {
                hideAllOverlaysFrom(observer);
            }
        }
    }

    /** 玩家重生：服务端会重新向客户端发送周边实体，之前的隐藏会失效。 */
    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        resyncLater(event.getPlayer());
    }

    /** 切换世界同理：客户端会重建实体列表。 */
    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        resyncLater(event.getPlayer());
    }

    /**
     * 传送同样会让服务端重建实体追踪，隐藏状态可能失效。
     *
     * <p>这条对本插件尤其要紧：WarZBombDefuse 每回合都要把玩家传送到出生点，
     * 若只靠周期性的重隐藏，玩家每回合传送后都会有一段「糊脸」窗口。
     */
    @EventHandler
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        resyncLater(event.getPlayer());
    }

    /**
     * 下一 tick 给该玩家补一次全量隐藏。
     *
     * <p>为什么要延后一 tick：事件触发时客户端还没收到重发的实体包，
     * 此刻的 hideEntity 会被随后到达的实体包盖掉，等于白做。
     */
    private void resyncLater(Player player) {
        if (!Boolean.FALSE.equals(visibleByDefaultSupported)) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                hideAllOverlaysFrom(player);
            }
        });
    }

    /**
     * 非乘客模式下的位置跟随：每 tick 把遮挡面挪到玩家身上。
     *
     * <p>位置随便设，朝向交给 {@code Billboard.CENTER}（永远面向观察者）。
     */
    private void followTick() {
        for (Map.Entry<UUID, TextDisplay> entry : overlays.entrySet()) {
            Player owner = Bukkit.getPlayer(entry.getKey());
            if (owner == null) continue;

            TextDisplay display = entry.getValue();
            if (!display.isValid()) continue;

            display.teleport(owner.getLocation());
        }
    }

    /**
     * 是否需要给该玩家挂遮挡面。
     *
     * <p>只给「竞技场内仍存活」的参赛者挂：阵亡玩家转旁观后全图本来就该看得见，
     * 再挂遮挡面只会把他自己的视野糊掉，纯属干扰。
     */
    private boolean shouldBlock(ArenaManager am, Player player) {
        ArenaSession session = am.getSession(player);
        if (session == null) {
            // 不在任何竞技场：仅当配置要求全服生效时才处理
            return !plugin.getConfig().getBoolean("modules.antithirdcam.only-in-arena", true);
        }

        Team team = session.getTeam(player);
        if (team == null || !team.isPlaying()) return false;

        // team.isPlaying() 对已阵亡的 T/CT 仍为 true，必须另外确认还活着
        PlayerRecord record = session.getRecord(player);
        return record != null && record.isAlive();
    }

    /** 确保该玩家有一个有效的遮挡面（丢了就重建）。 */
    private void ensure(Player player) {
        TextDisplay display = overlays.get(player.getUniqueId());
        if (display != null && display.isValid() && isFollowing(player, display)) {
            // 可见性由每轮的 hideAllOverlaysFrom() 统一重算，这里不必重复隐藏
            return;
        }
        remove(player.getUniqueId());
        overlays.put(player.getUniqueId(), spawn(player));
    }

    /** 遮挡面是否仍正常跟着该玩家。 */
    private boolean isFollowing(Player player, TextDisplay display) {
        if (usePassenger) {
            return player.getPassengers().contains(display);
        }
        // 主动传送模式下只要还在同一个世界就算正常，位置由 followTick() 负责
        return player.getWorld().equals(display.getWorld());
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

        // 乘客模式：骑在玩家身上，位置与朝向由服务端自动同步
        // 非乘客模式：位置由 followTick() 每 tick 主动更新
        if (usePassenger) {
            player.addPassenger(display);
        } else {
            display.teleport(player.getLocation());
        }
        player.showEntity(plugin, display);

        // 可见性：优先用 setVisibleByDefault（Paper / 部分服务端），
        // 不支持则退化为「对其他玩家逐个 hideEntity」
        if (!applyDefaultInvisibility(display)) {
            // 新遮挡面立刻对当前所有其他在线玩家隐藏，不等下一轮重算，
            // 否则新建的遮挡面会有一个周期的可见窗口
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (other.equals(player)) continue;
                other.hideEntity(plugin, display);
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
     * 把除自己那份之外的所有遮挡面，对该玩家隐藏一遍。
     *
     * <p>{@code hideEntity} 是幂等的：已经隐藏过的实体再调一次不会有副作用，
     * 所以可以放心每个周期对所有人重来一遍，不依赖任何增量记录。
     */
    private void hideAllOverlaysFrom(Player observer) {
        UUID observerId = observer.getUniqueId();
        for (Map.Entry<UUID, TextDisplay> entry : overlays.entrySet()) {
            // 自己那份遮挡面要留着，别把本人也挡住
            if (entry.getKey().equals(observerId)) continue;
            observer.hideEntity(plugin, entry.getValue());
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
