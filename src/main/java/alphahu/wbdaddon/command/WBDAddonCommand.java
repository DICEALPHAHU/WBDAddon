package alphahu.wbdaddon.command;

import alphahu.wbdaddon.WBDAddon;
import alphahu.wbdaddon.module.AddonModule;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * /wbdaddon 主命令。
 *
 * <p>子命令：
 * <ul>
 *   <li>/wbdaddon help         查看帮助</li>
 *   <li>/wbdaddon reload       重载配置文件并重启模块</li>
 *   <li>/wbdaddon modules      查看所有模块的启用状态</li>
 * </ul>
 *
 * @author AlphaHu
 */
public class WBDAddonCommand implements CommandExecutor, TabCompleter {

    private final WBDAddon plugin;

    private static final List<String> SUB_COMMANDS = Arrays.asList("help", "reload", "modules");

    public WBDAddonCommand(WBDAddon plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             @NotNull String[] args) {

        // 无参数时显示帮助
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {

            case "help" -> sendHelp(sender);

            case "reload" -> {
                if (!sender.hasPermission("wbdaddon.admin")) {
                    sender.sendMessage(color("&c你没有权限执行此命令。"));
                    return true;
                }
                try {
                    plugin.reload();
                    sender.sendMessage(color("&aWBDAddon 配置已重载，模块已重新启停。"));
                } catch (Exception e) {
                    sender.sendMessage(color("&c重载失败：" + e.getMessage()));
                    plugin.getLogger().warning("重载配置时出错：" + e.getMessage());
                }
            }

            case "modules" -> {
                sender.sendMessage(color("&6====== WBDAddon 模块状态 ======"));
                for (AddonModule module : plugin.getModuleManager().getModules()) {
                    boolean enabled = plugin.getModuleManager().isEnabled(module);
                    String status = enabled ? "&a启用" : "&c禁用";
                    sender.sendMessage(color("&e" + module.getId()
                            + " &7(" + module.getDisplayName() + ") " + status));
                }
                sender.sendMessage(color("&6==============================="));
            }

            default -> sender.sendMessage(color("&c未知子命令。使用 /wbdaddon help 查看帮助。"));
        }

        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(color("&6====== WBDAddon 帮助 ======"));
        sender.sendMessage(color("&e/wbdaddon help &7- 查看帮助"));
        sender.sendMessage(color("&e/wbdaddon reload &7- 重载配置并重启模块"));
        sender.sendMessage(color("&e/wbdaddon modules &7- 查看模块状态"));
        sender.sendMessage(color("&6=========================="));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
                                                @NotNull Command command,
                                                @NotNull String alias,
                                                @NotNull String[] args) {
        if (args.length == 1) {
            String input = args[0].toLowerCase();
            return SUB_COMMANDS.stream()
                    .filter(s -> s.startsWith(input))
                    .collect(Collectors.toList());
        }
        return new ArrayList<>();
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}