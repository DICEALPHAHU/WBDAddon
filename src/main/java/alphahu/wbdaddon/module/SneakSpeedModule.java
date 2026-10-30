package alphahu.wbdaddon.module;

import alphahu.wbdaddon.WBDAddon;
import alphahu.wbdaddon.api.AddonMode;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 潜行加速。
 *
 * <p>原版潜行只有正常速度的 30%，绕后、贴墙、架点的时候慢得离谱。这里在
 * **蹲下时**给移动速度属性挂一个乘法修饰符，站起来立刻摘掉 —— 只在潜行期间生效，
 * 正常跑动的手感一点不变。
 *
 * <p>用属性而不是药水效果：药水会带 FOV 变化、粒子、以及右上角的图标，
 * 属性修饰符是静默的，数值也更好算（{@code bonus-scalar: 0.7} = 潜行速度 +70%）。
 *
 * <p>只在比赛中的玩家身上生效，大厅与观战不受影响。
 *
 * @author AlphaHu
 */
public class SneakSpeedModule implements AddonModule, Listener {

    private static final String ID = "sneak-speed";

    /** 修饰符名。同一个名字重复添加是幂等的，便于每次重载干净地重挂。 */
    private static final String MODIFIER_NAME = "wbdaddon_sneak_speed";

    private final WBDAddon plugin;

    /** 已经挂上修饰符的玩家，避免重复添加。 */
    private final Map<UUID, Boolean> applied = new ConcurrentHashMap<>();

    private BukkitTask task;
    private boolean enabled;
    private boolean onlyInArena;
    private double bonusScalar;

    public SneakSpeedModule(WBDAddon plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "潜行加速";
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }

    @Override
    public void onEnable() {
        load();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        // 兜底同步：潜行状态偶尔会与事件不同步（比如被传送到别的世界）
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, 40L, 20L);
        plugin.getLogger().info("潜行加速已启动：蹲下时移动速度 +"
                + Math.round(bonusScalar * 100) + "%。");
    }

    @Override
    public void onDisable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        HandlerList.unregisterAll(this);
        for (Player player : Bukkit.getOnlinePlayers()) {
            clear(player);
        }
        applied.clear();
        plugin.getLogger().info("潜行加速已卸载。");
    }

    private void load() {
        String base = "modules." + ID + ".";
        enabled = plugin.getConfig().getBoolean(base + "enabled", true);
        onlyInArena = plugin.getConfig().getBoolean(base + "only-in-arena", true);
        bonusScalar = plugin.getConfig().getDouble(base + "bonus-scalar", 0.7);
        if (bonusScalar < 0) bonusScalar = 0;
    }

    public void reload() {
        load();
        for (Player player : Bukkit.getOnlinePlayers()) {
            clear(player);
            if (player.isSneaking()) {
                apply(player);
            }
        }
    }

    // ------------------------------------------------------------------
    // 逻辑
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSneak(PlayerToggleSneakEvent event) {
        if (!enabled) return;
        Player player = event.getPlayer();
        if (event.isSneaking()) {
            apply(player);
        } else {
            clear(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        applied.remove(event.getPlayer().getUniqueId());
    }

    private void tickAll() {
        if (!enabled) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isSneaking()) {
                apply(player);
            } else if (applied.containsKey(player.getUniqueId())) {
                clear(player);
            }
        }
    }

    private boolean eligible(Player player) {
        if (onlyInArena) {
            if (plugin.getApi() == null) return false;
            AddonMode mode = plugin.getApi().getModeFor(player);
            return mode != null && mode.isPlaying(player);
        }
        return true;
    }

    private void apply(Player player) {
        if (!eligible(player)) {
            clear(player);
            return;
        }
        AttributeInstance attribute = player.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED);
        if (attribute == null) return;

        for (AttributeModifier modifier : attribute.getModifiers()) {
            if (MODIFIER_NAME.equals(modifier.getName())) {
                applied.put(player.getUniqueId(), Boolean.TRUE);
                return;
            }
        }
        attribute.addModifier(new AttributeModifier(MODIFIER_NAME, bonusScalar,
                AttributeModifier.Operation.ADD_SCALAR));
        applied.put(player.getUniqueId(), Boolean.TRUE);
    }

    private void clear(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED);
        if (attribute != null) {
            for (AttributeModifier modifier : attribute.getModifiers()) {
                if (MODIFIER_NAME.equals(modifier.getName())) {
                    attribute.removeModifier(modifier);
                }
            }
        }
        applied.remove(player.getUniqueId());
    }
}
