package alphahu.wbdaddon.module;

import alphahu.wbdaddon.WBDAddon;
import alphahu.wbdaddon.antithirdcam.ThirdCamBlocker;

/**
 * 防第三人称偷看模块。
 *
 * <p>第三人称视角是纯客户端按键，服务端无法真正禁用或侦测，本模块通过
 * 「给参赛玩家挂只对本人可见的巨大遮挡面」的方式，让第三人称失去偷看价值。
 * 详见 {@link ThirdCamBlocker}。
 *
 * <p>只在竞技场内生效：观战者、大厅玩家不受影响。
 *
 * @author AlphaHu
 */
public class AntiThirdCamModule implements AddonModule {

    private final WBDAddon plugin;

    private ThirdCamBlocker blocker;

    public AntiThirdCamModule(WBDAddon plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getId() {
        return "antithirdcam";
    }

    @Override
    public String getDisplayName() {
        return "防第三人称";
    }

    @Override
    public boolean isEnabledByDefault() {
        return false;
    }

    @Override
    public void onEnable() {
        blocker = new ThirdCamBlocker(plugin);
        blocker.start();

        long interval = plugin.getConfig()
                .getLong("modules.antithirdcam.update-interval-ticks", 20);
        plugin.getLogger().info("防第三人称已启动，检查间隔 " + interval + " tick。");
    }

    @Override
    public void onDisable() {
        if (blocker != null) {
            blocker.stop();
            blocker = null;
        }
        plugin.getLogger().info("防第三人称已卸载。");
    }
}
