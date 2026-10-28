package alphahu.wbdaddon.command;

import alphahu.wbdaddon.WBDAddon;
import alphahu.wbdaddon.module.AddonModule;
import alphahu.wbdaddon.module.SpectateCameraModule;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 观战第一视角 {@code /spectate [玩家|off]}。
 *
 * <p>阵亡后默认走观战模式，用它可以把自己的视角挂到队友身上：
 * 不带参数 = 切下一名存活队友，带名字 = 直接看指定的人，{@code off} = 退出。
 *
 * @author AlphaHu
 */
public class SpectateCommand implements CommandExecutor, TabCompleter {

    private final WBDAddon plugin;

    public SpectateCommand(WBDAddon plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c该命令只能由玩家执行。");
            return true;
        }
        AddonModule module = plugin.getModuleManager().getModule("spectate-camera");
        if (!(module instanceof SpectateCameraModule spectate)
                || !plugin.getModuleManager().isEnabled(module)) {
            player.sendMessage("§c观战第一视角模块未启用。");
            return true;
        }

        if (args.length == 0) {
            spectate.next(player);
            return true;
        }
        if ("off".equalsIgnoreCase(args[0]) || "stop".equalsIgnoreCase(args[0])) {
            spectate.stop(player);
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            player.sendMessage("§c找不到玩家 " + args[0] + "。");
            return true;
        }
        spectate.watch(player, target);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!(sender instanceof Player player) || args.length != 1) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        names.add("off");
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.equals(player)) continue;
            if (online.getName().toLowerCase().startsWith(args[0].toLowerCase())) {
                names.add(online.getName());
            }
        }
        return names;
    }
}
