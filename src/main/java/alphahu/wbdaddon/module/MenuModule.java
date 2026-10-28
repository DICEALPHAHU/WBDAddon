package alphahu.wbdaddon.module;

import alphahu.wbdaddon.WBDAddon;
import alphahu.wbdaddon.api.AddonMode;
import alphahu.wbdaddon.mode.BombDefuseMode;
import alphahu.wbdaddon.storage.StatsStore;
import alphahu.wbdaddon.util.ArenaAdmin;
import com.warz.bombdefuse.api.WbdMatchEndEvent;
import com.warz.bombdefuse.api.WbdPlayerLeaveArenaEvent;
import com.warz.bombdefuse.api.WbdRoundStartEvent;
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
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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

    /** 最近的比赛记录（含每名玩家的 K/D）。 */
    private final java.util.List<MatchRecord> history = new java.util.ArrayList<>();
    private int historySize;

    /** 是否启用 Shift+F 开菜单（modules.menu.open-key = off 时关闭）。 */
    private boolean keybind = true;

    /** 玩家生涯统计（跨比赛累计）。 */
    private final Map<UUID, Stats> stats = new ConcurrentHashMap<>();

    /** 当前这场比赛已经计入过的玩家，避免离场和比赛结束重复累加。 */
    private final Map<String, Set<UUID>> counted = new ConcurrentHashMap<>();

    /** 生涯数据 + 比赛历史 + 最近结果：优先 SQLite，驱动缺失时退回 data/stats.yml。 */
    private File storageFile;
    private StatsStore store;

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
        this.storageFile = plugin.dataFile("stats.yml");
        this.historySize = Math.max(1,
                plugin.getConfig().getInt("modules.menu.history-size", 30));

        // 数据库优先：服务端 libraries 里自带 sqlite-jdbc 时用它，否则退回 YAML
        this.store = new StatsStore(plugin.getLogger());
        if (!store.open(new File(plugin.getDataDir(), "stats.db"))) {
            this.store = null;
        }
        this.keybind = !"off".equalsIgnoreCase(
                plugin.getConfig().getString("modules.menu.open-key", "sneak-swap-hands"));
        migrateLegacyFiles();
        if (store != null && !store.isEmpty()) {
            loadFromStore();
        } else {
            // 库里还没数据：先把 YAML（或旧版文件）读进来，再灌进库
            loadStorage();
            saveStore();
        }

        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("主菜单已启动：/wbda 或 "
                + (keybind ? "Shift+F " : "命令 ")
                + "打开，已载入 "
                + lastResults.size() + " 条比赛记录、" + stats.size() + " 份生涯数据。");
    }

    @Override
    public void onDisable() {
        HandlerList.unregisterAll(this);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder) {
                player.closeInventory();
            }
        }
        saveStorage();
        if (store != null) {
            store.close();
            store = null;
        }
        lastResults.clear();
        stats.clear();
        counted.clear();
        plugin.getLogger().info("主菜单已卸载。");
    }

    // ------------------------------------------------------------------
    // 入口
    // ------------------------------------------------------------------

    public void openMain(Player player) {
        open(player, Screen.MAIN, null, null);
    }

    /** 管理员面板入口（/wbdaddon admin）。 */
    public void openAdmin(Player player) {
        open(player, Screen.ADMIN, null, null);
    }

    /**
     * Shift + F 打开主菜单。
     *
     * <p>服务端能识别的按键只有「交换副手」，所以用潜行组合来区分：
     * 单按 F 仍是原版换手，Shift + F 才弹菜单。
     * 优先于装备槽守卫的按键处理（那边是 LOW），避免被绿宝石拦截。
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (!keybind) return;
        Player player = event.getPlayer();
        if (!player.isSneaking()) return;
        // 已经开着别的界面时（商店、箱子）不抢，免得把人家窗口顶掉
        if (player.getOpenInventory().getType() != InventoryType.CRAFTING) return;
        event.setCancelled(true);
        openMain(player);
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
        handle(player, holder, event.getSlot(), event);
    }

    private void handle(Player player, MenuHolder holder, int slot,
                        InventoryClickEvent clickEvent) {
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
                } else if (slot < 45 && slot < history.size()) {
                    MatchRecord record = history.get(slot);
                    open(player, Screen.MATCH, record.arenaId(), record.arenaId(), record.time());
                }
            }
            case MATCH -> {
                if (slot == 49) {
                    open(player, Screen.SUMMARY, null, null);
                }
            }
            case PROFILE -> {
                if (slot == 22) {
                    open(player, Screen.MAIN, null, null);
                }
            }
            case ADMIN -> {
                if (slot == 10) {
                    open(player, Screen.ADMIN_MAPS, null, null);
                } else if (slot == 12) {
                    forceRound(player, true);
                } else if (slot == 14) {
                    forceRound(player, false);
                } else if (slot == 16) {
                    open(player, Screen.ADMIN_DATA, null, null);
                } else if (slot == 22) {
                    open(player, Screen.MAIN, null, null);
                }
            }
            case ADMIN_MAPS -> {
                if (slot == 49) {
                    open(player, Screen.ADMIN, null, null);
                    return;
                }
                if (slot >= 45) return;
                java.util.List<ArenaSession> sessions = arenaList();
                if (slot < sessions.size()) {
                    forceStart(player, sessions.get(slot));
                }
            }
            case ADMIN_DATA -> {
                if (slot == 11) {
                    open(player, Screen.ADMIN_PLAYERS, null, null);
                } else if (slot == 15) {
                    open(player, Screen.ADMIN_MATCHES, null, null);
                } else if (slot == 22) {
                    open(player, Screen.ADMIN, null, null);
                }
            }
            case ADMIN_PLAYERS -> {
                if (slot == 49) {
                    open(player, Screen.ADMIN_DATA, null, null);
                    return;
                }
                if (slot >= 45) return;
                java.util.List<Player> targets = adminPlayerList();
                if (slot >= targets.size()) return;
                Player target = targets.get(slot);
                if (!clickEvent.isShiftClick()) {
                    player.sendMessage(color("&7要清除 &f" + target.getName()
                            + " &7的生涯数据，请 &cShift + 左键&7。"));
                    return;
                }
                clearPlayerData(player, target);
                open(player, Screen.ADMIN_PLAYERS, null, null);
            }
            case ADMIN_MATCHES -> {
                if (slot == 49) {
                    open(player, Screen.ADMIN_DATA, null, null);
                    return;
                }
                if (slot >= 45 || slot >= history.size()) return;
                if (!clickEvent.isShiftClick()) {
                    player.sendMessage(color("&7要删除这条比赛记录，请 &cShift + 左键&7。"));
                    return;
                }
                MatchRecord record = history.get(slot);
                history.remove(slot);
                saveStorage();
                player.sendMessage(color("&a已删除比赛记录：&f" + record.arenaId()
                        + " &7(" + record.tScore() + ":" + record.ctScore() + ")"));
                open(player, Screen.ADMIN_MATCHES, null, null);
            }
        }
    }

    // ------------------------------------------------------------------
    // 管理员动作
    // ------------------------------------------------------------------

    private void forceStart(Player admin, ArenaSession session) {
        if (session == null) return;
        GameState state = session.getState();
        if (state != GameState.WAITING && state != GameState.COUNTDOWN) {
            admin.sendMessage(color("&c该竞技场当前是「" + ArenaAdmin.stateName(state)
                    + "」，不能强制开局。"));
            return;
        }
        if (session.getPlayers().isEmpty()) {
            admin.sendMessage(color("&c该竞技场里一个人都没有，先让人进去。"));
            return;
        }
        // 倒计时压到 1 秒，跳过大厅等待
        if (ArenaAdmin.forceStart(session, 20, plugin.getLogger())) {
            admin.closeInventory();
            admin.sendMessage(color("&a已强制开局：&f" + session.getConfig().getId()
                    + " &7(" + session.getPlayers().size() + " 人)"));
        } else {
            admin.sendMessage(color("&c强制开局失败（可能是 WBD 版本差异）。"));
        }
    }

    private void forceRound(Player admin, boolean win) {
        ArenaSession session = sessionOf(admin);
        if (session == null) {
            admin.sendMessage(color("&c你不在任何竞技场里。"));
            return;
        }
        Team team = session.getTeam(admin);
        if (team != Team.T && team != Team.CT) {
            admin.sendMessage(color("&c你不在任何一方阵营，无法按阵营判定胜负。"));
            return;
        }
        Team winner = win ? team : team.opposite();
        if (ArenaAdmin.forceEndRound(session, winner, plugin.getLogger())) {
            admin.closeInventory();
            admin.sendMessage(color("&a已强制结算本回合：&f" + teamLabel(winner) + " &a胜利"));
        } else {
            admin.sendMessage(color("&c强制结算失败（当前不在回合进行中）。"));
        }
    }

    private void clearPlayerData(Player admin, Player target) {
        Stats removed = stats.remove(target.getUniqueId());
        counted.values().forEach(set -> set.remove(target.getUniqueId()));
        saveStorage();
        if (removed == null) {
            admin.sendMessage(color("&7" + target.getName() + " 本来就没有生涯数据。"));
        } else {
            admin.sendMessage(color("&a已清除 &f" + target.getName() + " &a的生涯数据（"
                    + removed.kills + "/" + removed.deaths + "，"
                    + removed.matches + " 场）。"));
        }
    }

    // ------------------------------------------------------------------
    // 打开 / 渲染
    // ------------------------------------------------------------------

    private void open(Player player, Screen screen, String modeId, String arenaId) {
        open(player, screen, modeId, arenaId, 0L);
    }

    private void open(Player player, Screen screen, String modeId, String arenaId, long matchTime) {
        MenuHolder holder = new MenuHolder(screen, modeId, arenaId, matchTime);
        Inventory inventory = Bukkit.createInventory(holder, size(screen),
                color(title(screen, modeId)));
        holder.inventory = inventory;
        render(inventory, holder, player);
        player.openInventory(inventory);
    }

    private int size(Screen screen) {
        return switch (screen) {
            case ROOMS, ACTIVE, SUMMARY, MATCH, ADMIN_MAPS, ADMIN_PLAYERS, ADMIN_MATCHES -> 54;
            default -> 27;
        };
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
            case MATCH -> "&8比赛详情";
            case PROFILE -> "&8个人资料";
            case ADMIN -> "&8管理面板";
            case ADMIN_MAPS -> "&8管理 - 强制开局";
            case ADMIN_DATA -> "&8管理 - 数据管理";
            case ADMIN_PLAYERS -> "&8管理 - 清除玩家数据";
            case ADMIN_MATCHES -> "&8管理 - 清除比赛记录";
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
                for (int i = 0; i < history.size() && i < 45; i++) {
                    MatchRecord record = history.get(i);
                    inventory.setItem(i, item(Material.BOOK,
                            "&e" + record.arenaId() + " &7| &f" + record.winner(),
                            "&7比分：&cT " + record.tScore() + " &7: &9CT " + record.ctScore(),
                            "&7时间：&f" + time(record.time()),
                            "&7玩家：&f" + record.players().size() + " 人",
                            "&a点击查看详情"));
                }
                if (history.isEmpty()) {
                    inventory.setItem(22, item(Material.PAPER, "&7暂无比赛记录"));
                }
                inventory.setItem(49, item(Material.ARROW, "&7返回"));
            }
            case MATCH -> renderMatch(inventory, holder);
            case PROFILE -> renderProfile(inventory, player);
            case ADMIN -> renderAdmin(inventory, player);
            case ADMIN_MAPS -> renderAdminMaps(inventory);
            case ADMIN_DATA -> {
                inventory.setItem(11, item(Material.PLAYER_HEAD, "&e清除玩家数据",
                        "&7清掉某名玩家的生涯数据",
                        "&7（K/D、下包拆包、场次、胜场）"));
                inventory.setItem(15, item(Material.BOOK, "&e清除比赛记录",
                        "&7从比赛历史里删掉某一场"));
                inventory.setItem(22, item(Material.ARROW, "&7返回"));
            }
            case ADMIN_PLAYERS -> renderAdminPlayers(inventory);
            case ADMIN_MATCHES -> renderAdminMatches(inventory);
        }
    }

    // ------------------------------------------------------------------
    // 管理面板
    // ------------------------------------------------------------------

    private void renderAdmin(Inventory inventory, Player player) {
        inventory.setItem(10, item(Material.MAP, "&e强制开局",
                "&7选一张地图，立刻开战",
                "&7跳过大厅等待，倒计时压到 1 秒"));

        ArenaSession session = sessionOf(player);
        Team team = session == null ? null : session.getTeam(player);
        if (team == Team.T || team == Team.CT) {
            inventory.setItem(12, item(Material.LIME_WOOL, "&a本回合强制胜利",
                    "&7按你所在阵营判定：&f" + teamLabel(team) + " &7胜",
                    "&7当前状态：&f" + ArenaAdmin.stateName(session.getState())));
            inventory.setItem(14, item(Material.RED_WOOL, "&c本回合强制失败",
                    "&7按你所在阵营判定：&f" + teamLabel(team.opposite()) + " &7胜",
                    "&7当前状态：&f" + ArenaAdmin.stateName(session.getState())));
        } else {
            inventory.setItem(12, item(Material.GRAY_WOOL, "&7本回合强制胜利",
                    "&c你不在比赛中，无法按阵营判定"));
            inventory.setItem(14, item(Material.GRAY_WOOL, "&7本回合强制失败",
                    "&c你不在比赛中，无法按阵营判定"));
        }

        inventory.setItem(16, item(Material.CHEST, "&e数据管理",
                "&7清除玩家生涯数据 / 比赛记录"));
        inventory.setItem(22, item(Material.ARROW, "&7返回主菜单"));
    }

    private void renderAdminMaps(Inventory inventory) {
        java.util.List<ArenaSession> sessions = arenaList();
        for (int i = 0; i < sessions.size() && i < 45; i++) {
            ArenaSession session = sessions.get(i);
            GameState state = session.getState();
            String hint = state == GameState.WAITING || state == GameState.COUNTDOWN
                    ? "&a点击强制开局" : "&7当前状态不能强制开局";
            inventory.setItem(i, item(Material.FILLED_MAP,
                    "&e" + session.getConfig().getId(),
                    "&7名称：&f" + session.getConfig().getName(),
                    "&7状态：&f" + ArenaAdmin.stateName(state),
                    "&7玩家：&f" + session.getPlayers().size() + " 人",
                    hint));
        }
        if (sessions.isEmpty()) {
            inventory.setItem(22, item(Material.PAPER, "&7没有配置任何竞技场"));
        }
        inventory.setItem(49, item(Material.ARROW, "&7返回"));
    }

    private void renderAdminPlayers(Inventory inventory) {
        java.util.List<Player> players = adminPlayerList();
        for (int i = 0; i < players.size() && i < 45; i++) {
            Player target = players.get(i);
            Stats data = stats.get(target.getUniqueId());
            String career = data == null ? "&7（无生涯数据）"
                    : "&7K/D：&f" + data.kills + "&7/&f" + data.deaths
                    + "  &7场次：&f" + data.matches + "  &7胜场：&f" + data.wins;
            inventory.setItem(i, item(Material.PLAYER_HEAD,
                    "&e" + target.getName(), career,
                    "&cShift + 左键 &7清除他的生涯数据"));
        }
        if (players.isEmpty()) {
            inventory.setItem(22, item(Material.PAPER, "&7当前没有在线玩家"));
        }
        inventory.setItem(49, item(Material.ARROW, "&7返回"));
    }

    private void renderAdminMatches(Inventory inventory) {
        for (int i = 0; i < history.size() && i < 45; i++) {
            MatchRecord record = history.get(i);
            inventory.setItem(i, item(Material.BOOK,
                    "&e" + record.arenaId() + " &7| &f" + record.winner(),
                    "&7比分：&cT " + record.tScore() + " &7: &9CT " + record.ctScore(),
                    "&7时间：&f" + time(record.time()),
                    "&cShift + 左键 &7删除这条记录"));
        }
        if (history.isEmpty()) {
            inventory.setItem(22, item(Material.PAPER, "&7暂无比赛记录"));
        }
        inventory.setItem(49, item(Material.ARROW, "&7返回"));
    }

    private java.util.List<ArenaSession> arenaList() {
        java.util.List<ArenaSession> list = new java.util.ArrayList<>();
        if (plugin.getWbd() == null || plugin.getWbd().getArenaManager() == null) return list;
        list.addAll(plugin.getWbd().getArenaManager().getSessions());
        list.sort(java.util.Comparator.comparing(s -> s.getConfig().getId()));
        return list;
    }

    private java.util.List<Player> adminPlayerList() {
        java.util.List<Player> list = new java.util.ArrayList<>(Bukkit.getOnlinePlayers());
        list.sort(java.util.Comparator.comparing(Player::getName));
        return list;
    }

    private String teamLabel(Team team) {
        if (team == Team.T) return "T 方";
        if (team == Team.CT) return "CT 方";
        return "观战";
    }

    private void renderMatch(Inventory inventory, MenuHolder holder) {
        MatchRecord record = findMatch(holder);
        if (record == null) {
            inventory.setItem(22, item(Material.PAPER, "&7记录不存在"));
            inventory.setItem(49, item(Material.ARROW, "&7返回"));
            return;
        }
        inventory.setItem(4, item(Material.WRITTEN_BOOK,
                "&e" + record.arenaId() + " &7| &f" + record.winner(),
                "&7比分：&cT " + record.tScore() + " &7: &9CT " + record.ctScore(),
                "&7时间：&f" + time(record.time()),
                "&7玩家：&f" + record.players().size() + " 人"));
        int slot = 9;
        for (Line line : record.players()) {
            if (slot > 44) break;
            inventory.setItem(slot++, playerLine(line));
        }
        inventory.setItem(49, item(Material.ARROW, "&7返回"));
    }

    private MatchRecord findMatch(MenuHolder holder) {
        for (MatchRecord record : history) {
            if (record.time() == holder.matchTime
                    && record.arenaId().equals(holder.arenaId)) {
                return record;
            }
        }
        return null;
    }

    private ItemStack playerLine(Line line) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = head.getItemMeta();
        if (meta instanceof SkullMeta skull) {
            skull.setOwningPlayer(Bukkit.getOfflinePlayer(line.name()));
        }
        if (meta != null) {
            String teamColor = "T".equals(line.team()) ? "&c" : "&9";
            meta.setDisplayName(color(teamColor + line.name()));
            meta.setLore(java.util.List.of(
                    color("&7阵营：&f" + line.team()),
                    color("&7击杀：&c" + line.kills() + " &7死亡：&f" + line.deaths()
                            + " &7K/D：&f" + ratio(line.kills(), line.deaths())),
                    color("&7下包：&f" + line.plants() + " &7拆包：&f" + line.defuses())));
        }
        head.setItemMeta(meta);
        return head;
    }

    private String time(long millis) {
        return new SimpleDateFormat("MM-dd HH:mm").format(new Date(millis));
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
            lore.add(color("&8— 生涯 —"));
            Stats career = stats.get(player.getUniqueId());
            if (career == null) {
                lore.add(color("&7暂无生涯记录"));
            } else {
                lore.add(color("&7击杀：&c" + career.kills + " &7死亡：&f" + career.deaths
                        + " &7K/D：&f" + ratio(career.kills, career.deaths)));
                lore.add(color("&7场次：&f" + career.matches + " &7胜场：&f" + career.wins
                        + " &7胜率：&f" + percent(career.wins, career.matches)));
                lore.add(color("&7下包：&f" + career.plants + " &7拆包：&f" + career.defuses));
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

    private String percent(int wins, int matches) {
        if (matches <= 0) return "0%";
        return Math.round(wins * 100.0 / matches) + "%";
    }

    // ------------------------------------------------------------------
    // 数据
    // ------------------------------------------------------------------

    private List<AddonMode> modes() {
        if (plugin.getApi() == null) return List.of();
        return plugin.getApi().getModes();
    }

    private ArenaSession sessionOf(Player player) {
        if (player == null || plugin.getWbd() == null
                || plugin.getWbd().getArenaManager() == null) {
            return null;
        }
        return plugin.getWbd().getArenaManager().getSession(player);
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
        // 被本场投票踢出去的人，菜单这边先拦一道，别让他先进大厅再被弹回去
        if (voteBanned(player, arenaId)) {
            player.sendMessage(color("&c你已被投票踢出本场比赛，无法再加入。"));
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

    /** 本场被投票踢出的玩家不允许再加入。 */
    private boolean voteBanned(Player player, String arenaId) {
        AddonModule module = plugin.getModuleManager().getModule("vote");
        if (module instanceof VoteModule votes && plugin.getModuleManager().isEnabled(module)) {
            return votes.isBanned(player, arenaId);
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 最近比赛结果
    // ------------------------------------------------------------------

    @EventHandler
    public void onMatchEnd(WbdMatchEndEvent event) {
        Team winner = event.getWinner();
        String winnerName = winner == null ? "无" : winner.name();
        lastResults.put(event.getArenaId(),
                new LastResult(winnerName, event.getTScore(), event.getCtScore()));
        saveStorage();

        ArenaSession session = session(event.getArenaId());
        if (session != null) {
            Set<UUID> done = counted.computeIfAbsent(
                    event.getArenaId(), k -> ConcurrentHashMap.newKeySet());
            for (PlayerRecord record : session.getPlayers()) {
                if (!done.add(record.getUuid())) continue;
                addStats(record, true, winner);
            }
            saveStorage();

            java.util.List<Line> lines = new java.util.ArrayList<>();
            for (PlayerRecord record : session.getPlayers()) {
                lines.add(new Line(record.getName(),
                        record.getTeam() == null ? "?" : record.getTeam().name(),
                        record.getKills(), record.getDeaths(),
                        record.getPlants(), record.getDefuses()));
            }
            history.add(0, new MatchRecord(event.getArenaId(), winnerName,
                    event.getTScore(), event.getCtScore(), System.currentTimeMillis(), lines));
            while (history.size() > historySize) {
                history.remove(history.size() - 1);
            }
            saveStorage();
        }
    }

    @EventHandler
    public void onLeave(WbdPlayerLeaveArenaEvent event) {
        Player player = event.getPlayer();
        ArenaSession session = session(event.getArenaId());
        if (session == null || player == null) return;

        PlayerRecord record = session.getRecord(player);
        if (record == null) return;

        Set<UUID> done = counted.computeIfAbsent(
                event.getArenaId(), k -> ConcurrentHashMap.newKeySet());
        if (!done.add(player.getUniqueId())) return;

        addStats(record, false, null);
        saveStorage();
    }

    @EventHandler
    public void onRoundStart(WbdRoundStartEvent event) {
        // 新比赛开始，清掉上一场的“已计入”标记
        if (event.getRound() <= 1) {
            counted.remove(event.getArenaId());
        }
    }

    private void addStats(PlayerRecord record, boolean countMatch, Team winner) {
        Stats s = stats.computeIfAbsent(record.getUuid(), k -> new Stats());
        s.name = record.getName();
        s.kills += record.getKills();
        s.deaths += record.getDeaths();
        s.plants += record.getPlants();
        s.defuses += record.getDefuses();
        if (countMatch) {
            s.matches++;
            if (winner != null && record.getTeam() == winner) {
                s.wins++;
            }
        }
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
        MAIN, MODES, MODE, MATCHING, ROOMS, ACTIVE, TEAM, SUMMARY, MATCH, PROFILE,
        ADMIN, ADMIN_MAPS, ADMIN_DATA, ADMIN_PLAYERS, ADMIN_MATCHES
    }

    public static final class MenuHolder implements InventoryHolder {
        final Screen screen;
        final String modeId;
        final String arenaId;
        final long matchTime;
        Inventory inventory;

        MenuHolder(Screen screen, String modeId, String arenaId) {
            this(screen, modeId, arenaId, 0L);
        }

        MenuHolder(Screen screen, String modeId, String arenaId, long matchTime) {
            this.screen = screen;
            this.modeId = modeId;
            this.arenaId = arenaId;
            this.matchTime = matchTime;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private record LastResult(String winner, int tScore, int ctScore) {
    }

    private record Line(String name, String team, int kills, int deaths,
                        int plants, int defuses) {
    }

    private record MatchRecord(String arenaId, String winner, int tScore, int ctScore,
                               long time, java.util.List<Line> players) {
    }

    // ------------------------------------------------------------------
    // 落盘：menu-results.yml / player-stats.yml
    // ------------------------------------------------------------------

    /** 载入 data/stats.yml（比赛结果 / 历史 / 生涯都在里面）。 */
    private void loadStorage() {
        lastResults.clear();
        history.clear();
        stats.clear();
        if (storageFile == null || !storageFile.isFile()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(storageFile);
        readResults(yaml);
        readStats(yaml);
        trimHistory();
    }

    /** 历史只保留最近 history-size 场，读盘时也要裁一次（旧版本可能存多了）。 */
    private void trimHistory() {
        while (history.size() > historySize) {
            history.remove(history.size() - 1);
        }
    }

    /**
     * 旧版本把数据拆成 menu-results.yml 和 player-stats.yml 放在插件根目录。
     * 这里一次性合并进 data/stats.yml，并把旧文件改名成 .bak 留个底。
     */
    private void migrateLegacyFiles() {
        File legacyResults = new File(plugin.getDataFolder(), "menu-results.yml");
        File legacyStats = new File(plugin.getDataFolder(), "player-stats.yml");
        if (!legacyResults.isFile() && !legacyStats.isFile()) return;
        if (storageFile != null && storageFile.isFile()) return;

        if (legacyResults.isFile()) {
            readResults(YamlConfiguration.loadConfiguration(legacyResults));
        }
        if (legacyStats.isFile()) {
            readStats(YamlConfiguration.loadConfiguration(legacyStats));
        }
        trimHistory();
        saveStorage();
        backupLegacy(legacyResults);
        backupLegacy(legacyStats);
        plugin.getLogger().info("已把旧数据合并到 data/stats.yml（原文件改名 .bak 保留）。");
    }

    private void backupLegacy(File file) {
        if (file == null || !file.isFile()) return;
        try {
            // 备份也放进 data/，根目录只留配置
            java.nio.file.Files.move(file.toPath(),
                    new File(plugin.getDataDir(), file.getName() + ".bak").toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable ignored) {
            // 备份失败不影响主流程
        }
    }

    private void readResults(YamlConfiguration yaml) {
        ConfigurationSection section = yaml.getConfigurationSection("results");
        if (section == null) return;
        for (String id : section.getKeys(false)) {
            lastResults.put(id, new LastResult(
                    section.getString(id + ".winner", "无"),
                    section.getInt(id + ".t-score"),
                    section.getInt(id + ".ct-score")));
        }

        for (Map<?, ?> raw : yaml.getMapList("history")) {
            java.util.List<Line> lines = new java.util.ArrayList<>();
            Object playersRaw = raw.get("players");
            if (playersRaw instanceof java.util.List<?> list) {
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> pm)) continue;
                    lines.add(new Line(String.valueOf(pm.get("name")),
                            String.valueOf(pm.get("team")),
                            number(pm.get("kills")), number(pm.get("deaths")),
                            number(pm.get("plants")), number(pm.get("defuses"))));
                }
            }
            history.add(new MatchRecord(String.valueOf(raw.get("arena")),
                    String.valueOf(raw.get("winner")),
                    number(raw.get("t-score")), number(raw.get("ct-score")),
                    numberLong(raw.get("time")), lines));
        }
    }

    private int number(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    private long numberLong(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    /** 落盘：YAML 作为可读备份，有数据库时同时写库。 */
    private void saveStorage() {
        saveYaml();
        saveStore();
    }

    /** 把比赛结果 / 历史 / 生涯一起写回 data/stats.yml。 */
    private void saveYaml() {
        if (storageFile == null) return;
        // 防御：内存里什么都没有、磁盘上却有内容时，绝不覆盖。
        // 之前 /wbdaddon reload 在半路抛错，模块被卸载了两次，
        // 第二次 onDisable 从已清空的内存里写出空文件，把历史记录洗掉了。
        if (lastResults.isEmpty() && history.isEmpty() && stats.isEmpty()
                && fileHasData(storageFile)) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        writeResults(yaml);
        writeStats(yaml);
        try {
            yaml.save(storageFile);
        } catch (IOException e) {
            plugin.getLogger().warning("保存 data/stats.yml 失败：" + e.getMessage());
        }
    }

    private void writeResults(YamlConfiguration yaml) {
        for (Map.Entry<String, LastResult> entry : lastResults.entrySet()) {
            String base = "results." + entry.getKey();
            yaml.set(base + ".winner", entry.getValue().winner());
            yaml.set(base + ".t-score", entry.getValue().tScore());
            yaml.set(base + ".ct-score", entry.getValue().ctScore());
        }

        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (MatchRecord record : history) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("arena", record.arenaId());
            m.put("winner", record.winner());
            m.put("t-score", record.tScore());
            m.put("ct-score", record.ctScore());
            m.put("time", record.time());
            java.util.List<Map<String, Object>> pl = new java.util.ArrayList<>();
            for (Line line : record.players()) {
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("name", line.name());
                pm.put("team", line.team());
                pm.put("kills", line.kills());
                pm.put("deaths", line.deaths());
                pm.put("plants", line.plants());
                pm.put("defuses", line.defuses());
                pl.add(pm);
            }
            m.put("players", pl);
            out.add(m);
        }
        yaml.set("history", out);
    }

    private void readStats(YamlConfiguration yaml) {
        ConfigurationSection section = yaml.getConfigurationSection("players");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                Stats s = new Stats();
                s.name = section.getString(key + ".name", "");
                s.kills = section.getInt(key + ".kills");
                s.deaths = section.getInt(key + ".deaths");
                s.plants = section.getInt(key + ".plants");
                s.defuses = section.getInt(key + ".defuses");
                s.matches = section.getInt(key + ".matches");
                s.wins = section.getInt(key + ".wins");
                stats.put(id, s);
            } catch (IllegalArgumentException ignored) {
                // 坏 UUID 跳过
            }
        }
    }

    private void writeStats(YamlConfiguration yaml) {
        for (Map.Entry<UUID, Stats> entry : stats.entrySet()) {
            String base = "players." + entry.getKey();
            Stats s = entry.getValue();
            yaml.set(base + ".name", s.name);
            yaml.set(base + ".kills", s.kills);
            yaml.set(base + ".deaths", s.deaths);
            yaml.set(base + ".plants", s.plants);
            yaml.set(base + ".defuses", s.defuses);
            yaml.set(base + ".matches", s.matches);
            yaml.set(base + ".wins", s.wins);
        }
    }

    // ------------------------------------------------------------------
    // SQLite
    // ------------------------------------------------------------------

    /** 从库里读出比赛结果 / 历史 / 生涯。 */
    private void loadFromStore() {
        lastResults.clear();
        history.clear();
        stats.clear();

        for (StatsStore.ResultRow row : store.loadResults()) {
            lastResults.put(row.arena(), new LastResult(row.winner(), row.tScore(), row.ctScore()));
        }
        for (StatsStore.MatchRow match : store.loadHistory(historySize)) {
            java.util.List<Line> lines = new java.util.ArrayList<>();
            for (StatsStore.PlayerLine line : match.lines()) {
                lines.add(new Line(line.name(), line.team(), line.kills(), line.deaths(),
                        line.plants(), line.defuses()));
            }
            history.add(new MatchRecord(match.arena(), match.winner(), match.tScore(),
                    match.ctScore(), match.playedAt(), lines));
        }
        trimHistory();
        for (StatsStore.CareerRow row : store.loadCareer()) {
            Stats entry = new Stats();
            entry.name = row.name();
            entry.kills = row.kills();
            entry.deaths = row.deaths();
            entry.plants = row.plants();
            entry.defuses = row.defuses();
            entry.matches = row.matches();
            entry.wins = row.wins();
            stats.put(row.uuid(), entry);
        }
    }

    /** 把内存里的数据整体写进库（一场比赛结束才写，量很小）。 */
    private void saveStore() {
        if (store == null || !store.isAvailable()) return;

        java.util.List<StatsStore.ResultRow> results = new java.util.ArrayList<>();
        for (Map.Entry<String, LastResult> entry : lastResults.entrySet()) {
            LastResult value = entry.getValue();
            results.add(new StatsStore.ResultRow(entry.getKey(), value.winner(),
                    value.tScore(), value.ctScore()));
        }

        java.util.List<StatsStore.MatchRow> matches = new java.util.ArrayList<>();
        for (MatchRecord record : history) {
            StatsStore.MatchRow row = new StatsStore.MatchRow(0L, record.arenaId(),
                    record.winner(), record.tScore(), record.ctScore(), record.time());
            for (Line line : record.players()) {
                row.lines().add(new StatsStore.PlayerLine(line.name(), line.team(),
                        line.kills(), line.deaths(), line.plants(), line.defuses()));
            }
            matches.add(row);
        }

        java.util.List<StatsStore.CareerRow> career = new java.util.ArrayList<>();
        for (Map.Entry<UUID, Stats> entry : stats.entrySet()) {
            Stats value = entry.getValue();
            career.add(new StatsStore.CareerRow(entry.getKey(), value.name, value.kills,
                    value.deaths, value.plants, value.defuses, value.matches, value.wins));
        }

        store.replaceAll(results, matches, career);
    }

    /** 文件里是否已经有内容（有内容就不该被空内存覆盖）。 */
    private boolean fileHasData(java.io.File file) {
        try {
            return file != null && file.isFile() && file.length() > 32L;
        } catch (Throwable t) {
            return false;
        }
    }

    private static final class Stats {
        String name = "";
        int kills;
        int deaths;
        int plants;
        int defuses;
        int matches;
        int wins;
    }
}
