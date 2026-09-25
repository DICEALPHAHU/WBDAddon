package alphahu.wbdaddon.command;

import alphahu.wbdaddon.WBDAddon;
import alphahu.wbdaddon.module.AddonModule;
import alphahu.wbdaddon.module.BombDefuseSoundModule;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
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
 *   <li>/wbdaddon sound        试听拆弹提示音效</li>
 *   <li>/wbdaddon diag         查看服务端提供了哪些扩展 API</li>
 * </ul>
 *
 * @author AlphaHu
 */
public class WBDAddonCommand implements CommandExecutor, TabCompleter {

    private final WBDAddon plugin;

    private static final List<String> SUB_COMMANDS =
            Arrays.asList("help", "reload", "modules", "sound", "diag");

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

            case "sound" -> playSoundPreview(sender, args);

            case "diag" -> sendDiagnostics(sender);

            default -> sender.sendMessage(color("&c未知子命令。使用 /wbdaddon help 查看帮助。"));
        }

        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(color("&6====== WBDAddon 帮助 ======"));
        sender.sendMessage(color("&e/wbdaddon help &7- 查看帮助"));
        sender.sendMessage(color("&e/wbdaddon reload &7- 重载配置并重启模块"));
        sender.sendMessage(color("&e/wbdaddon modules &7- 查看模块状态"));
        sender.sendMessage(color("&e/wbdaddon sound &7- 试听拆弹提示音效"));
        sender.sendMessage(color("&e/wbdaddon diag &7- 查看服务端提供了哪些扩展 API"));
        sender.sendMessage(color("&6=========================="));
    }

    /**
     * /wbdaddon diag：列出当前服务端提供了哪些扩展 API。
     *
     * <p>本项目按 Paper API 编译，实际却可能跑在 Arclight 这类 Spigot 实现上。
     * 那些 Paper 扩展方法有的根本不存在、有的「签名存在但行为不完整」，
     * 于是会出现「功能没生效，控制台也不报错」的情况。这时先用它把环境看清，
     * 比逐个猜原因快得多。
     */
    private void sendDiagnostics(CommandSender sender) {
        sender.sendMessage(color("&6====== WBDAddon 环境诊断 ======"));
        sender.sendMessage(color("&7服务端：&f" + Bukkit.getName() + " &7" + Bukkit.getVersion()));

        reportMethod(sender, "Player#hideEntity(Plugin,Entity)",
                Player.class, "hideEntity", Plugin.class, Entity.class);
        reportMethod(sender, "Player#showEntity(Plugin,Entity)",
                Player.class, "showEntity", Plugin.class, Entity.class);
        reportMethod(sender, "Entity#setVisibleByDefault(boolean)",
                Entity.class, "setVisibleByDefault", boolean.class);
        reportMethod(sender, "Display#setViewRange(float)",
                Display.class, "setViewRange", float.class);
        reportMethod(sender, "Player#teleportAsync(Location)",
                Player.class, "teleportAsync", Location.class);

        sender.sendMessage(color("&6==============================="));
    }

    /** 反射查一个公开方法在不在，只报「存在/不存在」。 */
    private void reportMethod(CommandSender sender, String label,
                              Class<?> owner, String name, Class<?>... params) {
        boolean present;
        try {
            owner.getMethod(name, params);
            present = true;
        } catch (Throwable t) {
            present = false;
        }
        sender.sendMessage(color("&e" + label + " &7-> "
                + (present ? "&a存在" : "&c不存在（Paper 扩展，本服务端未实现）")));
    }

    /**
     * /wbdaddon sound [音效名] [音调]：在自己位置连播三次，试听拆弹提示音。
     *
     * <p>拆弹音效平时只有真的拆弹才听得到，挑音效时非常不方便，故提供该试听入口。
     * 不带音效名时列出核对过的推荐清单。
     */
    private void playSoundPreview(CommandSender sender, String[] args) {
        if (!sender.hasPermission("wbdaddon.admin")) {
            sender.sendMessage(color("&c你没有权限执行此命令。"));
            return;
        }

        // 声音只能播给玩家，控制台没有听觉
        if (!(sender instanceof Player player)) {
            sender.sendMessage(color("&c该命令只能由玩家执行。"));
            return;
        }

        if (args.length < 2) {
            sendSoundHelp(player);
            return;
        }

        float pitch = 1.0f;
        if (args.length >= 3) {
            try {
                pitch = Float.parseFloat(args[2]);
            } catch (NumberFormatException e) {
                player.sendMessage(color("&c音调必须是数字，例如 1.8（范围约 0.5 - 2.0）。"));
                return;
            }
        }

        AddonModule module = plugin.getModuleManager().getModule("bomb-defuse-sound");
        if (!(module instanceof BombDefuseSoundModule soundModule)
                || !plugin.getModuleManager().isEnabled(module)) {
            player.sendMessage(color("&c拆弹声音模块未启用，无法试听。"));
            return;
        }

        String soundName = args[1];
        if (soundModule.playPreview(player, soundName, pitch)) {
            player.sendMessage(color("&a已试听 &e" + soundName
                    + " &7(音调 " + pitch + ")&a，连播三次。"));
        } else {
            player.sendMessage(color("&c音效名无效：&e" + soundName + "&c，用 /wbdaddon sound 看推荐清单。"));
        }
    }

    /** 列出核对过确实存在的 1.20.1 原版音效，供挑选用。 */
    private void sendSoundHelp(Player player) {
        player.sendMessage(color("&6====== 拆弹音效试听 ======"));
        player.sendMessage(color("&e/wbdaddon sound <音效名> [音调] &7- 连播三次试听"));
        player.sendMessage(color("&7「嘀嘀嘀」电子感（推荐）："));
        player.sendMessage(color("&fBLOCK_NOTE_BLOCK_BIT &7- 8-bit 芯片音，最像电子拆弹器 &8(音调 1.8)"));
        player.sendMessage(color("&fBLOCK_NOTE_BLOCK_IRON_XYLOPHONE &7- 清脆金属敲击 &8(1.5)"));
        player.sendMessage(color("&fBLOCK_NOTE_BLOCK_PLING &7- 柔和铃音 &8(2.0)"));
        player.sendMessage(color("&fBLOCK_NOTE_BLOCK_HAT &7- 机械嗒嗒（旧默认）&8(1.5)"));
        player.sendMessage(color("&7短促点击类："));
        player.sendMessage(color("&fBLOCK_STONE_BUTTON_CLICK_ON &7- 干脆按钮声"));
        player.sendMessage(color("&fUI_BUTTON_CLICK &7- 极轻的点击"));
        player.sendMessage(color("&fBLOCK_LEVER_CLICK &7- 开关咔哒"));
        player.sendMessage(color("&fBLOCK_TRIPWIRE_CLICK_ON &7- 绊线弹起"));
        player.sendMessage(color("&fENTITY_EXPERIENCE_ORB_PICKUP &7- 上扬叮声"));
        player.sendMessage(color("&6=========================="));
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