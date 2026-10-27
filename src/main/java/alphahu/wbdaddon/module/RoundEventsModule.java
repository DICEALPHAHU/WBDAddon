package alphahu.wbdaddon.module;

import alphahu.wbdaddon.WBDAddon;
import alphahu.wbdaddon.api.AddonMode;
import alphahu.wbdaddon.mode.BombDefuseMode;
import com.warz.bombdefuse.api.WbdRoundStartEvent;
import com.warz.bombdefuse.arena.ArenaSession;
import com.warz.bombdefuse.model.Boundary;
import com.warz.bombdefuse.model.Team;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

/**
 * 回合事件模块：回合结束清场 + 回合开始的赛事提示。
 *
 * <h2>清场</h2>
 *
 * <p>在<b>下一回合开始时</b>，把竞技场边界内的所有掉落物移除。
 * 回合结束到下一回合开始之间有一段结算冷却，这期间掉落物要保留，
 * 让玩家还能看到上一局掉了什么；等新回合真正开始再清。
 *
 * <p>只清 {@code ArenaConfig.getBoundary()} 范围内的物品，不会动竞技场外的东西。
 *
 * <h2>上半场最终局</h2>
 *
 * <p>WBD 的 {@code settings.side-switch-round} 表示「第 N 回合后换边」，
 * 所以第 N 回合就是上半场的最后一回合。到这一回合开始时打一个醒目的大标题。
 *
 * <h2>赛点</h2>
 *
 * <p>任意一方胜场达到 {@code settings.rounds-to-win - 1} 时，之后的每一回合
 * 开局都提示「赛点」，直到有人真正拿下比赛。两边同时到赛点时同样提示。
 *
 * @author AlphaHu
 */
public class RoundEventsModule implements AddonModule, Listener {

    private final WBDAddon plugin;

    public RoundEventsModule(WBDAddon plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getId() {
        return "round-events";
    }

    @Override
    public String getDisplayName() {
        return "回合事件";
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("回合事件已启动：新回合开始清场、上半场最终局、赛点提示。");
    }

    @Override
    public void onDisable() {
        HandlerList.unregisterAll(this);
        plugin.getLogger().info("回合事件已卸载。");
    }

    // ------------------------------------------------------------------
    // 掉落物清理
    // ------------------------------------------------------------------

    /** 清掉竞技场边界内的所有掉落物。由下一回合开始时调用。 */
    private void clearDrops(ArenaSession session) {
        Boundary boundary = session.getConfig().getBoundary();
        if (boundary == null) return;

        World world = Bukkit.getWorld(boundary.getWorld());
        if (world == null) return;

        int removed = 0;
        for (Item item : world.getEntitiesByClass(Item.class)) {
            if (boundary.contains(item.getLocation())) {
                item.remove();
                removed++;
            }
        }

        if (removed > 0 && plugin.getConfig().getBoolean("modules.round-events.debug", false)) {
            plugin.getLogger().info("[round-events] 新回合开始，清理上一回合掉落物 " + removed + " 个。");
        }
    }

    // ------------------------------------------------------------------
    // 回合开始：清掉上一回合的掉落物 + 上半场最终局 / 赛点
    // ------------------------------------------------------------------

    @EventHandler
    public void onRoundStart(WbdRoundStartEvent event) {
        ArenaSession session = session(event.getArenaId());
        if (session == null) return;

        // 上一回合的掉落物要保留整个「回合间冷却」，直到下一回合真正开始才清，
        // 这样结算期间玩家还能看到/捡起上一局掉的东西（与 CS 一致）。
        // 之前挂在 WbdRoundEndEvent 上，一结算就清空，太急了。
        if (plugin.getConfig().getBoolean(
                "modules.round-events.clear-drops-on-round-start", true)) {
            clearDrops(session);
        }

        boolean halfFinal = plugin.getConfig().getBoolean(
                "modules.round-events.half-final-enabled", true)
                && isHalfFinalRound(event.getRound());

        boolean matchPoint = plugin.getConfig().getBoolean(
                "modules.round-events.match-point-enabled", true)
                && isMatchPoint(session);

        if (halfFinal) {
            Runnable show = () -> sendTitle(event.getArenaId(),
                    cfg("half-final-title", "&6&l上半场最终局"),
                    cfg("half-final-subtitle", ""));
            show.run();
            // 再补发一次：防止被上一回合/其它模块的标题盖掉
            Bukkit.getScheduler().runTaskLater(plugin, show, 20L);
        }

        if (plugin.getConfig().getBoolean("modules.round-events.debug", false)) {
            plugin.getLogger().info("[round-events] round=" + event.getRound()
                    + " halfFinal=" + halfFinal + " matchPoint=" + matchPoint);
        }

        if (matchPoint) {
            Runnable show = () -> sendTitle(event.getArenaId(),
                    cfg("match-point-title", "&c&l赛点"),
                    cfg("match-point-subtitle", ""));
            // 同一回合既是上半场最后一局又是赛点时，两句话错开显示，
            // 否则后一个 sendTitle 会直接覆盖前一个。
            if (halfFinal) {
                Bukkit.getScheduler().runTaskLater(plugin, show, 45L);
            } else {
                show.run();
            }
        }
    }

    /** 第 N 回合后换边 → 第 N 回合就是上半场最后一回合。 */
    private boolean isHalfFinalRound(int round) {
        if (plugin.getWbd() == null) return false;
        int switchRound = plugin.getWbd().getConfig()
                .getInt("settings.side-switch-round", 6);
        return switchRound > 0 && round == switchRound;
    }

    /** 任意一方达到「胜利回合数 - 1」，且还没有人真正赢下比赛。 */
    private boolean isMatchPoint(ArenaSession session) {
        if (plugin.getWbd() == null) return false;
        int roundsToWin = plugin.getWbd().getConfig()
                .getInt("settings.rounds-to-win", 8);
        if (roundsToWin <= 1) return false;

        int t = session.getScore(Team.T);
        int ct = session.getScore(Team.CT);
        if (t >= roundsToWin || ct >= roundsToWin) return false;
        return t == roundsToWin - 1 || ct == roundsToWin - 1;
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private void sendTitle(String arenaId, String title, String subtitle) {
        String main = color(title);
        String sub = color(subtitle);
        if (main.isEmpty() && sub.isEmpty()) return;

        int fadeIn = plugin.getConfig().getInt("modules.round-events.title-fade-in", 10);
        int stay = plugin.getConfig().getInt("modules.round-events.title-stay", 50);
        int fadeOut = plugin.getConfig().getInt("modules.round-events.title-fade-out", 20);

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!inArena(player, arenaId)) continue;
            player.resetTitle();
            player.sendTitle(main, sub, fadeIn, stay, fadeOut);
        }
    }

    private boolean inArena(Player player, String arenaId) {
        if (plugin.getApi() == null) return false;
        AddonMode mode = plugin.getApi().getModeFor(player);
        if (mode == null || !BombDefuseMode.MODE_ID.equals(mode.getModeId())) return false;
        return arenaId.equals(mode.getArenaId(player));
    }

    private ArenaSession session(String arenaId) {
        if (plugin.getWbd() == null || plugin.getWbd().getArenaManager() == null) {
            return null;
        }
        return plugin.getWbd().getArenaManager().getSession(arenaId);
    }

    private String cfg(String key, String fallback) {
        String value = plugin.getConfig()
                .getString("modules.round-events." + key, fallback);
        return value == null ? "" : value;
    }

    private String color(String raw) {
        return raw == null ? "" : ChatColor.translateAlternateColorCodes('&', raw);
    }
}
