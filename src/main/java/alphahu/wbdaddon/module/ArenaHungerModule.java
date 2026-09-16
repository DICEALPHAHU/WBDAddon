package alphahu.wbdaddon.module;

import alphahu.wbdaddon.WBDAddon;
import com.warz.bombdefuse.arena.ArenaManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * 竞技场饥饿锁定模块。
 *
 * <p>竞技场内玩家跑步会消耗饥饿，饿到跑不动不公平；满饥饿又触发自然回血，同样不公平。
 * MC 规则：跑速只在饥饿 &lt; 6 时变慢，自然回血需要饥饿 ≥ 18。
 * 因此把竞技场玩家饥饿恒定到 17（跑速恒定、不自然回血），并清空饱和度为封死回血条件。
 *
 * <p>只作用于竞技场内玩家，不碰全局、不影响大厅和其他地图。
 *
 * @author AlphaHu
 */
public class ArenaHungerModule implements AddonModule {

    private final WBDAddon plugin;

    private BukkitTask task;

    public ArenaHungerModule(WBDAddon plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getId() {
        return "arena-food";
    }

    @Override
    public String getDisplayName() {
        return "竞技场饥饿锁定";
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }

    @Override
    public void onEnable() {
        long interval = Math.max(1, plugin.getConfig()
                .getLong("modules.arena-food.update-interval-ticks", 5));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::lockFood, 0L, interval);
        plugin.getLogger().info("竞技场饥饿锁定已启动，恒定饥饿值。间隔 " + interval + " tick。");
    }

    @Override
    public void onDisable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        plugin.getLogger().info("竞技场饥饿锁定已卸载。");
    }

    /** 把竞技场内玩家的饥饿/饱和度重置到安全值。 */
    private void lockFood() {
        ArenaManager am = plugin.getWbd().getArenaManager();
        if (am == null) return;

        int food = plugin.getConfig().getInt("modules.arena-food.food-level", 17);
        float sat = (float) plugin.getConfig().getDouble("modules.arena-food.saturation", 0.0);

        for (Player player : Bukkit.getOnlinePlayers()) {
            // 只处理竞技场内的玩家；getSession 为 null 表示不在任何竞技场
            if (am.getSession(player) != null) {
                player.setFoodLevel(food);
                player.setSaturation(sat);
            }
        }
    }
}
