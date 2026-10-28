package alphahu.wbdaddon.command;

import alphahu.wbdaddon.WBDAddon;
import alphahu.wbdaddon.api.AddonMode;
import alphahu.wbdaddon.module.AddonModule;
import alphahu.wbdaddon.module.BombDefuseSoundModule;
import alphahu.wbdaddon.module.LoadoutGuardModule;
import alphahu.wbdaddon.module.TechnicalPauseModule;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * /wbdaddon 主命令。
 *
 * <p>子命令：
 * <ul>
 *   <li>/wbdaddon help         查看帮助</li>
 *   <li>/wbdaddon reload       重载配置文件并重启模块</li>
 *   <li>/wbdaddon modules      查看所有模块的启用状态</li>
 *   <li>/wbdaddon modes        查看已接入的游戏模式（含第三方注册进来的）</li>
 *   <li>/wbdaddon sound        试听拆弹提示音效</li>
 * </ul>
 *
 * @author AlphaHu
 */
public class WBDAddonCommand implements CommandExecutor, TabCompleter {

    private final WBDAddon plugin;

    private static final List<String> SUB_COMMANDS =
            Arrays.asList("help", "reload", "modules", "modes", "sound", "pause", "resume",
                    "retag", "shopscan");

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

            case "modes" -> sendModes(sender);

            case "sound" -> playSoundPreview(sender, args);

            case "pause" -> {
                if (!sender.hasPermission("wbdaddon.admin")) {
                    sender.sendMessage(color("&c你没有权限执行此命令。"));
                    return true;
                }
                TechnicalPauseModule module = pauseModule();
                if (module == null) {
                    sender.sendMessage(color("&c技术暂停模块未启用。"));
                    return true;
                }
                module.requestAdminPause(sender,
                        args.length >= 2 ? args[1] : null,
                        args.length >= 3 ? parseSeconds(sender, args[2]) : null);
            }

            case "resume" -> {
                if (!sender.hasPermission("wbdaddon.admin")) {
                    sender.sendMessage(color("&c你没有权限执行此命令。"));
                    return true;
                }
                TechnicalPauseModule module = pauseModule();
                if (module == null) {
                    sender.sendMessage(color("&c技术暂停模块未启用。"));
                    return true;
                }
                module.requestAdminResume(sender, args.length >= 2 ? args[1] : null);
            }

            case "retag" -> retag(sender, args);

            case "shopscan" -> scanShop(sender);

            default -> sender.sendMessage(color("&c未知子命令。使用 /wbdaddon help 查看帮助。"));
        }

        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(color("&6====== WBDAddon 帮助 ======"));
        sender.sendMessage(color("&e/wbdaddon help &7- 查看帮助"));
        sender.sendMessage(color("&e/wbdaddon reload &7- 重载配置并重启模块"));
        sender.sendMessage(color("&e/wbdaddon modules &7- 查看模块状态"));
        sender.sendMessage(color("&e/wbdaddon modes &7- 查看已接入的游戏模式"));
        sender.sendMessage(color("&e/wbdaddon sound &7- 试听拆弹提示音效"));
        sender.sendMessage(color("&e/wbdaddon pause [竞技场] [秒数] &7- 技术暂停（管理员）"));
        sender.sendMessage(color("&e/wbdaddon resume [竞技场] &7- 恢复比赛（管理员）"));
        sender.sendMessage(color("&e/wbdaddon retag [玩家] &7- 重打装备分类标签"));
        sender.sendMessage(color("&e/wbdaddon shopscan &7- 扫描商店商品并输出分类表片段"));
        sender.sendMessage(color("&e/pause &7- 玩家请求技术暂停（每队每场限次）"));
        sender.sendMessage(color("&6=========================="));
    }

    /**
     * {@code /wbdaddon shopscan}：把 WBD 商店里的商品按当前规则分类，输出成
     * 可以直接粘进 {@code item-categories.yml} 的片段。
     *
     * <p>只读，不改任何文件——服主看过之后再决定要不要写进对照表。
     */
    private void scanShop(CommandSender sender) {
        if (!sender.hasPermission("wbdaddon.admin")) {
            sender.sendMessage(color("&c你没有权限执行此命令。"));
            return;
        }
        AddonModule module = plugin.getModuleManager().getModule("loadout-guard");
        if (!(module instanceof LoadoutGuardModule loadout)
                || !plugin.getModuleManager().isEnabled(module)) {
            sender.sendMessage(color("&c装备槽守卫模块未启用。"));
            return;
        }
        if (plugin.getWbd() == null || plugin.getWbd().getShopManager() == null) {
            sender.sendMessage(color("&c找不到 WBD 商店。"));
            return;
        }

        Map<String, String> guns = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        int total = 0;

        for (var shopItem : plugin.getWbd().getShopManager().getItems()) {
            var give = shopItem.getGiveStack();
            if (give == null || give.getType().isAir()) continue;
            total++;
            String category = loadout.categoryKey(give);
            String gunId = null;
            String type = "-";
            if (plugin.isTacZAvailable() && plugin.getTacZApi() != null) {
                try {
                    gunId = plugin.getTacZApi().getItemGunId(give).orElse(null);
                    if (gunId != null) {
                        type = plugin.getTacZApi().getGun(gunId)
                                .map(com.tacz.bridge.spigot.api.model.TacZGunSummary::type)
                                .orElse("-");
                    }
                } catch (Throwable ignored) {
                    // TacZ 异常时按无 GunId 处理
                }
            }

            String line = shopItem.getId() + " | " + (gunId == null ? "-" : gunId)
                    + " | " + type + " | " + category;
            sender.sendMessage(color("&7" + line));
            plugin.getLogger().info("[shopscan] " + line);

            if (gunId != null) {
                guns.putIfAbsent(gunId, category);
            } else if (give.hasItemMeta() && give.getItemMeta().hasDisplayName()) {
                // 只收真正有自定义显示名的物品；材质名当 key 没意义，还会污染 names 表
                names.putIfAbsent(ChatColor.stripColor(give.getItemMeta().getDisplayName()),
                        category);
            }
        }

        sender.sendMessage(color("&6====== 可粘贴到 item-categories.yml ======"));
        plugin.getLogger().info("[shopscan] ====== 可粘贴到 item-categories.yml 的片段 ======");
        if (!guns.isEmpty()) {
            sender.sendMessage(color("&eguns:"));
            plugin.getLogger().info("[shopscan] guns:");
            for (Map.Entry<String, String> e : guns.entrySet()) {
                String yaml = "  \"" + e.getKey() + "\": " + e.getValue();
                sender.sendMessage(color("&f" + yaml));
                plugin.getLogger().info("[shopscan] " + yaml);
            }
        }
        if (!names.isEmpty()) {
            sender.sendMessage(color("&enames:"));
            plugin.getLogger().info("[shopscan] names:");
            for (Map.Entry<String, String> e : names.entrySet()) {
                String yaml = "  \"" + e.getKey() + "\": " + e.getValue();
                sender.sendMessage(color("&f" + yaml));
                plugin.getLogger().info("[shopscan] " + yaml);
            }
        }
        sender.sendMessage(color("&a扫描完成：&f" + total + " &a件商品，&f"
                + guns.size() + " &a把枪、&f" + names.size() + " &a件非枪物品。"));
        plugin.getLogger().info("[shopscan] 完成：" + total + " 件商品，"
                + guns.size() + " 把枪，" + names.size() + " 件非枪物品。");
    }

    /**
     * {@code /wbdaddon retag [玩家]}：清掉背包里的分类标签并重新分类。
     *
     * <p>装备槽守卫把分类结果写成 PDC 标签存在物品上，认错一次就会一直错；
     * 这个命令用来把标签洗掉重认（比如补了 `gun-overrides` 之后）。
     */
    private void retag(CommandSender sender, String[] args) {
        Player target;
        if (args.length >= 2) {
            if (!sender.hasPermission("wbdaddon.admin")) {
                sender.sendMessage(color("&c你没有权限重打其他玩家的标签。"));
                return;
            }
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(color("&c找不到在线玩家 &e" + args[1] + "&c。"));
                return;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            sender.sendMessage(color("&c控制台需要指定玩家：/wbdaddon retag <玩家>"));
            return;
        }

        AddonModule module = plugin.getModuleManager().getModule("loadout-guard");
        if (!(module instanceof LoadoutGuardModule loadout)
                || !plugin.getModuleManager().isEnabled(module)) {
            sender.sendMessage(color("&c装备槽守卫模块未启用。"));
            return;
        }

        int cleared = loadout.retag(target);
        sender.sendMessage(color("&a已清除 &e" + cleared + " &a件物品的分类标签，并重新分类：&f"
                + target.getName()));
    }

    private TechnicalPauseModule pauseModule() {
        AddonModule module = plugin.getModuleManager().getModule("technical-pause");
        if (module instanceof TechnicalPauseModule pause
                && plugin.getModuleManager().isEnabled(module)) {
            return pause;
        }
        return null;
    }

    private Integer parseSeconds(CommandSender sender, String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            sender.sendMessage(color("&c秒数必须是数字。"));
            return null;
        }
    }

    /**
     * /wbdaddon modes：列出已注册的游戏模式与各自的在线参赛人数。
     *
     * <p>排查「第三方模式接进来但附加功能没生效」时，先看这里：
     * 模式没出现，说明对方没注册成功（多半是加载顺序问题，或没在
     * onEnable 里取 WbdAddonApi）；模式在、人数为 0，说明判定方法写错了。
     */
    private void sendModes(CommandSender sender) {
        sender.sendMessage(color("&6====== WBDAddon 已接入模式 ======"));

        var api = plugin.getApi();
        if (api == null) {
            sender.sendMessage(color("&c模式注册表尚未初始化。"));
            return;
        }

        sender.sendMessage(color("&7WbdAddonApi 版本：&f" + api.getApiVersion()));
        for (AddonMode mode : api.getModes()) {
            String id;
            String name;
            int online;
            try {
                id = mode.getModeId();
                name = mode.getDisplayName();
                online = 0;
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (mode.isParticipating(player)) {
                        online++;
                    }
                }
            } catch (Throwable t) {
                // 第三方实现可能抛任何异常，这里不能让一条坏数据把整张表打断
                sender.sendMessage(color("&e" + mode.getClass().getName()
                        + " &c查询失败：&7" + t));
                continue;
            }
            sender.sendMessage(color("&e" + id + " &7(" + name + ") &f在线 " + online + " 人"));
        }

        sender.sendMessage(color("&7第三方模式实现 AddonMode 并注册 WbdAddonApi 即可接入"));
        sender.sendMessage(color("&6================================="));
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