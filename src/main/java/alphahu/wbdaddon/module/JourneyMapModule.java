package alphahu.wbdaddon.module;

import alphahu.wbdaddon.WBDAddon;
import alphahu.wbdaddon.journeymap.TeamSynchronizer;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

/**
 * JourneyMap 桥接模块。
 *
 * <p>把 WarZBombDefuse 的队伍数据同步到原版 Scoreboard Team，
 * 配合 JourneyMap Teams 实现队友可见、敌军隐藏、观战消失。
 *
 * <p>本模块不依赖 JourneyMap 服务端插件，只操作原版 Team。
 * JourneyMap 客户端会自行读取原版 Team 数据。
 *
 * @author AlphaHu
 */
public class JourneyMapModule implements AddonModule {

    private final WBDAddon plugin;

    private TeamSynchronizer synchronizer;
    private BukkitTask syncTask;

    public JourneyMapModule(WBDAddon plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getId() {
        return "journeymap-bridge";
    }

    @Override
    public String getDisplayName() {
        return "JourneyMap 桥接";
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }

    @Override
    public void onEnable() {
        this.synchronizer = new TeamSynchronizer(plugin);

        // 同步间隔从配置读取，默认 20 tick = 1 秒
        long interval = plugin.getConfig()
                .getLong("modules.journeymap-bridge.sync-interval-ticks", 20L);

        this.syncTask = Bukkit.getScheduler().runTaskTimer(
                plugin, synchronizer::syncAllPlayers, 0L, interval);

        plugin.getLogger().info("JourneyMap 桥接模块已启动，同步间隔 " + interval + " tick。");
    }

    @Override
    public void onDisable() {
        // 取消同步任务
        if (syncTask != null) {
            syncTask.cancel();
            syncTask = null;
        }

        // 清理本模块创建的所有原版队伍
        if (synchronizer != null) {
            synchronizer.cleanupAllTeams();
            synchronizer = null;
        }

        plugin.getLogger().info("JourneyMap 桥接模块已卸载，队伍标签已清理。");
    }
}