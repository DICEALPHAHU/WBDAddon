package alphahu.wbdaddon.module;

import alphahu.wbdaddon.WBDAddon;
import alphahu.wbdaddon.matchstatus.StatusManager;

/**
 * 比赛状态显示模块。
 *
 * <p>功能：
 * <ul>
 *   <li>顶部 ActionBar 显示 T / CT 存活人数</li>
 *   <li>炸弹安放后用 BossBar 显示倒计时进度条</li>
 * </ul>
 *
 * @author AlphaHu
 */
public class MatchStatusModule implements AddonModule {

    private final WBDAddon plugin;

    private StatusManager statusManager;

    public MatchStatusModule(WBDAddon plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getId() {
        return "match-status";
    }

    @Override
    public String getDisplayName() {
        return "比赛状态显示";
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }

    @Override
    public void onEnable() {
        statusManager = new StatusManager(plugin);
        statusManager.start();

        long interval = plugin.getConfig()
                .getLong("modules.match-status.update-interval-ticks", 2);
        boolean aliveBar = plugin.getConfig()
                .getBoolean("modules.match-status.enable-alive-action-bar", true);
        boolean bombBar = plugin.getConfig()
                .getBoolean("modules.match-status.enable-bomb-bar", true);
        plugin.getLogger().info("比赛状态显示模块已启动，刷新间隔 " + interval + " tick。");
        plugin.getLogger().info("头顶显示已启用：存活人数=" + (aliveBar ? "开" : "关")
                + "，炸弹倒计时条=" + (bombBar ? "开" : "关") + "。");
    }

    @Override
    public void onDisable() {
        if (statusManager != null) {
            statusManager.stop();
            statusManager = null;
        }
        plugin.getLogger().info("比赛状态显示模块已卸载。");
    }

    public StatusManager getStatusManager() {
        return statusManager;
    }
}
