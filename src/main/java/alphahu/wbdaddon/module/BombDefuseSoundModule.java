package alphahu.wbdaddon.module;

import alphahu.wbdaddon.WBDAddon;
import alphahu.wbdaddon.defusesound.DefuseSoundNotifier;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;

/**
 * 拆弹声音提示模块。
 *
 * <p>WBD 本体在开始拆弹与整个拆弹过程中都是静音的（只有拆除成功与被中断时才有音效），
 * 导致 T 阵营无法察觉 CT 正在拆弹。本模块在 C4 位置向全图播放可定位的
 * 「开始拆弹」提示音与持续的拆弹滴答声，让 T 能据此回防博弈。
 *
 * <p>具体逻辑见 {@link DefuseSoundNotifier}。
 *
 * @author AlphaHu
 */
public class BombDefuseSoundModule implements AddonModule {

    private final WBDAddon plugin;

    private DefuseSoundNotifier notifier;

    public BombDefuseSoundModule(WBDAddon plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getId() {
        return "bomb-defuse-sound";
    }

    @Override
    public String getDisplayName() {
        return "拆弹声音提示";
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }

    @Override
    public void onEnable() {
        notifier = new DefuseSoundNotifier(plugin);
        Bukkit.getPluginManager().registerEvents(notifier, plugin);

        plugin.getLogger().info("拆弹声音提示已启动，听众范围："
                + plugin.getConfig().getString("modules.bomb-defuse-sound.audience", "arena") + "。");
    }

    @Override
    public void onDisable() {
        if (notifier != null) {
            // 显式注销监听器，避免 reload 时同一事件被处理多次
            HandlerList.unregisterAll(notifier);
            notifier.shutdown();
            notifier = null;
        }
        plugin.getLogger().info("拆弹声音提示已卸载。");
    }

    /**
     * 试听音效（供 /wbdaddon sound 使用）。
     *
     * @return 模块未启用或音效名无效时返回 false
     */
    public boolean playPreview(Player player, String soundName, float pitch) {
        return notifier != null && notifier.playPreview(player, soundName, pitch);
    }
}
