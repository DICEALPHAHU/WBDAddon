package alphahu.wbdaddon.module;

import alphahu.wbdaddon.WBDAddon;
import alphahu.wbdaddon.api.AddonMode;
import alphahu.wbdaddon.mode.BombDefuseMode;
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
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
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

    /** 玩家生涯统计（跨比赛累计）。 */
    private final Map<UUID, Stats> stats = new ConcurrentHashMap<>();

    /** 当前这场比赛已经计入过的玩家，避免离场和比赛结束重复累加。 */
    private final Map<String, Set<UUID>> counted = new ConcurrentHashMap<>();

    private File resultsFile;
    private File statsFile;

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
        this.resultsFile = new File(plugin.getDataFolder(), "menu-results.yml");
        this.statsFile = new File(plugin.getDataFolder(), "player-stats.yml");
        this.historySize = Math.max(1,
                plugin.getConfig().getInt("modules.menu.history-size", 30));
        loadResults();
        loadStats();

        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("主菜单已启动：/wbda 打开，已载入 "
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
        saveResults();
        saveStats();
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
        return screen == Screen.ROOMS || screen == Screen.ACTIVE
                || screen == Screen.SUMMARY || screen == Screen.MATCH ? 54 : 27;
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
        }
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
        saveResults();

        ArenaSession session = session(event.getArenaId());
        if (session != null) {
            Set<UUID> done = counted.computeIfAbsent(
                    event.getArenaId(), k -> ConcurrentHashMap.newKeySet());
            for (PlayerRecord record : session.getPlayers()) {
                if (!done.add(record.getUuid())) continue;
                addStats(record, true, winner);
            }
            saveStats();

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
            saveResults();
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
        saveStats();
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
        MAIN, MODES, MODE, MATCHING, ROOMS, ACTIVE, TEAM, SUMMARY, MATCH, PROFILE
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

    private void loadResults() {
        lastResults.clear();
        if (resultsFile == null || !resultsFile.isFile()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(resultsFile);
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

    private void saveResults() {
        if (resultsFile == null) return;
        YamlConfiguration yaml = new YamlConfiguration();
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

        try {
            yaml.save(resultsFile);
        } catch (IOException e) {
            plugin.getLogger().warning("保存 menu-results.yml 失败：" + e.getMessage());
        }
    }

    private void loadStats() {
        stats.clear();
        if (statsFile == null || !statsFile.isFile()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(statsFile);
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

    private void saveStats() {
        if (statsFile == null) return;
        YamlConfiguration yaml = new YamlConfiguration();
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
        try {
            yaml.save(statsFile);
        } catch (IOException e) {
            plugin.getLogger().warning("保存 player-stats.yml 失败：" + e.getMessage());
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
