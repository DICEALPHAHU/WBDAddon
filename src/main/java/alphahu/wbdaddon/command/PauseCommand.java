package alphahu.wbdaddon.command;

import alphahu.wbdaddon.WBDAddon;
import alphahu.wbdaddon.module.AddonModule;
import alphahu.wbdaddon.module.TechnicalPauseModule;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * 玩家暂停入口 {@code /pause}。
 *
 * <p>每队每场限次（默认 1 次），具体判定全部交给
 * {@link TechnicalPauseModule#requestTeamPause(Player)}。
 *
 * @author AlphaHu
 */
public class PauseCommand implements CommandExecutor {

    private final WBDAddon plugin;

    public PauseCommand(WBDAddon plugin) {
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

        AddonModule module = plugin.getModuleManager().getModule("technical-pause");
        if (!(module instanceof TechnicalPauseModule pause)
                || !plugin.getModuleManager().isEnabled(module)) {
            player.sendMessage("§c技术暂停模块未启用。");
            return true;
        }

        pause.requestTeamPause(player);
        return true;
    }
}
