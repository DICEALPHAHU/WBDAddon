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
 * <p>回合结束时屏幕中央上方：
 * <pre>
 * T 阵营胜利 | MVP: AlphaHu
 * 本回合 T 方 42 秒全歼 CT
 * </pre>
 *
 * <p>第二行从一组「本场趣闻」里<b>随机挑一条</b>：全歼用时、首杀时间、C4 爆炸、
 * 拆包、命中率、爆头数、下包次数、总时长……避免每回合都是同一句话。
 *
 * <p>射击数据来自 TacZ 桥接事件；没装 TacZ 时自动跳过相关候选。
 *
 * @author AlphaHu
 */
public class RoundSummaryModule implements AddonModule, Listener {

    private final WBDAddon plugin;

    /** 本场射击统计。 */
    private final Map<UUID, Counter> counters = new ConcurrentHashMap<>();

    /** 本回合开始时间。 */
    private final Map<String, Long> roundStart = new ConcurrentHashMap<>();
    /** 本场比赛开始时间。 */
    private final Map<String, Long> matchStart = new ConcurrentHashMap<>();
    /** 本回合首杀时间。 */
    private final Map<String, Long> firstKill = new ConcurrentHashMap<>();
    /** 本回合阵亡人数。 */
    private final Map<String, Integer> roundDeaths = new ConcurrentHashMap<>();

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
        roundStart.clear();
        matchStart.clear();
        firstKill.clear();
        roundDeaths.clear();
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
        long now = System.currentTimeMillis();
        roundStart.put(arenaId, now);
        firstKill.remove(arenaId);
        roundDeaths.remove(arenaId);

        if (event.getRound() <= 1) {
            counters.clear();
            matchStart.put(arenaId, now);
        }
    }

    @EventHandler
    public void onEliminated(WbdPlayerEliminatedEvent event) {
        String arenaId = event.getArenaId();
        firstKill.putIfAbsent(arenaId, System.currentTimeMillis());
        roundDeaths.merge(arenaId, 1, Integer::sum);
    }

    @EventHandler
    public void onMatchEnd(WbdMatchEndEvent event) {
        String arenaId = event.getArenaId();
        counters.clear();
        roundStart.remove(arenaId);
        matchStart.remove(arenaId);
        firstKill.remove(arenaId);
        roundDeaths.remove(arenaId);
    }

    // ------------------------------------------------------------------
    // 显示
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRoundEnd(WbdRoundEndEvent event) {
        ArenaSession session = session(event.getArenaId());
        if (session == null) return;

        String arenaId = event.getArenaId();
        long now = System.currentTimeMillis();
        long roundStartAt = roundStart.getOrDefault(arenaId, now);
        long matchStartAt = matchStart.getOrDefault(arenaId, roundStartAt);
        int roundSeconds = (int) Math.max(0, (now - roundStartAt) / 1000);
        int matchSeconds = (int) Math.max(0, (now - matchStartAt) / 1000);
        Long firstKillAt = firstKill.get(arenaId);
        int firstKillSeconds = firstKillAt == null ? -1
                : (int) Math.max(0, (firstKillAt - roundStartAt) / 1000);
        int roundDeathCount = roundDeaths.getOrDefault(arenaId, 0);

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
        for (PlayerRecord record : session.getPlayers()) {
            plants += record.getPlants();
            defuses += record.getDefuses();
            deaths += record.getDeaths();
        }

        String title = winnerName(winner) + " &7| &eMVP: &f"
                + (mvp == null ? "无" : mvp.getName());
        String fact = randomFact(new Facts(event.getReason(), roundSeconds, matchSeconds,
                firstKillSeconds, roundDeathCount, shots, hits, headshots,
                plants, defuses, deaths, mvp));

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (inArena(player, arenaId)) {
                player.sendTitle(color(title), color(fact), fadeIn, stay, fadeOut);
            }
        }
    }

    /** 从候选里随机挑一条；没有候选时给个兜底。 */
    private String randomFact(Facts f) {
        List<String> facts = new ArrayList<>();

        if (f.reason == RoundEndReason.CT_ELIMINATED) {
            facts.add("&7本回合 &cT 方 &f" + f.roundSeconds + " &7秒全歼 CT");
        }
        if (f.reason == RoundEndReason.T_ELIMINATED) {
            facts.add("&7本回合 &9CT 方 &f" + f.roundSeconds + " &7秒全歼 T");
        }
        if (f.reason == RoundEndReason.BOMB_EXPLODED) {
            facts.add("&7C4 爆炸，回合用时 &f" + f.roundSeconds + " &7秒");
        }
        if (f.reason == RoundEndReason.BOMB_DEFUSED) {
            facts.add("&7CT 拆包成功，回合用时 &f" + f.roundSeconds + " &7秒");
        }
        if (f.reason == RoundEndReason.TIME_EXPIRED) {
            facts.add("&7时间耗尽，本回合打了 &f" + f.roundSeconds + " &7秒");
        }
        if (f.firstKillSeconds >= 0) {
            facts.add("&7开局 &f" + f.firstKillSeconds + " &7秒出现首杀");
        }
        if (f.roundSeconds > 0) {
            facts.add("&7本回合用时 &f" + f.roundSeconds + " &7秒");
        }
        if (f.matchSeconds > 0) {
            facts.add("&7本场已进行 &f" + clock(f.matchSeconds));
        }
        if (f.roundDeathCount > 0) {
            facts.add("&7本回合 &f" + f.roundDeathCount + " &7人阵亡");
        }
        if (f.shots > 0) {
            facts.add("&7本场发射 &f" + f.shots + " &7发，命中 &f" + f.hits + " &7发");
        }
        if (f.shots > 0) {
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
        if (f.deaths > 0) {
            facts.add("&7本场累计阵亡 &f" + f.deaths + " &7人");
        }
        if (f.mvp != null && f.mvp.getKills() > 0) {
            facts.add("&7MVP &f" + f.mvp.getName() + " &7拿下 &f" + f.mvp.getKills() + " &7杀");
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

    private static final class Facts {
        final RoundEndReason reason;
        final int roundSeconds;
        final int matchSeconds;
        final int firstKillSeconds;
        final int roundDeathCount;
        final int shots;
        final int hits;
        final int headshots;
        final int plants;
        final int defuses;
        final int deaths;
        final PlayerRecord mvp;

        Facts(RoundEndReason reason, int roundSeconds, int matchSeconds, int firstKillSeconds,
              int roundDeathCount, int shots, int hits, int headshots,
              int plants, int defuses, int deaths, PlayerRecord mvp) {
            this.reason = reason;
            this.roundSeconds = roundSeconds;
            this.matchSeconds = matchSeconds;
            this.firstKillSeconds = firstKillSeconds;
            this.roundDeathCount = roundDeathCount;
            this.shots = shots;
            this.hits = hits;
            this.headshots = headshots;
            this.plants = plants;
            this.defuses = defuses;
            this.deaths = deaths;
            this.mvp = mvp;
        }
    }
}
