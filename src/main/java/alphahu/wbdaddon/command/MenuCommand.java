package alphahu.wbdaddon.command;

import alphahu.wbdaddon.WBDAddon;
import alphahu.wbdaddon.module.AddonModule;
import alphahu.wbdaddon.module.MenuModule;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * 主菜单入口 {@code /wbda}。
 *
 * @author AlphaHu
 */
public class MenuCommand implements CommandExecutor {

    private final WBDAddon plugin;

    public MenuCommand(WBDAddon plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             @NotNull String[] args) {

        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c该命令只能由玩家执行。");
            return true;
        }

        AddonModule module = plugin.getModuleManager().getModule("menu");
        if (!(module instanceof MenuModule menu)
                || !plugin.getModuleManager().isEnabled(module)) {
            player.sendMessage("§c主菜单模块未启用。");
            return true;
        }

        menu.openMain(player);
        return true;
    }
}
