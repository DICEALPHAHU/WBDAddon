package alphahu.wbdaddon.module;

import alphahu.wbdaddon.WBDAddon;
import alphahu.wbdaddon.api.AddonMode;
import alphahu.wbdaddon.mode.BombDefuseMode;
import com.warz.bombdefuse.api.WbdMatchEndEvent;
import com.warz.bombdefuse.api.WbdPlayerJoinArenaEvent;
import com.warz.bombdefuse.api.WbdPlayerLeaveArenaEvent;
import com.warz.bombdefuse.arena.ArenaSession;
import com.warz.bombdefuse.arena.PlayerRecord;
import com.warz.bombdefuse.model.GameState;
import com.warz.bombdefuse.model.JoinMode;
import com.warz.bombdefuse.model.Team;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 比赛内投票。
 *
 * <p>四种投票共用一套「发起 → BossBar 计票 → 通过/否决 → 执行」的流程，
 * 区别只在<b>谁有资格投</b>与<b>通过门槛</b>：
 *
 * <table border="1">
 *   <caption>投票类型</caption>
 *   <tr><th>命令</th><th>范围</th><th>门槛</th><th>通过后</th></tr>
 *   <tr><td>{@code /unpause}</td><td>全场（T + CT）</td><td>全员同意</td>
 *       <td>提前解除技术暂停</td></tr>
 *   <tr><td>{@code /vkick <队友>}</td><td>同一阵营</td><td>除被踢者外全员同意</td>
 *       <td>踢出本场比赛且本场禁止再加入</td></tr>
 *   <tr><td>{@code /vmap <地图>}</td><td>全场（T + CT）</td><td>超过 2/3</td>
 *       <td>提前结算，整场搬到新地图重开</td></tr>
 *   <tr><td>{@code /vsdr}</td><td>同一阵营</td><td>全员同意</td>
 *       <td>提前结束比赛，判对方获胜</td></tr>
 * </table>
 *
 * <p>投票期间顶部 BossBar 实时显示投票事项、同意/反对票数与剩余时间；
 * 投票一结束（通过、否决或超时）立刻收起。
 *
 * <p>「被踢者默认反对」不是一句提示：发起投票时就把被踢者写进反对集合，
 * 因此他既不能把自己投成同意，也不会因为「还有一票没投」而让投票悬在那里。
 *
 * <p>踢人黑名单按<b>场</b>记录（{@code 竞技场 + 玩家}），
 * {@link WbdMatchEndEvent} 时清空——下一场是新的比赛，没有理由继续拦人。
 * 除了在加入事件里兜底踢回，{@code /wbda} 菜单的加入入口也会先查一次黑名单，
 * 免得玩家先被传进大厅再被弹出来。
 *
 * @author AlphaHu
 */
public class VoteModule implements AddonModule, Listener {

    /** 投票类型。 */
    public enum Kind {
        /** 提前解除技术暂停。 */
        UNPAUSE,
        /** 投票踢人。 */
        KICK,
        /** 投票换图。 */
        MAP,
        /** 投票投降。 */
        SURRENDER
    }

    private final WBDAddon plugin;

    /** 每个竞技场同时最多一个投票，key = 竞技场 ID。 */
    private final Map<String, Vote> active = new ConcurrentHashMap<>();

    /** 被投票踢出、本场禁止再加入的玩家，key = 竞技场 ID。 */
    private final Map<String, Set<UUID>> bans = new ConcurrentHashMap<>();

    /** 冷却结束时间戳（毫秒），key = 竞技场 ID + "|" + 投票类型。 */
    private final Map<String, Long> cooldownUntil = new ConcurrentHashMap<>();

    private int durationSeconds;
    private int cooldownSeconds;
    private boolean includeSpectators;
    private boolean debug;

    /** {@code ArenaSession#endMatch(Team)}，WBD 没有公开的结算入口，只能反射。 */
    private Method endMatchMethod;

    public VoteModule(WBDAddon plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getId() {
        return "vote";
    }

    @Override
    public String getDisplayName() {
        return "比赛投票";
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }

    @Override
    public void onEnable() {
        this.durationSeconds = Math.max(5, plugin.getConfig()
                .getInt("modules.vote.duration-seconds", 30));
        this.cooldownSeconds = Math.max(0, plugin.getConfig()
                .getInt("modules.vote.cooldown-seconds", 15));
        this.includeSpectators = plugin.getConfig()
                .getBoolean("modules.vote.include-spectators", false);
        this.debug = plugin.getConfig().getBoolean("modules.vote.debug", false);

        resolveEndMatchMethod();
        Bukkit.getPluginManager().registerEvents(this, plugin);

        plugin.getLogger().info("比赛投票已启动：/vyes /vno 表态，"
                + "投票时长 " + durationSeconds + " 秒，冷却 " + cooldownSeconds + " 秒"
                + (endMatchMethod != null ? "，支持提前结算。" : "，提前结算不可用（反射失败）。"));
    }

    @Override
    public void onDisable() {
        for (Vote vote : new ArrayList<>(active.values())) {
            cancel(vote, "插件卸载");
        }
        active.clear();
        bans.clear();
        cooldownUntil.clear();
        HandlerList.unregisterAll(this);
        plugin.getLogger().info("比赛投票已卸载。");
    }

    // ------------------------------------------------------------------
    // 发起入口
    // ------------------------------------------------------------------

    /** {@code /unpause}：暂停期间发起「提前解除暂停」的全场投票。 */
    public void startUnpause(Player player) {
        String arenaId = arenaIdOf(player);
        if (arenaId == null) {
            player.sendMessage(color("&c你不在爆破模式的比赛里。"));
            return;
        }
        TechnicalPauseModule pause = pauseModule();
        if (pause == null || !pause.isPaused(arenaId)) {
            player.sendMessage(color("&c当前没有技术暂停，不需要投票解除。"));
            return;
        }
        if (!begin(player, arenaId, Kind.UNPAUSE, "解除技术暂停", null, null, null)) {
            return;
        }
    }

    /** {@code /vkick <队友>}：同一阵营投票把某名玩家踢出本场比赛。 */
    public void startKick(Player player, String targetName) {
        String arenaId = arenaIdOf(player);
        Team team = teamOf(player);
        if (arenaId == null || team == null) {
            player.sendMessage(color("&c你不在爆破模式的比赛里。"));
            return;
        }
        Player target = findPlayer(targetName);
        if (target == null) {
            player.sendMessage(color("&c找不到玩家 &e" + targetName + "&c。"));
            return;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            player.sendMessage(color("&c不能投票踢自己。"));
            return;
        }
        if (!arenaId.equals(arenaIdOf(target)) || teamOf(target) != team) {
            player.sendMessage(color("&c只能投票踢出同一阵营的队友。"));
            return;
        }
        if (begin(player, arenaId, Kind.KICK,
                "踢出 " + target.getName(), team, target.getUniqueId(), null)) {
            player.sendMessage(color("&7被踢的玩家默认反对票，你队其余人 &a全部同意 &7才生效。"));
        }
    }

    /** {@code /vmap <地图>}：全场投票提前结算并换图重开。 */
    public void startMap(Player player, String mapId) {
        String arenaId = arenaIdOf(player);
        if (arenaId == null) {
            player.sendMessage(color("&c你不在爆破模式的比赛里。"));
            return;
        }
        String targetId = mapId == null ? "" : mapId.toLowerCase();
        ArenaSession target = session(targetId);
        if (target == null) {
            player.sendMessage(color("&c找不到地图 &e" + mapId + "&c。"));
            return;
        }
        if (!target.getConfig().isEnabled()) {
            player.sendMessage(color("&c地图 &e" + targetId + " &c当前未启用。"));
            return;
        }
        // 换到正在打的地图没有意义；换回自己当前这张等于「重开本图」，允许
        if (!targetId.equals(arenaId)) {
            GameState state = target.getState();
            if (state != GameState.WAITING && state != GameState.COUNTDOWN) {
                player.sendMessage(color("&c地图 &e" + targetId + " &c正在比赛，无法换过去。"));
                return;
            }
        }
        if (begin(player, arenaId, Kind.MAP,
                "更换地图为 " + target.getConfig().getName(), null, null, targetId)) {
            player.sendMessage(color("&7换图需要 &e超过 2/3 &7的玩家同意。"));
        }
    }

    /** {@code /vsdr}：同一阵营投票投降，判对方获胜。 */
    public void startSurrender(Player player) {
        String arenaId = arenaIdOf(player);
        Team team = teamOf(player);
        if (arenaId == null || team == null) {
            player.sendMessage(color("&c你不在爆破模式的比赛里。"));
            return;
        }
        ArenaSession session = session(arenaId);
        if (session == null) {
            player.sendMessage(color("&c找不到竞技场 " + arenaId + "。"));
            return;
        }
        if (session.getState() == GameState.MATCH_END) {
            player.sendMessage(color("&c本场比赛已经结束了。"));
            return;
        }
        if (begin(player, arenaId, Kind.SURRENDER,
                (team == Team.T ? "T 方" : "CT 方") + "投降", team, null, null)) {
            player.sendMessage(color("&7投降需要 &e全队一致同意&7，通过后判对方获胜。"));
        }
    }

    // ------------------------------------------------------------------
    // 表态
    // ------------------------------------------------------------------

    /** {@code /vyes} 与 {@code /vno}。 */
    public void vote(Player player, boolean yes) {
        String arenaId = arenaIdOf(player);
        Vote vote = arenaId == null ? null : active.get(arenaId);
        if (vote == null) {
            player.sendMessage(color("&c当前没有进行中的投票。"));
            return;
        }
        UUID id = player.getUniqueId();
        if (!vote.voters.contains(id)) {
            player.sendMessage(color("&c你没有资格参与这个投票。"));
            return;
        }
        if (vote.target != null && vote.target.equals(id)) {
            player.sendMessage(color("&c你正被投票，已经默认反对。"));
            return;
        }
        if (yes ? vote.yes.contains(id) : vote.no.contains(id)) {
            player.sendMessage(color("&7你已经投过" + (yes ? "同意" : "反对") + "票了。"));
            return;
        }
        if (yes) {
            vote.no.remove(id);
            vote.yes.add(id);
        } else {
            vote.yes.remove(id);
            vote.no.add(id);
        }

        int remaining = Math.max(0,
                (int) ((vote.endMillis - System.currentTimeMillis() + 999) / 1000));
        updateBar(vote, remaining);
        broadcast(vote.arenaId, "&e[投票] &f" + player.getName() + " &7投了"
                + (yes ? "&a同意" : "&c反对")
                + "&7（&a" + vote.yes.size() + " &7/ &c" + vote.no.size()
                + " &7/ 共 " + vote.voters.size() + "）");
        evaluate(vote);
    }

    // ------------------------------------------------------------------
    // 计票
    // ------------------------------------------------------------------

    private boolean begin(Player initiator, String arenaId, Kind kind, String subject,
                          Team team, UUID target, String mapArenaId) {
        Vote running = active.get(arenaId);
        if (running != null) {
            initiator.sendMessage(color("&c这个房间已经有一个进行中的投票了：&f" + running.subject
                    + "&c（输入 /vyes 或 /vno）。"));
            return false;
        }
        long now = System.currentTimeMillis();
        long until = cooldownUntil.getOrDefault(key(arenaId, kind), 0L);
        if (until > now) {
            initiator.sendMessage(color("&c这个投票刚结束，请 &e"
                    + ((until - now + 999) / 1000) + " &c秒后再发起。"));
            return false;
        }

        Set<UUID> voters = collectVoters(arenaId, kind, team);
        if (voters.isEmpty() || !voters.contains(initiator.getUniqueId())) {
            initiator.sendMessage(color("&c你不在这个投票的参与名单里。"));
            return false;
        }
        if (kind == Kind.KICK && !voters.contains(target)) {
            initiator.sendMessage(color("&c对方不在本场比赛中。"));
            return false;
        }

        Vote vote = new Vote(arenaId, kind, subject, team, target, mapArenaId, voters,
                now + durationSeconds * 1000L);
        // 被踢者一开始就记一棵反对票：他既不能给自己投同意，
        // 也不会因为「还剩他这一票」让投票一直悬着
        if (target != null) {
            vote.no.add(target);
        }

        active.put(arenaId, vote);
        broadcast(arenaId, "&e[投票] &f" + initiator.getName() + " &7发起了投票：&f"
                + subject + "&7（" + durationSeconds + " 秒，输入 &a/vyes &7同意 或 &c/vno &7反对）");

        vote.bar = Bukkit.createBossBar(barTitle(vote, durationSeconds),
                BarColor.YELLOW, BarStyle.SEGMENTED_20);
        vote.bar.setProgress(1.0);
        for (UUID id : vote.voters) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                vote.bar.addPlayer(player);
            }
        }

        vote.ticker = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(vote), 20L, 20L);

        if (debug) {
            plugin.getLogger().info("[vote] " + arenaId + " 发起 " + kind + "：" + subject
                    + "，参与者 " + vote.voters.size() + "，需要 " + required(vote) + " 票同意");
        }
        return true;
    }

    /** 每投一票就判一次：够票立刻通过，票数不够立刻否决。 */
    private void evaluate(Vote vote) {
        if (active.get(vote.arenaId) != vote) {
            return;
        }
        int required = required(vote);
        int remaining = vote.voters.size() - vote.yes.size() - vote.no.size();
        if (vote.yes.size() >= required) {
            finish(vote, true);
        } else if (vote.yes.size() + remaining < required) {
            finish(vote, false);
        }
    }

    private void tick(Vote vote) {
        if (active.get(vote.arenaId) != vote) {
            return;
        }
        long remaining = vote.endMillis - System.currentTimeMillis();
        if (remaining <= 0) {
            finish(vote, vote.yes.size() >= required(vote));
            return;
        }
        // 暂停被管理员 / 倒计时结束时，解除暂停的投票就没有意义了
        if (vote.kind == Kind.UNPAUSE) {
            TechnicalPauseModule pause = pauseModule();
            if (pause == null || !pause.isPaused(vote.arenaId)) {
                cancel(vote, "技术暂停已经结束");
                return;
            }
        }
        updateBar(vote, (int) ((remaining + 999) / 1000));
    }

    /** 通过门槛：全员 / 除被踢者外全员 / 严格超过 2/3。 */
    private int required(Vote vote) {
        return switch (vote.kind) {
            case KICK -> Math.max(0, vote.voters.size() - 1);
            case MAP -> vote.voters.size() * 2 / 3 + 1;
            default -> vote.voters.size();
        };
    }

    private void finish(Vote vote, boolean passed) {
        if (active.get(vote.arenaId) != vote) {
            return;
        }
        close(vote, passed ? BarColor.GREEN : BarColor.RED);

        if (!passed) {
            broadcast(vote.arenaId, "&c[投票] " + vote.subject + " &c未通过&7（同意 &a"
                    + vote.yes.size() + " &7/ 反对 &c" + vote.no.size()
                    + " &7/ 共 " + vote.voters.size() + "）。");
            if (debug) {
                plugin.getLogger().info("[vote] " + vote.arenaId + " " + vote.kind + " 否决");
            }
            return;
        }

        broadcast(vote.arenaId, "&a[投票] " + vote.subject + " &a已通过&7（同意 &a"
                + vote.yes.size() + " &7/ 反对 &c" + vote.no.size()
                + " &7/ 共 " + vote.voters.size() + "）。");
        if (debug) {
            plugin.getLogger().info("[vote] " + vote.arenaId + " " + vote.kind + " 通过");
        }
        apply(vote);
    }

    /** 中途取消（对象跑了、暂停结束了、插件卸载……）。 */
    private void cancel(Vote vote, String reason) {
        if (active.get(vote.arenaId) != vote) {
            return;
        }
        close(vote, BarColor.WHITE);
        broadcast(vote.arenaId, "&7[投票] " + vote.subject + " &7已取消（" + reason + "）。");
        if (debug) {
            plugin.getLogger().info("[vote] " + vote.arenaId + " " + vote.kind
                    + " 取消：" + reason);
        }
    }

    private void close(Vote vote, BarColor color) {
        active.remove(vote.arenaId, vote);
        cooldownUntil.put(key(vote.arenaId, vote.kind),
                System.currentTimeMillis() + cooldownSeconds * 1000L);
        if (vote.ticker != null) {
            vote.ticker.cancel();
            vote.ticker = null;
        }
        if (vote.bar != null) {
            vote.bar.setColor(color);
            vote.bar.setVisible(false);
            vote.bar.removeAll();
            vote.bar = null;
        }
    }

    // ------------------------------------------------------------------
    // 执行
    // ------------------------------------------------------------------

    private void apply(Vote vote) {
        switch (vote.kind) {
            case UNPAUSE -> {
                TechnicalPauseModule pause = pauseModule();
                if (pause != null) {
                    pause.forceResume(vote.arenaId, "投票通过");
                }
            }
            case KICK -> kickOut(vote);
            case SURRENDER -> {
                ArenaSession session = session(vote.arenaId);
                Team winner = vote.team == Team.T ? Team.CT : Team.T;
                if (!endMatch(session, winner)) {
                    broadcast(vote.arenaId, "&c[投票] 提前结算失败，请管理员手动处理。");
                }
            }
            case MAP -> switchMap(vote);
        }
    }

    private void kickOut(Vote vote) {
        if (vote.target == null) {
            return;
        }
        bans.computeIfAbsent(vote.arenaId, k -> ConcurrentHashMap.newKeySet()).add(vote.target);

        Player target = Bukkit.getPlayer(vote.target);
        ArenaSession session = session(vote.arenaId);
        if (target != null && session != null) {
            try {
                session.leave(target, true);
            } catch (Throwable t) {
                plugin.getLogger().warning("投票踢人：移出竞技场失败：" + t);
            }
            target.sendMessage(color("&c你被队友投票踢出了本场比赛，本场无法再加入。"));
        }
        broadcast(vote.arenaId, "&c[投票] " + nameOf(vote.target) + " 已被踢出本场比赛。");
    }

    private void switchMap(Vote vote) {
        String targetId = vote.mapArenaId;
        ArenaSession from = session(vote.arenaId);
        ArenaSession to = session(targetId);
        if (to == null) {
            broadcast(vote.arenaId, "&c[投票] 目标地图不存在，换图取消。");
            return;
        }

        // 把当前场上所有人（含观战）都带上，别把观战者丢在原地
        List<UUID> movers = new ArrayList<>();
        if (from != null) {
            for (PlayerRecord record : from.getPlayers()) {
                movers.add(record.getUuid());
            }
        }

        Team leader = leadingTeam(from);
        broadcast(vote.arenaId, "&e[投票] 本场比赛提前结算"
                + (leader == null ? "" : "（" + teamLabel(leader) + " 领先）")
                + "，3 秒后切换到地图 &f" + to.getConfig().getName() + "&e。");
        if (!endMatch(from, leader)) {
            broadcast(vote.arenaId, "&c[投票] 提前结算失败，换图已取消。");
            return;
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> moveAll(movers, targetId),
                60L);
    }

    /** 结算完成后把玩家搬到新地图，并让 WBD 自己开新的一场。 */
    private void moveAll(List<UUID> movers, String targetArenaId) {
        ArenaSession to = session(targetArenaId);
        if (to == null) {
            return;
        }
        int moved = 0;
        for (UUID id : movers) {
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) {
                continue;
            }
            ArenaSession from = sessionOf(player);
            if (from != null) {
                try {
                    from.leave(player, true);
                } catch (Throwable t) {
                    plugin.getLogger().warning("换图：离开原竞技场失败：" + t);
                }
            }
            try {
                if (to.join(player, JoinMode.AUTO)) {
                    moved++;
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("换图：加入新竞技场失败：" + t);
            }
        }
        if (debug) {
            plugin.getLogger().info("[vote] 换图完成：" + moved + " 人进入 " + targetArenaId);
        }
    }

    /**
     * 反射调用 WBD 的提前结算。
     *
     * <p>WBD 没有公开「结束比赛」的 API，{@code endMatch(Team)} 是它自己结算时走的那条路：
     * 切到 MATCH_END、广播胜方与整场总结、抛出 {@link WbdMatchEndEvent}，
     * 之后由它自己的 tick 收尾。比起「把一队全杀光」，这条路不会多算一分，
     * 也不会打乱当前的比分。
     */
    private boolean endMatch(ArenaSession session, Team winner) {
        if (session == null || winner == null) {
            return false;
        }
        if (endMatchMethod == null) {
            return false;
        }
        try {
            endMatchMethod.invoke(session, winner);
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("投票：提前结算失败：" + t);
            return false;
        }
    }

    private void resolveEndMatchMethod() {
        try {
            endMatchMethod = ArenaSession.class.getDeclaredMethod("endMatch", Team.class);
            endMatchMethod.setAccessible(true);
        } catch (Throwable t) {
            endMatchMethod = null;
            plugin.getLogger().warning("比赛投票：无法定位 ArenaSession.endMatch，"
                    + "/vsdr 与 /vmap 的提前结算将不可用。");
        }
    }

    /** 当前比分领先方；平局时返回 T（只用于结算播报）。 */
    private Team leadingTeam(ArenaSession session) {
        if (session == null) {
            return Team.T;
        }
        int t = session.getScore(Team.T);
        int ct = session.getScore(Team.CT);
        return ct > t ? Team.CT : Team.T;
    }

    // ------------------------------------------------------------------
    // BossBar
    // ------------------------------------------------------------------

    private String barTitle(Vote vote, int seconds) {
        return color("&e投票 &7| &f" + vote.subject
                + " &7| &a同意 " + vote.yes.size()
                + " &7/ &c反对 " + vote.no.size()
                + " &7| &7剩余 &e" + seconds + "s");
    }

    private void updateBar(Vote vote, int seconds) {
        if (vote.bar == null) {
            return;
        }
        vote.bar.setTitle(barTitle(vote, seconds));
        vote.bar.setProgress(seconds <= 0 ? 0.0
                : Math.max(0.0, Math.min(1.0, seconds / (double) durationSeconds)));
    }

    // ------------------------------------------------------------------
    // 名单
    // ------------------------------------------------------------------

    private Set<UUID> collectVoters(String arenaId, Kind kind, Team team) {
        Set<UUID> ids = new LinkedHashSet<>();
        ArenaSession session = session(arenaId);
        if (session == null) {
            return ids;
        }
        boolean teamVote = kind == Kind.KICK || kind == Kind.SURRENDER;
        for (PlayerRecord record : session.getPlayers()) {
            Player player = Bukkit.getPlayer(record.getUuid());
            if (player == null || !player.isOnline()) {
                continue;
            }
            Team recordTeam = record.getTeam();
            if (teamVote) {
                if (recordTeam != team) {
                    continue;
                }
            } else if (!recordTeam.isPlaying() && !includeSpectators) {
                continue;
            }
            ids.add(record.getUuid());
        }
        return ids;
    }

    // ------------------------------------------------------------------
    // 事件
    // ------------------------------------------------------------------

    /** 投票中途有人离开：把他从名单里摘掉，必要时直接结束投票。 */
    @EventHandler
    public void onLeaveArena(WbdPlayerLeaveArenaEvent event) {
        drop(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        drop(event.getPlayer());
    }

    private void drop(Player player) {
        UUID id = player.getUniqueId();
        for (Vote vote : active.values()) {
            if (!vote.voters.remove(id)) {
                continue;
            }
            vote.yes.remove(id);
            vote.no.remove(id);
            if (id.equals(vote.target)) {
                cancel(vote, "被投票的玩家已离开");
                continue;
            }
            if (debug) {
                plugin.getLogger().info("[vote] " + player.getName() + " 离开，"
                        + vote.arenaId + " 剩余参与者 " + vote.voters.size());
            }
            // 少了一个人，门槛可能跟着变低，重新判一次
            evaluate(vote);
        }
    }

    /** 被投票踢出的人，本场不得再加入：加入后立刻请出去。 */
    @EventHandler
    public void onJoinArena(WbdPlayerJoinArenaEvent event) {
        if (!isBanned(event.getPlayer(), event.getArenaId())) {
            return;
        }
        Player player = event.getPlayer();
        player.sendMessage(color("&c你已被投票踢出本场比赛，无法再加入。"));
        Bukkit.getScheduler().runTask(plugin, () -> {
            ArenaSession session = session(event.getArenaId());
            if (session != null && session.contains(player)) {
                session.leave(player, true);
            }
        });
    }

    @EventHandler
    public void onMatchEnd(WbdMatchEndEvent event) {
        Vote vote = active.get(event.getArenaId());
        if (vote != null) {
            cancel(vote, "比赛已结束");
        }
        // 新的一场重新开始，黑名单没有理由继续留着
        bans.remove(event.getArenaId());
        String prefix = event.getArenaId() + "|";
        cooldownUntil.keySet().removeIf(key -> key.startsWith(prefix));
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /** 该玩家本场是否被投票踢出过。给菜单等加入入口做前置拦截。 */
    public boolean isBanned(Player player, String arenaId) {
        if (player == null || arenaId == null) {
            return false;
        }
        Set<UUID> set = bans.get(arenaId);
        return set != null && set.contains(player.getUniqueId());
    }

    /** 当前是否正在进行投票（供其他模块判断）。 */
    public boolean isVoting(String arenaId) {
        return active.containsKey(arenaId);
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private TechnicalPauseModule pauseModule() {
        AddonModule module = plugin.getModuleManager().getModule("technical-pause");
        if (module instanceof TechnicalPauseModule pause
                && plugin.getModuleManager().isEnabled(module)) {
            return pause;
        }
        return null;
    }

    private String arenaIdOf(Player player) {
        if (player == null || plugin.getApi() == null) {
            return null;
        }
        AddonMode mode = plugin.getApi().getModeFor(player);
        if (mode == null || !BombDefuseMode.MODE_ID.equals(mode.getModeId())) {
            return null;
        }
        return mode.getArenaId(player);
    }

    private Team teamOf(Player player) {
        if (player == null || plugin.getApi() == null) {
            return null;
        }
        AddonMode mode = plugin.getApi().getModeFor(player);
        if (mode == null || !BombDefuseMode.MODE_ID.equals(mode.getModeId())) {
            return null;
        }
        String teamId = mode.getTeamId(player);
        if ("T".equals(teamId)) {
            return Team.T;
        }
        if ("CT".equals(teamId)) {
            return Team.CT;
        }
        return null;
    }

    private ArenaSession session(String arenaId) {
        if (arenaId == null || plugin.getWbd() == null
                || plugin.getWbd().getArenaManager() == null) {
            return null;
        }
        return plugin.getWbd().getArenaManager().getSession(arenaId);
    }

    private ArenaSession sessionOf(Player player) {
        if (player == null || plugin.getWbd() == null
                || plugin.getWbd().getArenaManager() == null) {
            return null;
        }
        return plugin.getWbd().getArenaManager().getSession(player);
    }

    private Player findPlayer(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Player exact = Bukkit.getPlayerExact(name);
        return exact != null ? exact : Bukkit.getPlayer(name);
    }

    private String nameOf(UUID id) {
        Player player = id == null ? null : Bukkit.getPlayer(id);
        if (player != null) {
            return player.getName();
        }
        return id == null ? "?" : id.toString().substring(0, 8);
    }

    private String teamLabel(Team team) {
        if (team == Team.T) {
            return "T 方";
        }
        if (team == Team.CT) {
            return "CT 方";
        }
        return "观战";
    }

    private String key(String arenaId, Kind kind) {
        return arenaId + "|" + kind.name();
    }

    /** 竞技场里的所有人（含观战），用于广播。 */
    private void broadcast(String arenaId, String message) {
        String text = color(message);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (arenaId.equals(arenaIdOf(player))) {
                player.sendMessage(text);
            }
        }
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }

    // ------------------------------------------------------------------
    // 内部类
    // ------------------------------------------------------------------

    private static final class Vote {
        final String arenaId;
        final Kind kind;
        final String subject;
        final Team team;
        final UUID target;
        final String mapArenaId;
        final Set<UUID> voters = new LinkedHashSet<>();
        final Set<UUID> yes = new LinkedHashSet<>();
        final Set<UUID> no = new LinkedHashSet<>();
        final long endMillis;
        BossBar bar;
        BukkitTask ticker;

        Vote(String arenaId, Kind kind, String subject, Team team, UUID target,
             String mapArenaId, Set<UUID> voters, long endMillis) {
            this.arenaId = arenaId;
            this.kind = kind;
            this.subject = subject;
            this.team = team;
            this.target = target;
            this.mapArenaId = mapArenaId;
            this.voters.addAll(voters);
            this.endMillis = endMillis;
        }
    }
}
