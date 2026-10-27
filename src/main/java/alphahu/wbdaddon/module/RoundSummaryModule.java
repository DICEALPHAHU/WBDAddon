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

    void countHit(Player player, boolean headshot, float damage) {
        ArenaState state = stateFor(player);
        if (state == null) return;
        Counter c = state.counter(player.getUniqueId());
        c.hits++;
        if (headshot) c.headshots++;
        int amount = Math.max(0, Math.round(damage));
        state.totalDamage += amount;
        state.damage.merge(player.getUniqueId(), amount, Integer::sum);
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
        state.resetForRound(System.currentTimeMillis(), event.getRound());
    }

    @EventHandler
    public void onEliminated(WbdPlayerEliminatedEvent event) {
        ArenaState state = arenaStates.computeIfAbsent(
                event.getArenaId(), k -> new ArenaState());
        state.roundDeathsTotal++;
        state.lastKillAt = System.currentTimeMillis();

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
        if (state == null) return;
        state.roundPlants++;
        state.planter = event.getPlayer() == null ? null : event.getPlayer().getName();
        state.planterId = event.getPlayer() == null ? null : event.getPlayer().getUniqueId();
        state.plantSite = event.getSiteId();
        state.plantAt = System.currentTimeMillis();
    }

    @EventHandler
    public void onDefuse(WbdBombDefuseCompleteEvent event) {
        ArenaState state = arenaStates.get(event.getArenaId());
        if (state == null) return;
        state.roundDefuses++;
        state.defuser = event.getPlayer() == null ? null : event.getPlayer().getName();
        state.defuserId = event.getPlayer() == null ? null : event.getPlayer().getUniqueId();
        state.defuseAt = System.currentTimeMillis();
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

        // 本回合 MVP（CS 式：击杀×2 + 伤害/100 + 下包/拆包各 +3，平手比死亡少）
        PlayerRecord mvp = roundMvp(session, state, event.getWinner());

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

    /** 本回合 MVP：只在胜方里选，没人的话退回全体。 */
    private PlayerRecord roundMvp(ArenaSession session, ArenaState state, Team winner) {
        PlayerRecord best = null;
        double bestScore = -1;
        int bestDeaths = Integer.MAX_VALUE;

        for (PlayerRecord record : session.getPlayers()) {
            if (winner != null && record.getTeam() != winner) continue;
            double score = roundScore(state, record);
            int deaths = state.roundDeaths.getOrDefault(record.getUuid(), 0);
            if (best == null || score > bestScore
                    || (score == bestScore && deaths < bestDeaths)) {
                best = record;
                bestScore = score;
                bestDeaths = deaths;
            }
        }

        if (best != null && bestScore > 0) return best;

        // 胜方没有有效数据时，退回全体里分数最高的
        best = null;
        bestScore = -1;
        bestDeaths = Integer.MAX_VALUE;
        for (PlayerRecord record : session.getPlayers()) {
            double score = roundScore(state, record);
            int deaths = state.roundDeaths.getOrDefault(record.getUuid(), 0);
            if (best == null || score > bestScore
                    || (score == bestScore && deaths < bestDeaths)) {
                best = record;
                bestScore = score;
                bestDeaths = deaths;
            }
        }
        return best;
    }

    private double roundScore(ArenaState state, PlayerRecord record) {
        int kills = state.roundKills.getOrDefault(record.getUuid(), 0);
        int damage = state.damage.getOrDefault(record.getUuid(), 0);
        double score = kills * 2.0 + damage / 100.0;
        if (record.getUuid().equals(state.planterId)) score += 3.0;
        if (record.getUuid().equals(state.defuserId)) score += 3.0;
        return score;
    }

    /** 只取本回合的数据，按 CS「趣味数据」的写法：选手 + 单回合 + 具体数字。 */
    private String randomFact(WbdRoundEndEvent event, ArenaState state,
                              ArenaSession session, int roundSeconds, int firstKillSeconds) {
        List<String> facts = new ArrayList<>();
        List<PlayerRecord> players = new ArrayList<>(session.getPlayers());

        PlayerRecord topKiller = null;
        int topKills = 0;
        PlayerRecord topDeaths = null;
        int worstDeaths = 0;
        PlayerRecord topDamage = null;
        int maxDamage = 0;
        PlayerRecord quietest = null;
        for (PlayerRecord record : players) {
            int kills = state.roundKills.getOrDefault(record.getUuid(), 0);
            int deaths = state.roundDeaths.getOrDefault(record.getUuid(), 0);
            int damage = state.damage.getOrDefault(record.getUuid(), 0);
            if (kills > topKills) {
                topKills = kills;
                topKiller = record;
            }
            if (deaths > worstDeaths) {
                worstDeaths = deaths;
                topDeaths = record;
            }
            if (damage > maxDamage) {
                maxDamage = damage;
                topDamage = record;
            }
            if (kills == 0 && deaths == 0 && damage == 0 && quietest == null) {
                quietest = record;
            }
        }

        int shots = 0;
        int hits = 0;
        PlayerRecord bestAccuracy = null;
        double bestAccuracyValue = -1;
        PlayerRecord topHeadshots = null;
        int topHeadshotCount = 0;
        PlayerRecord mostShots = null;
        int maxShots = 0;
        PlayerRecord mostMiss = null;
        int maxMiss = 0;
        for (Map.Entry<UUID, Counter> entry : state.counters.entrySet()) {
            Counter c = entry.getValue();
            shots += c.shots;
            hits += c.hits;
            PlayerRecord record = session.getRecord(entry.getKey());
            if (record == null) continue;

            if (c.shots > maxShots) {
                maxShots = c.shots;
                mostShots = record;
            }
            int miss = c.shots - c.hits;
            if (miss > maxMiss) {
                maxMiss = miss;
                mostMiss = record;
            }
            if (c.shots >= 10) {
                double acc = c.hits / (double) c.shots;
                if (acc > bestAccuracyValue) {
                    bestAccuracyValue = acc;
                    bestAccuracy = record;
                }
            }
            if (c.headshots > topHeadshotCount) {
                topHeadshotCount = c.headshots;
                topHeadshots = record;
            }
        }

        // 结束方式
        switch (event.getReason()) {
            case CT_ELIMINATED -> facts.add("T 方单回合 " + roundSeconds + " 秒全歼 CT");
            case T_ELIMINATED -> facts.add("CT 方单回合 " + roundSeconds + " 秒全歼 T");
            case BOMB_EXPLODED -> facts.add("本回合 C4 爆炸");
            case BOMB_DEFUSED -> facts.add("本回合 CT 拆包成功");
            case TIME_EXPIRED -> facts.add("本回合时间耗尽");
            default -> {
            }
        }

        // 选手 + 单回合 + 数字
        if (topKills > 0) {
            facts.add(topKiller.getName() + " 单回合 " + topKills + " 杀");
        }
        if (maxDamage > 0) {
            facts.add(topDamage.getName() + " 单回合打出 " + maxDamage + " 伤害");
        }
        if (topHeadshotCount > 0) {
            facts.add(topHeadshots.getName() + " 单回合 " + topHeadshotCount + " 个爆头");
        }
        if (bestAccuracy != null) {
            facts.add(bestAccuracy.getName() + " 单回合命中率 "
                    + Math.round(bestAccuracyValue * 100) + "%");
        }
        if (mostMiss != null && maxMiss > 0) {
            facts.add(mostMiss.getName() + " 单回合 " + maxShots
                    + " 发子弹只中 " + Math.max(0, maxShots - maxMiss) + " 发");
        } else if (mostShots != null && maxShots > 0) {
            facts.add(mostShots.getName() + " 单回合 " + maxShots + " 发子弹");
        }
        if (state.firstVictim != null && firstKillSeconds >= 0) {
            facts.add(state.firstVictim + " 单回合 " + firstKillSeconds + " 秒就被击杀");
        }
        if (worstDeaths > 1) {
            facts.add(topDeaths.getName() + " 单回合 " + worstDeaths + " 次死亡");
        }
        if (quietest != null) {
            facts.add(quietest.getName() + " 单回合 0 杀 0 死");
        }
        if (state.planter != null && state.plantAt > 0) {
            int sec = (int) ((state.plantAt - state.roundStart) / 1000);
            facts.add(state.planter + " 单回合下包，用时 " + sec + " 秒");
        }
        if (state.defuser != null && state.defuseAt > 0) {
            int sec = (int) ((state.defuseAt - state.roundStart) / 1000);
            facts.add(state.defuser + " 单回合拆包，用时 " + sec + " 秒");
        }

        // 独活
        String lone = null;
        if (event.getWinner() != null && session.aliveCount(event.getWinner()) == 1) {
            for (PlayerRecord record : players) {
                if (record.isAlive() && record.getTeam() == event.getWinner()) {
                    lone = record.getName();
                    break;
                }
            }
        }
        if (lone != null) {
            facts.add(lone + " 单回合独活");
        }

        // 回合整体
        if (!players.isEmpty() && state.roundDeathsTotal >= players.size()) {
            facts.add("本回合 " + state.roundDeathsTotal + " 人全灭");
        } else if (state.roundDeathsTotal <= 2) {
            facts.add("本回合只死了 " + state.roundDeathsTotal + " 人");
        }
        if (state.roundDeathsTotal > 0) {
            facts.add("本回合 " + state.roundDeathsTotal + " 人阵亡");
        }
        if (state.lastKillAt > 0 && state.roundStart > 0) {
            int sec = (int) ((state.lastKillAt - state.roundStart) / 1000);
            facts.add("本回合最后一杀在 " + sec + " 秒");
        }
        if (state.totalDamage > 0) {
            facts.add("本回合双方共打出 " + state.totalDamage + " 伤害");
        }
        if (shots > 0) {
            facts.add("本回合共射出 " + shots + " 发子弹");
        }
        if (roundSeconds > 0) {
            facts.add("本回合用时 " + roundSeconds + " 秒");
        }
        if (isPistolRound(state.roundNumber) && topKills > 0) {
            facts.add("手枪局，" + topKiller.getName() + " " + topKills + " 杀");
        }

        if (facts.isEmpty()) {
            return "本回合结束";
        }
        return facts.get(ThreadLocalRandom.current().nextInt(facts.size()));
    }

    private boolean isPistolRound(int round) {
        if (round == 1) return true;
        int sideSwitch = plugin.getWbd() == null ? 0
                : plugin.getWbd().getConfig().getInt("settings.side-switch-round", 0);
        return sideSwitch > 0 && round == sideSwitch + 1;
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
        int roundNumber;
        long firstKillAt = -1;
        String firstKiller;
        Team firstKillerTeam;
        String firstVictim;
        long lastKillAt;
        String planter;
        UUID planterId;
        String plantSite;
        long plantAt;
        String defuser;
        UUID defuserId;
        long defuseAt;
        int totalDamage;
        final Map<UUID, Integer> damage = new ConcurrentHashMap<>();
        int roundDeathsTotal;
        int roundPlants;
        int roundDefuses;
        final Map<UUID, Counter> counters = new ConcurrentHashMap<>();
        final Map<UUID, Integer> roundKills = new ConcurrentHashMap<>();
        final Map<UUID, Integer> roundDeaths = new ConcurrentHashMap<>();

        void resetForRound(long now, int round) {
            roundStart = now;
            roundNumber = round;
            firstKillAt = -1;
            firstKiller = null;
            firstKillerTeam = null;
            firstVictim = null;
            lastKillAt = 0;
            planter = null;
            planterId = null;
            plantSite = null;
            plantAt = 0;
            defuser = null;
            defuserId = null;
            defuseAt = 0;
            totalDamage = 0;
            damage.clear();
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
