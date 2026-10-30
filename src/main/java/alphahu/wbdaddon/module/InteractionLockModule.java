package alphahu.wbdaddon.module;

import alphahu.wbdaddon.WBDAddon;
import alphahu.wbdaddon.api.AddonMode;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 战局内的交互限制。
 *
 * <p>默认只拦活板门：竞技场里放一块活板门，玩家右键就能开合、站上去、卡住，
 * 有的地图还能借它上到不该去的位置。直接把这类方块在战局内锁掉最省事。
 *
 * <p>只拦「比赛中的玩家」，大厅与观战不受影响；要拦别的东西往
 * {@code blocked-materials} 里加材质名即可。
 *
 * @author AlphaHu
 */
public class InteractionLockModule implements AddonModule, Listener {

    private static final String ID = "interaction-lock";

    private final WBDAddon plugin;

    /** 战局内禁止交互的方块。 */
    private final Set<Material> blocked = EnumSet.noneOf(Material.class);

    /** 是否连所有活板门一起拦（现代 Material 里是 OAK_TRAPDOOR / SPRUCE_TRAPDOOR …）。 */
    private boolean blockTrapdoors = true;

    private boolean enabled;

    public InteractionLockModule(WBDAddon plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "交互限制";
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }

    @Override
    public void onEnable() {
        load();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("交互限制已启动：战局内禁止交互 "
                + (blockTrapdoors ? "活板门" : "")
                + (blocked.isEmpty() ? "" : (blockTrapdoors ? " / " : "") + blocked.size() + " 种方块")
                + (blockTrapdoors || !blocked.isEmpty() ? "" : "（未配置）") + "。");
    }

    @Override
    public void onDisable() {
        HandlerList.unregisterAll(this);
        plugin.getLogger().info("交互限制已卸载。");
    }

    private void load() {
        blocked.clear();
        String base = "modules." + ID + ".";
        enabled = plugin.getConfig().getBoolean(base + "enabled", true);
        blockTrapdoors = plugin.getConfig().getBoolean(base + "block-trapdoors", true);

        for (String name : plugin.getConfig().getStringList(base + "blocked-materials")) {
            String key = name.trim().toUpperCase(Locale.ROOT);
            // 1.13 之后活板门不再叫 TRAPDOOR，写 TRAPDOOR 时自动展开成所有变种
            if ("TRAPDOOR".equals(key)) {
                blockTrapdoors = true;
                continue;
            }
            Material material = Material.matchMaterial(key);
            if (material == null) {
                plugin.getLogger().warning("交互限制：材质 " + name + " 不存在，已忽略。");
                continue;
            }
            blocked.add(material);
        }
    }

    /** 重载时由模块管理器重新调用 onEnable，这里再读一次配置。 */
    public void reload() {
        load();
    }

    private boolean isBlocked(Material material) {
        if (blocked.contains(material)) return true;
        // 用 contains 而不是 endsWith：模组方块常见 XX_TRAPDOOR / TRAPDOOR_XX 两种写法
        return blockTrapdoors && material.name().toUpperCase(Locale.ROOT).contains("TRAPDOOR");
    }

    /**
     * 注意这里没加 {@code ignoreCancelled}：别的插件先把事件取消掉也无所谓，
     * 我们照样要拦（有些客户端能在事件被取消的情况下打开活板门）。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (!enabled) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getClickedBlock() == null) return;
        if (!isBlocked(event.getClickedBlock().getType())) return;

        Player player = event.getPlayer();
        if (plugin.getApi() == null) return;
        AddonMode mode = plugin.getApi().getModeFor(player);
        boolean playing = mode != null && mode.isPlaying(player);

        if (plugin.getConfig().getBoolean("modules." + ID + ".debug", false)) {
            plugin.getLogger().info("[interaction-lock] " + player.getName()
                    + " 右键 " + event.getClickedBlock().getType()
                    + " / 模式=" + (mode == null ? "无" : mode.getModeId())
                    + " / 比赛中=" + playing
                    + " / 已取消=" + event.isCancelled());
        }
        boolean onlyInArena = plugin.getConfig()
                .getBoolean("modules." + ID + ".only-in-arena", true);
        if (onlyInArena && !playing) return;

        event.setCancelled(true);
        if (plugin.getConfig().getBoolean("modules." + ID + ".notify", false)) {
            player.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&',
                    plugin.getConfig().getString("modules." + ID + ".notify-message",
                            "&7战局内不能使用这个。")));
        }
    }
}
