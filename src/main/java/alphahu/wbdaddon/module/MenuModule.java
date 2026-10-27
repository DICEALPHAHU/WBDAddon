package alphahu.wbdaddon.module;

import alphahu.wbdaddon.WBDAddon;
import alphahu.wbdaddon.api.AddonMode;
import alphahu.wbdaddon.mode.BombDefuseMode;
import com.warz.bombdefuse.api.WbdMatchEndEvent;
import com.warz.bombdefuse.arena.ArenaSession;
import com.warz.bombdefuse.arena.PlayerRecord;
import com.warz.bombdefuse.config.ArenaConfig;
import com.warz.bombdefuse.model.GameState;
import com.warz.bombdefuse.model.JoinMode;
import com.warz.bombdefuse.model.Team;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 主菜单（/wbda）。
 *
 * <p>箱子 UI 结构：
 * <pre>
 * 主菜单
 *  ├─ 模式选择
 *  │    ├─ 爆破模式
 *  │    │    ├─ 匹配 → 自动匹配 / 手动选择房间
 *  │    │    └─ 当前比赛 → 选阵营（T / CT / 自动 / 观战）
 *  │    └─ 团队死斗（接入 WBDTeamDeathmatch 时出现）
 *  ├─ 比赛总结
 *  └─ 个人资料
 * </pre>
 *
 * <p>爆破模式直接调 WBD 的 {@code ArenaSession.join(player, JoinMode)}；
 * 团队死斗通过 {@code /tdm join <场地> [t|ct]}，因为 TDM 没有对外的加入 API。
 *
 * @author AlphaHu
 */
public class MenuModule implements AddonModule, Listener {

    private final WBDAddon plugin;

    /** 各竞技场最近一场比赛的结果。 */
    private final Map<String, LastResult> lastResults = new ConcurrentHashMap<>();

    public MenuModule(WBDAddon plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getId() {
        return "menu";
    }

    @Override
    public String getDisplayName() {
        return "主菜单";
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("主菜单已启动：/wbda 打开。");
    }

    @Override
    public void onDisable() {
        HandlerList.unregisterAll(this);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder) {
                player.closeInventory();
            }
        }
        lastResults.clear();
        plugin.getLogger().info("主菜单已卸载。");
    }

    // ------------------------------------------------------------------
    // 入口
    // ------------------------------------------------------------------

    public void openMain(Player player) {
        open(player, Screen.MAIN, null, null);
    }

    // ------------------------------------------------------------------
    // 点击
    // ------------------------------------------------------------------

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof MenuHolder holder)) return;

        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null
                || !event.getClickedInventory().equals(top)) {
            return;
        }
        handle(player, holder, event.getSlot());
    }

    private void handle(Player player, MenuHolder holder, int slot) {
        switch (holder.screen) {
            case MAIN -> {
                if (slot == 11) {
                    open(player, Screen.MODES, null, null);
                } else if (slot == 13) {
                    open(player, Screen.SUMMARY, null, null);
                } else if (slot == 15) {
                    open(player, Screen.PROFILE, null, null);
                } else if (slot == 22) {
                    player.closeInventory();
                }
            }
            case MODES -> {
                if (slot == 22) {
                    open(player, Screen.MAIN, null, null);
                    return;
                }
                List<AddonMode> modes = modes();
                int index = slot - 10;
                if (index >= 0 && index < modes.size()) {
                    open(player, Screen.MODE, modes.get(index).getModeId(), null);
                }
            }
            case MODE -> {
                if (slot == 22) {
                    open(player, Screen.MODES, null, null);
                } else if (slot == 11) {
                    open(player, Screen.MATCHING, holder.modeId, null);
                } else if (slot == 15) {
                    open(player, Screen.ACTIVE, holder.modeId, null);
                }
            }
            case MATCHING -> {
                if (slot == 22) {
                    open(player, Screen.MODE, holder.modeId, null);
                } else if (slot == 11) {
                    autoMatch(player, holder.modeId);
                } else if (slot == 15) {
                    open(player, Screen.ROOMS, holder.modeId, null);
                }
            }
            case ROOMS, ACTIVE -> {
                if (slot == 49) {
                    open(player, Screen.MODE, holder.modeId, null);
                    return;
                }
                if (slot >= 45) return;
                List<String> entries = holder.screen == Screen.ROOMS
                        ? roomIds(holder.modeId) : activeIds(holder.modeId);
                if (slot < entries.size()) {
                    open(player, Screen.TEAM, holder.modeId, entries.get(slot));
                }
            }
            case TEAM -> {
                if (slot == 22) {
                    open(player, Screen.MODE, holder.modeId, null);
                } else if (slot == 10) {
                    join(player, holder.modeId, holder.arenaId, "T");
                } else if (slot == 12) {
                    join(player, holder.modeId, holder.arenaId, "CT");
                } else if (slot == 14) {
                    join(player, holder.modeId, holder.arenaId, "AUTO");
                } else if (slot == 16) {
                    join(player, holder.modeId, holder.arenaId, "SPECTATE");
                }
            }
            case SUMMARY -> {
                if (slot == 49) {
                    open(player, Screen.MAIN, null, null);
                }
            }
            case PROFILE -> {
                if (slot == 22) {
                    open(player, Screen.MAIN, null, null);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 打开 / 渲染
    // ------------------------------------------------------------------

    private void open(Player player, Screen screen, String modeId, String arenaId) {
        MenuHolder holder = new MenuHolder(screen, modeId, arenaId);
        Inventory inventory = Bukkit.createInventory(holder, size(screen),
                color(title(screen, modeId)));
        holder.inventory = inventory;
        render(inventory, holder, player);
        player.openInventory(inventory);
    }

    private int size(Screen screen) {
        return screen == Screen.ROOMS || screen == Screen.ACTIVE || screen == Screen.SUMMARY
                ? 54 : 27;
    }

    private String title(Screen screen, String modeId) {
        return switch (screen) {
            case MAIN -> "&8WBD 主菜单";
            case MODES -> "&8模式选择";
            case MODE -> "&8" + modeName(modeId);
            case MATCHING -> "&8匹配 - " + modeName(modeId);
            case ROOMS -> "&8选择房间 - " + modeName(modeId);
            case ACTIVE -> "&8当前比赛 - " + modeName(modeId);
            case TEAM -> "&8选择阵营";
            case SUMMARY -> "&8比赛总结";
            case PROFILE -> "&8个人资料";
        };
    }

    private void render(Inventory inventory, MenuHolder holder, Player player) {
        switch (holder.screen) {
            case MAIN -> {
                inventory.setItem(11, item(Material.COMPASS, "&e模式选择",
                        "&7爆破模式 / 团队死斗"));
                inventory.setItem(13, item(Material.WRITTEN_BOOK, "&e比赛总结",
                        "&7查看各竞技场最近一场结果"));
                inventory.setItem(15, item(Material.PLAYER_HEAD, "&e个人资料",
                        "&7查看自己的数据"));
                inventory.setItem(22, item(Material.BARRIER, "&c关闭"));
            }
            case MODES -> {
                List<AddonMode> modes = modes();
                for (int i = 0; i < modes.size() && i < 18; i++) {
                    AddonMode mode = modes.get(i);
                    inventory.setItem(10 + i, item(modeIcon(mode.getModeId()),
                            "&e" + mode.getDisplayName(),
                            "&7ID: &f" + mode.getModeId(),
                            "&a点击进入"));
                }
                inventory.setItem(22, item(Material.ARROW, "&7返回"));
            }
            case MODE -> {
                if (isBomb(holder.modeId) || isTdm(holder.modeId)) {
                    inventory.setItem(11, item(Material.COMPASS, "&e匹配",
                            "&7自动匹配 / 手动选择房间"));
                    inventory.setItem(15, item(Material.CLOCK, "&e当前比赛",
                            "&7加入正在进行的比赛"));
                } else {
                    inventory.setItem(13, item(Material.PAPER, "&7该模式暂未接入匹配"));
                }
                inventory.setItem(22, item(Material.ARROW, "&7返回"));
            }
            case MATCHING -> {
                inventory.setItem(11, item(Material.ENDER_PEARL, "&e自动匹配",
                        "&7自动找一个可加入的房间"));
                inventory.setItem(15, item(Material.CHEST, "&e手动选择房间",
                        "&7从列表里挑一个"));
                inventory.setItem(22, item(Material.ARROW, "&7返回"));
            }
            case ROOMS -> {
                List<String> ids = roomIds(holder.modeId);
                for (int i = 0; i < ids.size() && i < 45; i++) {
                    inventory.setItem(i, roomItem(holder.modeId, ids.get(i)));
                }
                if (ids.isEmpty()) {
                    inventory.setItem(22, item(Material.PAPER, "&7暂无可用房间"));
                }
                inventory.setItem(49, item(Material.ARROW, "&7返回"));
            }
            case ACTIVE -> {
                List<String> ids = activeIds(holder.modeId);
                for (int i = 0; i < ids.size() && i < 45; i++) {
                    inventory.setItem(i, activeItem(holder.modeId, ids.get(i)));
                }
                if (ids.isEmpty()) {
                    inventory.setItem(22, item(Material.PAPER, "&7当前没有进行中的比赛"));
                }
                inventory.setItem(49, item(Material.ARROW, "&7返回"));
            }
            case TEAM -> {
                inventory.setItem(10, item(Material.RED_WOOL, "&cT 方"));
                inventory.setItem(12, item(Material.BLUE_WOOL, "&9CT 方"));
                inventory.setItem(14, item(Material.WHITE_WOOL, "&f自动分配"));
                inventory.setItem(16, item(Material.GRAY_WOOL, "&7观战"));
                inventory.setItem(22, item(Material.ARROW, "&7返回"));
            }
            case SUMMARY -> {
                List<ArenaSession> sessions = sessions();
                for (int i = 0; i < sessions.size() && i < 45; i++) {
                    ArenaSession session = sessions.get(i);
                    String id = session.getConfig().getId();
                    LastResult result = lastResults.get(id);
                    if (result == null) {
                        inventory.setItem(i, item(Material.BOOK, "&e" + id,
                                "&7最近比赛：&f暂无记录"));
                    } else {
                        inventory.setItem(i, item(Material.BOOK, "&e" + id,
                                "&7胜者：&f" + result.winner,
                                "&7比分：&cT " + result.tScore + " &7: &9CT " + result.ctScore));
                    }
                }
                inventory.setItem(49, item(Material.ARROW, "&7返回"));
            }
            case PROFILE -> renderProfile(inventory, player);
        }
    }

    private void renderProfile(Inventory inventory, Player player) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = head.getItemMeta();
        if (meta instanceof SkullMeta skull) {
            skull.setOwningPlayer(player);
        }
        if (meta != null) {
            meta.setDisplayName(color("&e" + player.getName()));

            List<String> lore = new ArrayList<>();
            AddonMode mode = plugin.getApi() == null ? null : plugin.getApi().getModeFor(player);
            if (mode == null || !mode.isParticipating(player)) {
                lore.add(color("&7当前未参赛"));
            } else {
                lore.add(color("&7模式：&f" + mode.getDisplayName()));
                lore.add(color("&7竞技场：&f" + mode.getArenaName(player)
                        + " &8(" + mode.getArenaId(player) + ")"));
                String team = mode.getTeamId(player);
                lore.add(color("&7阵营：&f" + (team == null ? "观战" : team)));

                if (isBomb(mode.getModeId())) {
                    ArenaSession session = session(mode.getArenaId(player));
                    PlayerRecord record = session == null ? null : session.getRecord(player);
                    if (record != null) {
                        lore.add(color("&7击杀：&c" + record.getKills()
                                + " &7死亡：&f" + record.getDeaths()
                                + " &7K/D：&f" + ratio(record.getKills(), record.getDeaths())));
                        lore.add(color("&7下包：&f" + record.getPlants()
                                + " &7拆包：&f" + record.getDefuses()
                                + " &7资金：&f$" + record.getMoney()));
                    }
                }
            }
            meta.setLore(lore);
            head.setItemMeta(meta);
        }
        inventory.setItem(13, head);
        inventory.setItem(22, item(Material.ARROW, "&7返回"));
    }

    private String ratio(int kills, int deaths) {
        if (deaths <= 0) return kills == 0 ? "0.00" : String.valueOf(kills);
        return String.format("%.2f", kills / (double) deaths);
    }

    // ------------------------------------------------------------------
    // 数据
    // ------------------------------------------------------------------

    private List<AddonMode> modes() {
        if (plugin.getApi() == null) return List.of();
        return plugin.getApi().getModes();
    }

    private ArenaSession session(String arenaId) {
        if (plugin.getWbd() == null || plugin.getWbd().getArenaManager() == null) return null;
        return plugin.getWbd().getArenaManager().getSession(arenaId);
    }

    private List<ArenaSession> sessions() {
        if (plugin.getWbd() == null || plugin.getWbd().getArenaManager() == null) {
            return List.of();
        }
        List<ArenaSession> list = new ArrayList<>(plugin.getWbd().getArenaManager().getSessions());
        list.sort(Comparator.comparing(s -> s.getConfig().getId()));
        return list;
    }

    /** 可加入的房间：爆破取 WBD 会话；死斗取 arenas.yml 里启用的场地。 */
    private List<String> roomIds(String modeId) {
        if (isTdm(modeId)) return tdmArenaIds();
        List<String> ids = new ArrayList<>();
        for (ArenaSession session : sessions()) {
            GameState state = session.getState();
            if (state == GameState.WAITING || state == GameState.COUNTDOWN) {
                ids.add(session.getConfig().getId());
            }
        }
        return ids;
    }

    /** 当前进行中的比赛。 */
    private List<String> activeIds(String modeId) {
        if (isTdm(modeId)) return tdmArenaIds();
        List<String> ids = new ArrayList<>();
        for (ArenaSession session : sessions()) {
            GameState state = session.getState();
            if (state == GameState.LIVE || state == GameState.FREEZE
                    || state == GameState.COUNTDOWN) {
                ids.add(session.getConfig().getId());
            }
        }
        return ids;
    }

    private ItemStack roomItem(String modeId, String arenaId) {
        if (isTdm(modeId)) {
            return item(Material.IRON_SWORD, "&e" + arenaId, "&7团队死斗场地", "&a点击加入");
        }
        ArenaSession session = session(arenaId);
        if (session == null) return item(Material.BARRIER, "&c" + arenaId);
        ArenaConfig config = session.getConfig();
        return item(Material.MAP, "&e" + arenaId,
                "&7状态：&f" + session.getState(),
                "&7人数：&f" + session.getPlayers().size() + "&7/&f" + config.getMaxPlayers(),
                "&a点击选择阵营");
    }

    private ItemStack activeItem(String modeId, String arenaId) {
        if (isTdm(modeId)) {
            return item(Material.IRON_SWORD, "&e" + arenaId, "&7团队死斗场地", "&a点击加入");
        }
        ArenaSession session = session(arenaId);
        if (session == null) return item(Material.BARRIER, "&c" + arenaId);
        return item(Material.CLOCK, "&e" + arenaId,
                "&7状态：&f" + session.getState(),
                "&7比分：&cT " + session.getScore(Team.T)
                        + " &7: &9CT " + session.getScore(Team.CT),
                "&a点击选择阵营");
    }

    /** 读取 TDM 的 arenas.yml（只读，不改动对方文件）。 */
    private List<String> tdmArenaIds() {
        List<String> ids = new ArrayList<>();
        File file = new File(plugin.getDataFolder().getParentFile(),
                "WBDTeamDeathmatch/arenas.yml");
        if (!file.isFile()) return ids;
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection section = yaml.getConfigurationSection("arenas");
            if (section == null) return ids;
            for (String id : section.getKeys(false)) {
                if (section.getBoolean(id + ".enabled", true)) {
                    ids.add(id);
                }
            }
        } catch (RuntimeException ignored) {
            // 对方配置坏了就返回空列表，不影响菜单其它部分
        }
        ids.sort(String::compareTo);
        return ids;
    }

    // ------------------------------------------------------------------
    // 加入
    // ------------------------------------------------------------------

    private void autoMatch(Player player, String modeId) {
        if (isTdm(modeId)) {
            player.closeInventory();
            player.performCommand("tdm join");
            return;
        }
        ArenaSession best = null;
        for (ArenaSession session : sessions()) {
            GameState state = session.getState();
            if (state != GameState.WAITING && state != GameState.COUNTDOWN) continue;
            if (session.getPlayers().size() >= session.getConfig().getMaxPlayers()) continue;
            if (best == null || session.getPlayers().size() > best.getPlayers().size()) {
                best = session;
            }
        }
        if (best == null) {
            player.sendMessage(color("&c没有可自动加入的爆破房间。"));
            return;
        }
        joinBomb(player, best.getConfig().getId(), JoinMode.AUTO);
    }

    private void join(Player player, String modeId, String arenaId, String team) {
        if (arenaId == null) return;
        if (isTdm(modeId)) {
            player.closeInventory();
            String suffix = switch (team) {
                case "T" -> " t";
                case "CT" -> " ct";
                default -> "";
            };
            player.performCommand("tdm join " + arenaId + suffix);
            return;
        }
        JoinMode joinMode = switch (team) {
            case "T" -> JoinMode.T;
            case "CT" -> JoinMode.CT;
            case "SPECTATE" -> JoinMode.SPECTATE;
            default -> JoinMode.AUTO;
        };
        joinBomb(player, arenaId, joinMode);
    }

    private void joinBomb(Player player, String arenaId, JoinMode mode) {
        ArenaSession session = session(arenaId);
        if (session == null) {
            player.sendMessage(color("&c找不到竞技场 " + arenaId + "。"));
            return;
        }
        boolean joined;
        try {
            joined = session.join(player, mode);
        } catch (Throwable t) {
            plugin.getLogger().warning("菜单加入爆破失败：" + t);
            player.sendMessage(color("&c加入失败：" + t.getMessage()));
            return;
        }
        if (joined) {
            player.closeInventory();
        } else {
            player.sendMessage(color("&c加入失败（房间已满或状态不允许）。"));
        }
    }

    // ------------------------------------------------------------------
    // 最近比赛结果
    // ------------------------------------------------------------------

    @EventHandler
    public void onMatchEnd(WbdMatchEndEvent event) {
        String winner = event.getWinner() == null ? "无" : event.getWinner().name();
        lastResults.put(event.getArenaId(),
                new LastResult(winner, event.getTScore(), event.getCtScore()));
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private boolean isBomb(String modeId) {
        return modeId != null && BombDefuseMode.MODE_ID.equals(modeId);
    }

    private boolean isTdm(String modeId) {
        return "team-deathmatch".equals(modeId);
    }

    private String modeName(String modeId) {
        if (modeId == null) return "未知模式";
        for (AddonMode mode : modes()) {
            if (modeId.equals(mode.getModeId())) return mode.getDisplayName();
        }
        return modeId;
    }

    private Material modeIcon(String modeId) {
        if (isBomb(modeId)) return Material.TNT;
        if (isTdm(modeId)) return Material.IRON_SWORD;
        return Material.PAPER;
    }

    private ItemStack item(Material material, String name, String... lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;
        meta.setDisplayName(color(name));
        if (lore.length > 0) {
            List<String> lines = new ArrayList<>();
            for (String line : lore) lines.add(color(line));
            meta.setLore(lines);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }

    public enum Screen {
        MAIN, MODES, MODE, MATCHING, ROOMS, ACTIVE, TEAM, SUMMARY, PROFILE
    }

    public static final class MenuHolder implements InventoryHolder {
        final Screen screen;
        final String modeId;
        final String arenaId;
        Inventory inventory;

        MenuHolder(Screen screen, String modeId, String arenaId) {
            this.screen = screen;
            this.modeId = modeId;
            this.arenaId = arenaId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private record LastResult(String winner, int tScore, int ctScore) {
    }
}
