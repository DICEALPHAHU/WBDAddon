package alphahu.wbdaddon.module;

import alphahu.wbdaddon.WBDAddon;
import alphahu.wbdaddon.api.AddonMode;
import alphahu.wbdaddon.mode.BombDefuseMode;
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
 * <p>回合结束时屏幕中央：
 * <pre>
 * T 阵营胜利
 * MVP: AlphaHu | 本回合 T 方 42 秒全歼 CT
 * </pre>
 *
 * <p>副标题从一大组「本场趣闻」里<b>随机挑一条</b>。候选覆盖：
 * 全歼/拆包/爆炸/时间到、首杀、首个阵亡、回合用时、总时长、比分、分差、
 * 最惨、最准、爆头王、最长/最短回合、连胜、C4 爆炸次数、总击杀、MVP K/D 等。
 *
 * @author AlphaHu
 */
public class RoundSummaryModule implements AddonModule, Listener {

    private final WBDAddon plugin;

    /** 本场射击统计。 */
    private final Map<UUID, Counter> counters = new ConcurrentHashMap<>();

    /** 每个竞技场的计时与连胜等状态。 */
    private final Map<String, ArenaStats> arenaStats = new ConcurrentHashMap<>();

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
        counters.clear();
        arenaStats.clear();
        plugin.getLogger().info("回合结算展示已卸载。");
    }

    // ------------------------------------------------------------------
    // 计数（由 TacZStatsListener 调用）
    // ------------------------------------------------------------------

    void countShot(Player player) {
        if (!participates(player)) return;
        counters.computeIfAbsent(player.getUniqueId(), k -> new Counter()).shots++;
    }

    void countHit(Player player, boolean headshot) {
        if (!participates(player)) return;
        Counter c = counters.computeIfAbsent(player.getUniqueId(), k -> new Counter());
        c.hits++;
        if (headshot) c.headshots++;
    }

    private boolean participates(Player player) {
        if (plugin.getApi() == null) return false;
        AddonMode mode = plugin.getApi().getModeFor(player);
        return mode != null && BombDefuseMode.MODE_ID.equals(mode.getModeId())
                && mode.isParticipating(player);
    }

    // ------------------------------------------------------------------
    // 回合生命周期
    // ------------------------------------------------------------------

    @EventHandler
    public void onRoundStart(WbdRoundStartEvent event) {
        String arenaId = event.getArenaId();
        ArenaStats stats = arenaStats.computeIfAbsent(arenaId, k -> new ArenaStats());

        stats.roundStart = System.currentTimeMillis();
        stats.firstKillAt = -1;
        stats.firstKiller = null;
        stats.firstKillerTeam = null;
        stats.firstVictim = null;
        stats.roundDeaths = 0;

        if (event.getRound() <= 1) {
            counters.clear();
            stats.matchStart = stats.roundStart;
            stats.minRoundSeconds = Integer.MAX_VALUE;
            stats.maxRoundSeconds = 0;
            stats.lastWinner = null;
            stats.streak = 0;
            stats.bombExplosions = 0;
            stats.defuses = 0;
        }
    }

    @EventHandler
    public void onEliminated(WbdPlayerEliminatedEvent event) {
        ArenaStats stats = arenaStats.computeIfAbsent(event.getArenaId(), k -> new ArenaStats());
        stats.roundDeaths++;

        if (stats.firstKillAt < 0) {
            stats.firstKillAt = System.currentTimeMillis();
            Player killer = event.getKiller();
            Player victim = event.getVictim();
            stats.firstKiller = killer == null ? null : killer.getName();
            stats.firstVictim = victim == null ? null : victim.getName();
            if (killer != null) {
                ArenaSession session = session(event.getArenaId());
                stats.firstKillerTeam = session == null ? null : session.getTeam(killer);
            }
        }
    }

    @EventHandler
    public void onMatchEnd(WbdMatchEndEvent event) {
        counters.clear();
        arenaStats.remove(event.getArenaId());
    }

    // ------------------------------------------------------------------
    // 显示
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRoundEnd(WbdRoundEndEvent event) {
        String arenaId = event.getArenaId();
        ArenaSession session = session(arenaId);
        if (session == null) return;

        ArenaStats stats = arenaStats.computeIfAbsent(arenaId, k -> new ArenaStats());
        long now = System.currentTimeMillis();
        int roundSeconds = stats.roundStart <= 0 ? 0
                : (int) Math.max(0, (now - stats.roundStart) / 1000);
        int matchSeconds = stats.matchStart <= 0 ? roundSeconds
                : (int) Math.max(0, (now - stats.matchStart) / 1000);
        int firstKillSeconds = stats.firstKillAt < 0 ? -1
                : (int) Math.max(0, (stats.firstKillAt - stats.roundStart) / 1000);

        // 更新本场统计
        stats.minRoundSeconds = Math.min(stats.minRoundSeconds, roundSeconds);
        stats.maxRoundSeconds = Math.max(stats.maxRoundSeconds, roundSeconds);
        if (stats.lastWinner == event.getWinner()) {
            stats.streak++;
        } else {
            stats.lastWinner = event.getWinner();
            stats.streak = 1;
        }
        if (event.getReason() == RoundEndReason.BOMB_EXPLODED) stats.bombExplosions++;
        if (event.getReason() == RoundEndReason.BOMB_DEFUSED) stats.defuses++;

        Team winner = event.getWinner();
        PlayerRecord mvp = mvp(session, winner);

        int shots = 0;
        int hits = 0;
        int headshots = 0;
        for (Counter c : counters.values()) {
            shots += c.shots;
            hits += c.hits;
            headshots += c.headshots;
        }

        int plants = 0;
        int defuses = 0;
        int deaths = 0;
        int totalKills = 0;
        PlayerRecord mostDeaths = null;
        for (PlayerRecord record : session.getPlayers()) {
            plants += record.getPlants();
            defuses += record.getDefuses();
            deaths += record.getDeaths();
            totalKills += record.getKills();
            if (mostDeaths == null || record.getDeaths() > mostDeaths.getDeaths()) {
                mostDeaths = record;
            }
        }

        PlayerRecord bestAccuracy = null;
        double bestAccuracyValue = -1;
        PlayerRecord topHeadshots = null;
        for (Map.Entry<UUID, Counter> entry : counters.entrySet()) {
            Counter c = entry.getValue();
            if (c.shots >= 10) {
                double acc = c.hits / (double) c.shots;
                if (acc > bestAccuracyValue) {
                    bestAccuracyValue = acc;
                    bestAccuracy = session.getRecord(entry.getKey());
                }
            }
            if (c.headshots > 0 && (topHeadshots == null
                    || c.headshots > counters.get(topHeadshots.getUuid()).headshots)) {
                topHeadshots = session.getRecord(entry.getKey());
            }
        }

        String title = winnerName(winner);
        String fact = randomFact(new Facts(event, stats, session, roundSeconds, matchSeconds,
                firstKillSeconds, shots, hits, headshots, plants, defuses, deaths,
                totalKills, mvp, mostDeaths, bestAccuracy, bestAccuracyValue, topHeadshots));
        String subtitle = "&eMVP: &f" + (mvp == null ? "无" : mvp.getName())
                + " &7| " + fact;

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (inArena(player, arenaId)) {
                player.sendTitle(color(title), color(subtitle), fadeIn, stay, fadeOut);
            }
        }
    }

    /** 从候选里随机挑一条。 */
    private String randomFact(Facts f) {
        List<String> facts = new ArrayList<>();

        switch (f.event.getReason()) {
            case CT_ELIMINATED -> facts.add("&7本回合 &cT 方 &f" + f.roundSeconds + " &7秒全歼 CT");
            case T_ELIMINATED -> facts.add("&7本回合 &9CT 方 &f" + f.roundSeconds + " &7秒全歼 T");
            case BOMB_EXPLODED -> facts.add("&7C4 爆炸，回合用时 &f" + f.roundSeconds + " &7秒");
            case BOMB_DEFUSED -> facts.add("&7CT 拆包成功，回合用时 &f" + f.roundSeconds + " &7秒");
            case TIME_EXPIRED -> facts.add("&7时间耗尽，本回合打了 &f" + f.roundSeconds + " &7秒");
            default -> {
            }
        }

        if (f.event.getRound() > 0) {
            facts.add("&7第 &f" + f.event.getRound() + " &7回合结束");
        }
        int t = f.event.getTScore();
        int ct = f.event.getCtScore();
        facts.add("&7当前比分 &cT " + t + " &7: &9CT " + ct);
        if (t != ct) {
            facts.add("&7分差 &f" + Math.abs(t - ct) + " &7分");
        }

        if (f.stats.firstKiller != null) {
            String team = f.stats.firstKillerTeam == null ? ""
                    : (f.stats.firstKillerTeam == Team.T ? "&cT 方" : "&9CT 方");
            facts.add("&7首杀：&f" + f.stats.firstKiller
                    + (team.isEmpty() ? "" : " &7（" + team + "&7）")
                    + " &7— 开局 &f" + f.firstKillSeconds + " &7秒");
        }
        if (f.stats.firstVictim != null) {
            facts.add("&7首个阵亡：&f" + f.stats.firstVictim);
        }

        if (f.roundSeconds > 0) {
            facts.add("&7本回合用时 &f" + f.roundSeconds + " &7秒");
        }
        if (f.matchSeconds > 0) {
            facts.add("&7本场已进行 &f" + clock(f.matchSeconds));
        }
        if (f.stats.roundDeaths > 0) {
            facts.add("&7本回合 &f" + f.stats.roundDeaths + " &7人阵亡");
        } else {
            facts.add("&7本回合无人阵亡");
        }

        if (f.mostDeaths != null && f.mostDeaths.getDeaths() > 0) {
            facts.add("&7本场最惨：&f" + f.mostDeaths.getName()
                    + " &7阵亡 &f" + f.mostDeaths.getDeaths() + " &7次");
        }
        if (f.bestAccuracy != null) {
            facts.add("&7本场最准：&f" + f.bestAccuracy.getName()
                    + " &7命中率 &f" + Math.round(f.bestAccuracyValue * 100) + "%");
        }
        if (f.topHeadshots != null && f.headshots > 0) {
            facts.add("&7本场爆头王：&f" + f.topHeadshots.getName());
        }
        if (f.mvp != null && f.mvp.getKills() > 0) {
            facts.add("&7MVP &f" + f.mvp.getName() + " &7拿下 &f" + f.mvp.getKills() + " &7杀");
            facts.add("&7MVP K/D：&f" + f.mvp.getKills() + "&7/&f" + f.mvp.getDeaths());
        }

        if (f.shots > 0) {
            facts.add("&7本场发射 &f" + f.shots + " &7发，命中 &f" + f.hits + " &7发");
            facts.add("&7本场命中率 &f" + percent(f.hits, f.shots));
        }
        if (f.headshots > 0) {
            facts.add("&7本场爆头 &f" + f.headshots + " &7次");
        }

        if (f.plants > 0) {
            facts.add("&7本场下包 &f" + f.plants + " &7次");
        }
        if (f.defuses > 0) {
            facts.add("&7本场拆包 &f" + f.defuses + " &7次");
        }
        if (f.stats.bombExplosions > 0) {
            facts.add("&7本场 C4 爆炸 &f" + f.stats.bombExplosions + " &7次");
        }
        if (f.totalKills > 0) {
            facts.add("&7本场总击杀 &f" + f.totalKills + " &7人");
        }
        if (f.deaths > 0) {
            facts.add("&7本场累计阵亡 &f" + f.deaths + " &7人");
        }

        if (f.stats.streak >= 2) {
            String who = f.stats.lastWinner == Team.T ? "&cT 方" : "&9CT 方";
            facts.add("&7" + who + " &7已连胜 &f" + f.stats.streak + " &7回合");
        }
        if (f.event.getRound() > 1 && f.stats.minRoundSeconds < Integer.MAX_VALUE) {
            facts.add("&7本场最短回合 &f" + f.stats.minRoundSeconds + " &7秒");
            facts.add("&7本场最长回合 &f" + f.stats.maxRoundSeconds + " &7秒");
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

    private PlayerRecord mvp(ArenaSession session, Team winner) {
        PlayerRecord best = null;
        for (PlayerRecord record : session.getPlayers()) {
            if (winner != null && record.getTeam() != winner) continue;
            if (best == null || better(record, best)) {
                best = record;
            }
        }
        return best;
    }

    private boolean better(PlayerRecord a, PlayerRecord b) {
        if (a.getKills() != b.getKills()) return a.getKills() > b.getKills();
        int aObj = a.getPlants() + a.getDefuses();
        int bObj = b.getPlants() + b.getDefuses();
        return aObj > bObj;
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

    private String clock(int seconds) {
        return String.format("%d:%02d", seconds / 60, seconds % 60);
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

    private static final class ArenaStats {
        long roundStart;
        long matchStart;
        long firstKillAt = -1;
        String firstKiller;
        Team firstKillerTeam;
        String firstVictim;
        int roundDeaths;
        int minRoundSeconds = Integer.MAX_VALUE;
        int maxRoundSeconds;
        Team lastWinner;
        int streak;
        int bombExplosions;
        int defuses;
    }

    private static final class Facts {
        final WbdRoundEndEvent event;
        final ArenaStats stats;
        final ArenaSession session;
        final int roundSeconds;
        final int matchSeconds;
        final int firstKillSeconds;
        final int shots;
        final int hits;
        final int headshots;
        final int plants;
        final int defuses;
        final int deaths;
        final int totalKills;
        final PlayerRecord mvp;
        final PlayerRecord mostDeaths;
        final PlayerRecord bestAccuracy;
        final double bestAccuracyValue;
        final PlayerRecord topHeadshots;

        Facts(WbdRoundEndEvent event, ArenaStats stats, ArenaSession session,
              int roundSeconds, int matchSeconds, int firstKillSeconds,
              int shots, int hits, int headshots, int plants, int defuses,
              int deaths, int totalKills, PlayerRecord mvp, PlayerRecord mostDeaths,
              PlayerRecord bestAccuracy, double bestAccuracyValue,
              PlayerRecord topHeadshots) {
            this.event = event;
            this.stats = stats;
            this.session = session;
            this.roundSeconds = roundSeconds;
            this.matchSeconds = matchSeconds;
            this.firstKillSeconds = firstKillSeconds;
            this.shots = shots;
            this.hits = hits;
            this.headshots = headshots;
            this.plants = plants;
            this.defuses = defuses;
            this.deaths = deaths;
            this.totalKills = totalKills;
            this.mvp = mvp;
            this.mostDeaths = mostDeaths;
            this.bestAccuracy = bestAccuracy;
            this.bestAccuracyValue = bestAccuracyValue;
            this.topHeadshots = topHeadshots;
        }
    }
}
