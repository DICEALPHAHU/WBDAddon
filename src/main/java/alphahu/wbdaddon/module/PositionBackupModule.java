package alphahu.wbdaddon.module;

import alphahu.wbdaddon.WBDAddon;
import alphahu.wbdaddon.api.AddonMode;
import alphahu.wbdaddon.mode.BombDefuseMode;
import com.warz.bombdefuse.api.WbdPlayerJoinArenaEvent;
import com.warz.bombdefuse.api.WbdPlayerLeaveArenaEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进场位置兜底。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>WBD 用 {@code PlayerSnapshot} 记录进场前的背包、位置、血量等，正常
 * {@code leave} 时还原。但服务器重启 / 插件被卸载 / 服务端崩掉时，不一定会走到
 * {@code leave} 流程，玩家就会留在竞技场里 —— 下次上线还在场地中间。
 *
 * <p>这里在 {@link WbdPlayerJoinArenaEvent}（WBD 已完成快照、但还没把他传送进大厅）
 * 把「进来之前的位置」记下来并落盘。之后：
 *
 * <ul>
 *   <li>插件卸载 / 服务器关闭：把仍在场的玩家传回记录的位置</li>
 *   <li>服务器重启后玩家登录：再补一次传送，然后删掉记录</li>
 *   <li>正常离场（{@link WbdPlayerLeaveArenaEvent}）：删掉记录，不干扰 WBD 的还原</li>
 * </ul>
 *
 * <p>只兜底<b>位置</b>；背包、血量、药水效果仍由 WBD 的快照负责。
 *
 * @author AlphaHu
 */
public class PositionBackupModule implements AddonModule, Listener {

    private final WBDAddon plugin;

    private final Map<UUID, Stored> records = new ConcurrentHashMap<>();

    private File file;
    private boolean restoreOnDisable;
    private boolean restoreOnRejoin;

    public PositionBackupModule(WBDAddon plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getId() {
        return "position-backup";
    }

    @Override
    public String getDisplayName() {
        return "进场位置兜底";
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }

    @Override
    public void onEnable() {
        this.restoreOnDisable = plugin.getConfig()
                .getBoolean("modules.position-backup.restore-on-disable", true);
        this.restoreOnRejoin = plugin.getConfig()
                .getBoolean("modules.position-backup.restore-on-rejoin", true);
        this.file = plugin.dataFile("position-backup.yml");

        load();
        Bukkit.getPluginManager().registerEvents(this, plugin);

        plugin.getLogger().info("进场位置兜底已启动："
                + (restoreOnDisable ? "卸载时传回" : "卸载时不传")
                + "，"
                + (restoreOnRejoin ? "重启后登录补传" : "重启后不补传")
                + "，当前待处理 " + records.size() + " 人。");
    }

    @Override
    public void onDisable() {
        // 只在服务器真的在关闭时传回；/wbdaddon reload 这种重载不能把在场玩家踢出去。
        // isStopping() 是 Paper 补的 API，Arclight / 纯 Spigot 上没有这个方
        // 法——直接调用会抛 NoSuchMethodError，连整个重载流程一起带崩，故反射探测。
        if (restoreOnDisable && !plugin.isReloading() && isServerStopping()) {
            restoreAll();
        }
        // 记录故意保留：如果是服务器重启，下次登录还要靠它补一次
        save();
        HandlerList.unregisterAll(this);
        plugin.getLogger().info("进场位置兜底已卸载。");
    }

    /**
     * 当前服务端是否正在关闭。
     *
     * <p>{@code Server#isStopping()} 只有 Paper 系有；拿不到就当作「不是关闭」，
     * 让重启后的登录补传兜底。这样在 Arclight 上至少不会把插件搞崩。
     */
    private boolean isServerStopping() {
        try {
            java.lang.reflect.Method method = Bukkit.getServer().getClass()
                    .getMethod("isStopping");
            Object value = method.invoke(Bukkit.getServer());
            return value instanceof Boolean stopping && stopping;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 记录
    // ------------------------------------------------------------------

    /**
     * 进场时记下「进来之前的位置」。
     *
     * <p>用 {@code MONITOR}：WBD 的 join 流程是「先快照 → 发事件 → 再传送进大厅」，
     * 事件触发时玩家还站在原地，取到的就是进场前的位置。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(WbdPlayerJoinArenaEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;

        Location location = player.getLocation().clone();
        records.put(player.getUniqueId(), new Stored(
                location.getWorld() == null ? "" : location.getWorld().getName(),
                location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch(),
                event.getArenaId()));
        save();

        if (plugin.getConfig().getBoolean("modules.position-backup.debug", false)) {
            plugin.getLogger().info("[position-backup] 记录 " + player.getName()
                    + " 进场位置 " + format(location) + "（竞技场 " + event.getArenaId() + "）");
        }
    }

    /** 正常离场：WBD 会自己还原，删掉记录即可。 */
    @EventHandler
    public void onLeave(WbdPlayerLeaveArenaEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;
        if (records.remove(player.getUniqueId()) != null) {
            save();
        }
    }

    // ------------------------------------------------------------------
    // 恢复
    // ------------------------------------------------------------------

    /** 插件卸载 / 服务器关闭：把仍在场的玩家传回。 */
    private void restoreAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Stored stored = records.get(player.getUniqueId());
            if (stored == null) continue;
            teleport(player, stored);
        }
    }

    /** 服务器重启后登录：补一次传送，然后删掉记录。 */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!restoreOnRejoin) return;

        Player player = event.getPlayer();
        if (!records.containsKey(player.getUniqueId())) return;

        // 等一 tick 让登录流程（AuthMe / WBD）先安定下来
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            Stored stored = records.remove(player.getUniqueId());
            if (stored == null) return;
            teleport(player, stored);
            save();
            if (plugin.getConfig().getBoolean("modules.position-backup.debug", false)) {
                plugin.getLogger().info("[position-backup] 登录补传 " + player.getName()
                        + " 回 " + stored.world + " " + (int) stored.x + " " + (int) stored.y
                        + " " + (int) stored.z);
            }
        }, 1L);
    }

    /** 退服（含关服踢人）时先传回，位置会随玩家数据一起保存。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Stored stored = records.remove(player.getUniqueId());
        if (stored == null) return;
        if (inArena(player, stored.arena)) {
            teleport(player, stored);
        }
        save();
    }

    private boolean inArena(Player player, String arenaId) {
        if (plugin.getApi() == null) return false;
        AddonMode mode = plugin.getApi().getModeFor(player);
        return mode != null && BombDefuseMode.MODE_ID.equals(mode.getModeId())
                && mode.isParticipating(player)
                && (arenaId == null || arenaId.isEmpty() || arenaId.equals(mode.getArenaId(player)));
    }

    private void teleport(Player player, Stored stored) {
        World world = Bukkit.getWorld(stored.world);
        if (world == null) {
            plugin.getLogger().warning("[position-backup] 找不到世界 " + stored.world
                    + "，跳过 " + player.getName() + " 的位置还原。");
            return;
        }
        player.teleport(new Location(world, stored.x, stored.y, stored.z,
                stored.yaw, stored.pitch));
    }

    // ------------------------------------------------------------------
    // 落盘
    // ------------------------------------------------------------------

    private void load() {
        records.clear();
        if (file == null || !file.isFile()) return;

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("players");
        if (section == null) return;

        for (String key : section.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                String world = section.getString(key + ".world");
                if (world == null || world.isEmpty()) continue;
                records.put(id, new Stored(
                        world,
                        section.getDouble(key + ".x"),
                        section.getDouble(key + ".y"),
                        section.getDouble(key + ".z"),
                        (float) section.getDouble(key + ".yaw"),
                        (float) section.getDouble(key + ".pitch"),
                        section.getString(key + ".arena", "")));
            } catch (IllegalArgumentException ignored) {
                // 坏掉的 UUID 直接跳过
            }
        }
    }

    private void save() {
        if (file == null) return;
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, Stored> entry : records.entrySet()) {
            String base = "players." + entry.getKey();
            Stored s = entry.getValue();
            yaml.set(base + ".world", s.world);
            yaml.set(base + ".x", s.x);
            yaml.set(base + ".y", s.y);
            yaml.set(base + ".z", s.z);
            yaml.set(base + ".yaw", s.yaw);
            yaml.set(base + ".pitch", s.pitch);
            yaml.set(base + ".arena", s.arena);
        }
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("[position-backup] 保存位置文件失败：" + e.getMessage());
        }
    }

    private String format(Location location) {
        return location.getWorld() == null ? "?"
                : location.getWorld().getName() + " " + location.getBlockX()
                + " " + location.getBlockY() + " " + location.getBlockZ();
    }

    private static final class Stored {
        final String world;
        final double x;
        final double y;
        final double z;
        final float yaw;
        final float pitch;
        final String arena;

        Stored(String world, double x, double y, double z, float yaw, float pitch, String arena) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
            this.arena = arena;
        }
    }
}
