package alphahu.wbdaddon.module;

import alphahu.wbdaddon.WBDAddon;
import alphahu.wbdaddon.api.AddonMode;
import alphahu.wbdaddon.mode.BombDefuseMode;
import com.warz.bombdefuse.api.WbdBombDefuseCompleteEvent;
import com.warz.bombdefuse.api.WbdBombPlantCompleteEvent;
import com.warz.bombdefuse.api.WbdMatchEndEvent;
import com.warz.bombdefuse.api.WbdPlayerEliminatedEvent;
import com.warz.bombdefuse.api.WbdRoundEndEvent;
import com.warz.bombdefuse.api.WbdRoundStartEvent;
import com.warz.bombdefuse.arena.ArenaSession;
import com.warz.bombdefuse.arena.PlayerRecord;
import com.warz.bombdefuse.model.RoundEndReason;
import com.warz.bombdefuse.model.Team;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 回合结算展示。
 *
 * <pre>
 * T 阵营胜利
 * MVP: AlphaHu
 * 本回合 T 方 42 秒全歼 CT
 * </pre>
 *
 * <p>MVP 是<b>整场比赛</b>的 MVP（按 CS 的算法：击杀 + 下包/拆包加权，平手比死亡少），
 * 每回合结算时按当前累计数据重新计算。
 *
 * <p>趣闻<b>只取本回合</b>的数据：全歼用时、首杀/首个阵亡、回合阵亡数、
 * 回合击杀王/最惨/最准/爆头王、下包拆包、发射命中……
 *
 * <p>屏幕标题只有两行，所以第三行（趣闻）放在聊天栏。
 *
 * @author AlphaHu
 */
public class RoundSummaryModule implements AddonModule, Listener {

    private final WBDAddon plugin;

    /** 每个竞技场的本回合统计。 */
    private final Map<String, ArenaState> arenaStates = new ConcurrentHashMap<>();

    private TacZStatsListener tacZListener;
    private int fadeIn;
    private int stay;
    private int fadeOut;

    public RoundSummaryModule(WBDAddon plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getId() {
        return "round-summary";
    }

    @Override
    public String getDisplayName() {
        return "回合结算展示";
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }

    @Override
    public void onEnable() {
        this.fadeIn = plugin.getConfig().getInt("modules.round-summary.title-fade-in", 10);
        this.stay = plugin.getConfig().getInt("modules.round-summary.title-stay", 60);
        this.fadeOut = plugin.getConfig().getInt("modules.round-summary.title-fade-out", 20);

        Bukkit.getPluginManager().registerEvents(this, plugin);
        if (plugin.getTacZApi() != null) {
            try {
                this.tacZListener = new TacZStatsListener(this);
                Bukkit.getPluginManager().registerEvents(tacZListener, plugin);
            } catch (Throwable t) {
                this.tacZListener = null;
                plugin.getLogger().warning("回合结算：接入 TacZ 射击统计失败：" + t);
            }
        }
        plugin.getLogger().info("回合结算展示已启动"
                + (tacZListener != null ? "（含 TacZ 射击统计）" : "（无 TacZ，仅 MVP）") + "。");
    }

    @Override
    public void onDisable() {
        HandlerList.unregisterAll(this);
        if (tacZListener != null) {
            HandlerList.unregisterAll(tacZListener);
            tacZListener = null;
        }
        arenaStates.clear();
        plugin.getLogger().info("回合结算展示已卸载。");
    }

    // ------------------------------------------------------------------
    // 本回合计数（由 TacZStatsListener 调用）
    // ------------------------------------------------------------------

    void countShot(Player player) {
        ArenaState state = stateFor(player);
        if (state != null) {
            state.counter(player.getUniqueId()).shots++;
        }
    }

    void countHit(Player player, boolean headshot) {
        ArenaState state = stateFor(player);
        if (state == null) return;
        Counter c = state.counter(player.getUniqueId());
        c.hits++;
        if (headshot) c.headshots++;
    }

    private ArenaState stateFor(Player player) {
        if (plugin.getApi() == null) return null;
        AddonMode mode = plugin.getApi().getModeFor(player);
        if (mode == null || !BombDefuseMode.MODE_ID.equals(mode.getModeId())
                || !mode.isParticipating(player)) {
            return null;
        }
        return arenaStates.get(mode.getArenaId(player));
    }

    // ------------------------------------------------------------------
    // 回合生命周期
    // ------------------------------------------------------------------

    @EventHandler
    public void onRoundStart(WbdRoundStartEvent event) {
        ArenaState state = arenaStates.computeIfAbsent(
                event.getArenaId(), k -> new ArenaState());
        state.resetForRound(System.currentTimeMillis());
    }

    @EventHandler
    public void onEliminated(WbdPlayerEliminatedEvent event) {
        ArenaState state = arenaStates.computeIfAbsent(
                event.getArenaId(), k -> new ArenaState());
        state.roundDeathsTotal++;

        Player killer = event.getKiller();
        Player victim = event.getVictim();
        if (killer != null) {
            state.roundKills.merge(killer.getUniqueId(), 1, Integer::sum);
        }
        if (victim != null) {
            state.roundDeaths.merge(victim.getUniqueId(), 1, Integer::sum);
        }

        if (state.firstKillAt < 0) {
            state.firstKillAt = System.currentTimeMillis();
            state.firstKiller = killer == null ? null : killer.getName();
            state.firstVictim = victim == null ? null : victim.getName();
            if (killer != null) {
                ArenaSession session = session(event.getArenaId());
                state.firstKillerTeam = session == null ? null : session.getTeam(killer);
            }
        }
    }

    @EventHandler
    public void onPlant(WbdBombPlantCompleteEvent event) {
        ArenaState state = arenaStates.get(event.getArenaId());
        if (state != null) state.roundPlants++;
    }

    @EventHandler
    public void onDefuse(WbdBombDefuseCompleteEvent event) {
        ArenaState state = arenaStates.get(event.getArenaId());
        if (state != null) state.roundDefuses++;
    }

    @EventHandler
    public void onMatchEnd(WbdMatchEndEvent event) {
        arenaStates.remove(event.getArenaId());
    }

    // ------------------------------------------------------------------
    // 显示
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRoundEnd(WbdRoundEndEvent event) {
        String arenaId = event.getArenaId();
        ArenaSession session = session(arenaId);
        if (session == null) return;

        ArenaState state = arenaStates.computeIfAbsent(arenaId, k -> new ArenaState());
        long now = System.currentTimeMillis();
        int roundSeconds = state.roundStart <= 0 ? 0
                : (int) Math.max(0, (now - state.roundStart) / 1000);
        int firstKillSeconds = state.firstKillAt < 0 ? -1
                : (int) Math.max(0, (state.firstKillAt - state.roundStart) / 1000);

        // 整场比赛 MVP（CS 式：击杀 + 下包/拆包加权，平手比死亡少）
        PlayerRecord mvp = matchMvp(session);

        String title = winnerName(event.getWinner());
        String subtitle = "&eMVP: &f" + (mvp == null ? "无" : mvp.getName());
        String fact = randomFact(event, state, session, roundSeconds, firstKillSeconds);

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!inArena(player, arenaId)) continue;
            player.resetTitle();
            player.sendTitle(color(title), color(subtitle), fadeIn, stay, fadeOut);
            player.sendMessage(color("&e[回合结算] " + fact));
        }
    }

    /** 整场 MVP。 */
    private PlayerRecord matchMvp(ArenaSession session) {
        PlayerRecord best = null;
        double bestScore = -1;
        for (PlayerRecord record : session.getPlayers()) {
            double score = record.getKills() * 2.0
                    + record.getPlants() * 3.0
                    + record.getDefuses() * 3.0;
            if (best == null || score > bestScore
                    || (score == bestScore && record.getDeaths() < best.getDeaths())) {
                best = record;
                bestScore = score;
            }
        }
        return best;
    }

    /** 只取本回合的数据。 */
    private String randomFact(WbdRoundEndEvent event, ArenaState state,
                              ArenaSession session, int roundSeconds, int firstKillSeconds) {
        List<String> facts = new ArrayList<>();

        switch (event.getReason()) {
            case CT_ELIMINATED -> facts.add("&7本回合 &cT 方 &f" + roundSeconds + " &7秒全歼 CT");
            case T_ELIMINATED -> facts.add("&7本回合 &9CT 方 &f" + roundSeconds + " &7秒全歼 T");
            case BOMB_EXPLODED -> facts.add("&7本回合 C4 爆炸，用时 &f" + roundSeconds + " &7秒");
            case BOMB_DEFUSED -> facts.add("&7本回合 CT 拆包成功，用时 &f" + roundSeconds + " &7秒");
            case TIME_EXPIRED -> facts.add("&7本回合时间耗尽，打了 &f" + roundSeconds + " &7秒");
            default -> {
            }
        }

        if (roundSeconds > 0) {
            facts.add("&7本回合用时 &f" + roundSeconds + " &7秒");
        }
        if (state.roundDeathsTotal > 0) {
            facts.add("&7本回合 &f" + state.roundDeathsTotal + " &7人阵亡");
        } else {
            facts.add("&7本回合无人阵亡");
        }

        if (state.firstKiller != null) {
            String team = state.firstKillerTeam == null ? ""
                    : (state.firstKillerTeam == Team.T ? "&cT 方" : "&9CT 方");
            facts.add("&7本回合首杀：&f" + state.firstKiller
                    + (team.isEmpty() ? "" : " &7（" + team + "&7）")
                    + " &7— 开局 &f" + firstKillSeconds + " &7秒");
        }
        if (state.firstVictim != null) {
            facts.add("&7本回合首个阵亡：&f" + state.firstVictim);
        }

        // 本回合击杀王 / 最惨
        UUID topKiller = null;
        int topKills = 0;
        for (Map.Entry<UUID, Integer> entry : state.roundKills.entrySet()) {
            if (entry.getValue() > topKills) {
                topKills = entry.getValue();
                topKiller = entry.getKey();
            }
        }
        if (topKiller != null) {
            PlayerRecord record = session.getRecord(topKiller);
            if (record != null) {
                facts.add("&7本回合击杀王：&f" + record.getName() + " &7（&f" + topKills + " &7杀）");
            }
        }

        UUID worst = null;
        int worstDeaths = 0;
        for (Map.Entry<UUID, Integer> entry : state.roundDeaths.entrySet()) {
            if (entry.getValue() > worstDeaths) {
                worstDeaths = entry.getValue();
                worst = entry.getKey();
            }
        }
        if (worst != null) {
            PlayerRecord record = session.getRecord(worst);
            if (record != null) {
                facts.add("&7本回合最惨：&f" + record.getName() + " &7阵亡 &f" + worstDeaths + " &7次");
            }
        }

        // 本回合射击
        int shots = 0;
        int hits = 0;
        int headshots = 0;
        PlayerRecord bestAccuracy = null;
        double bestAccuracyValue = -1;
        PlayerRecord topHeadshots = null;
        int topHeadshotCount = 0;
        for (Map.Entry<UUID, Counter> entry : state.counters.entrySet()) {
            Counter c = entry.getValue();
            shots += c.shots;
            hits += c.hits;
            headshots += c.headshots;

            if (c.shots >= 10) {
                double acc = c.hits / (double) c.shots;
                if (acc > bestAccuracyValue) {
                    bestAccuracyValue = acc;
                    bestAccuracy = session.getRecord(entry.getKey());
                }
            }
            if (c.headshots > topHeadshotCount) {
                topHeadshotCount = c.headshots;
                topHeadshots = session.getRecord(entry.getKey());
            }
        }

        if (shots > 0) {
            facts.add("&7本回合发射 &f" + shots + " &7发，命中 &f" + hits + " &7发");
            facts.add("&7本回合命中率 &f" + percent(hits, shots));
        }
        if (headshots > 0) {
            facts.add("&7本回合爆头 &f" + headshots + " &7次");
        }
        if (bestAccuracy != null) {
            facts.add("&7本回合最准：&f" + bestAccuracy.getName()
                    + " &7命中率 &f" + Math.round(bestAccuracyValue * 100) + "%");
        }
        if (topHeadshots != null && topHeadshotCount > 0) {
            facts.add("&7本回合爆头王：&f" + topHeadshots.getName());
        }

        // 本回合目标
        if (state.roundPlants > 0) {
            facts.add("&7本回合下包 &f" + state.roundPlants + " &7次");
        }
        if (state.roundDefuses > 0) {
            facts.add("&7本回合拆包 &f" + state.roundDefuses + " &7次");
        }

        if (facts.isEmpty()) {
            return "&7本回合结束";
        }
        return facts.get(ThreadLocalRandom.current().nextInt(facts.size()));
    }

    private String winnerName(Team winner) {
        if (winner == null) return "&7回合结束";
        return winner == Team.T ? "&cT 阵营胜利" : "&9CT 阵营胜利";
    }

    private ArenaSession session(String arenaId) {
        if (plugin.getWbd() == null || plugin.getWbd().getArenaManager() == null) return null;
        return plugin.getWbd().getArenaManager().getSession(arenaId);
    }

    private boolean inArena(Player player, String arenaId) {
        if (plugin.getApi() == null) return false;
        AddonMode mode = plugin.getApi().getModeFor(player);
        if (mode == null || !BombDefuseMode.MODE_ID.equals(mode.getModeId())) return false;
        if (!mode.isPlaying(player)) return false;
        return arenaId.equals(mode.getArenaId(player));
    }

    private String percent(int part, int total) {
        return total <= 0 ? "0%" : Math.round(part * 100.0 / total) + "%";
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }

    private static final class Counter {
        int shots;
        int hits;
        int headshots;
    }

    private static final class ArenaState {
        long roundStart;
        long firstKillAt = -1;
        String firstKiller;
        Team firstKillerTeam;
        String firstVictim;
        int roundDeathsTotal;
        int roundPlants;
        int roundDefuses;
        final Map<UUID, Counter> counters = new ConcurrentHashMap<>();
        final Map<UUID, Integer> roundKills = new ConcurrentHashMap<>();
        final Map<UUID, Integer> roundDeaths = new ConcurrentHashMap<>();

        void resetForRound(long now) {
            roundStart = now;
            firstKillAt = -1;
            firstKiller = null;
            firstKillerTeam = null;
            firstVictim = null;
            roundDeathsTotal = 0;
            roundPlants = 0;
            roundDefuses = 0;
            counters.clear();
            roundKills.clear();
            roundDeaths.clear();
        }

        Counter counter(UUID id) {
            return counters.computeIfAbsent(id, k -> new Counter());
        }
    }
}
