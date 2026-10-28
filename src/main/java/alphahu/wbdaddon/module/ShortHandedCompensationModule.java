package alphahu.wbdaddon.module;

import alphahu.wbdaddon.WBDAddon;
import com.warz.bombdefuse.api.WbdRoundStartEvent;
import com.warz.bombdefuse.arena.ArenaSession;
import com.warz.bombdefuse.arena.PlayerRecord;
import com.warz.bombdefuse.model.Team;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.List;

/**
 * 少人经济补偿。
 *
 * <p>从配置的起始回合（默认第 3 回合）开始，每回合开始时比较双方实际参赛人数；
 * 人数较少的一方每人获得一笔经济补偿。人数相同、或有一方为空时不发。
 *
 * <p>只在回合开始时结算一次，不按存活人数反复计算：这样补偿与本回合的
 * 枪法、伤亡无关，只和开局人数差有关，也不会因为回合中途掉人而重复发钱。
 *
 * <p>直接调用 WBD 的 {@link ArenaSession#addMoney(Player, int)}，
 * 因此仍然受 WBD 的金钱上限约束，并会正常触发 WbdMoneyChangeEvent。
 */
public class ShortHandedCompensationModule implements AddonModule, Listener {

    private static final String ID = "short-handed-compensation";

    private final WBDAddon plugin;

    public ShortHandedCompensationModule(WBDAddon plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "少人经济补偿";
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("少人经济补偿已启动：从第 "
                + startRound() + " 回合起，少人方每人每回合 +$" + amount() + "。");
    }

    @Override
    public void onDisable() {
        HandlerList.unregisterAll(this);
        plugin.getLogger().info("少人经济补偿已卸载。");
    }

    @EventHandler
    public void onRoundStart(WbdRoundStartEvent event) {
        if (event.getRound() < startRound()) {
            return;
        }

        int amount = amount();
        if (amount <= 0) {
            return;
        }

        ArenaSession session = session(event.getArenaId());
        if (session == null) {
            return;
        }

        List<Player> tPlayers = players(session, Team.T);
        List<Player> ctPlayers = players(session, Team.CT);

        // 两边人数一样，或有一边根本没人（异常局），都不补。
        if (tPlayers.isEmpty() || ctPlayers.isEmpty() || tPlayers.size() == ctPlayers.size()) {
            return;
        }

        Team shortTeam = tPlayers.size() < ctPlayers.size() ? Team.T : Team.CT;
        List<Player> shortPlayers = shortTeam == Team.T ? tPlayers : ctPlayers;
        int diff = Math.abs(tPlayers.size() - ctPlayers.size());

        int paidPlayers = 0;
        for (Player player : shortPlayers) {
            PlayerRecord record = session.getRecord(player);
            if (record == null) {
                continue;
            }

            int before = record.getMoney();
            session.addMoney(player, amount);
            int gained = record.getMoney() - before;
            if (gained <= 0) {
                // 已经到金钱上限，不再刷一条「+0」提示。
                continue;
            }

            paidPlayers++;
            if (announce()) {
                player.sendMessage(color(message()
                        .replace("%amount%", String.valueOf(gained))
                        .replace("%team%", shortTeam.name())
                        .replace("%diff%", String.valueOf(diff))));
            }
        }

        if (plugin.getConfig().getBoolean("modules." + ID + ".debug", false)) {
            plugin.getLogger().info("[short-handed] round=" + event.getRound()
                    + " T=" + tPlayers.size() + " CT=" + ctPlayers.size()
                    + " short=" + shortTeam + " paid=" + paidPlayers
                    + " amount=" + amount);
        }
    }

    /**
     * 统计本回合实际会参赛的在线玩家。
     *
     * <p>不按 {@code isQueuedForNextRound} 过滤：WBD 在每回合准备时会把
     * 参赛者的该标记设为 true，它表示「已排进本回合」，而不是「在下面等」。
     * 真正要排除的是不在竞技场里的玩家和纯观战者，前者用 contains 判断，
     * 后者没有 T/CT 队伍。中途加入、已排队下一回合的玩家在本回合开始时
     * 会被 WBD 一并拉进场，因此也应该计入人数。
     */
    private List<Player> players(ArenaSession session, Team team) {
        List<Player> result = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!session.contains(player)) {
                continue;
            }
            if (session.getTeam(player) != team) {
                continue;
            }
            result.add(player);
        }
        return result;
    }

    private ArenaSession session(String arenaId) {
        if (plugin.getWbd() == null || plugin.getWbd().getArenaManager() == null) {
            return null;
        }
        return plugin.getWbd().getArenaManager().getSession(arenaId);
    }

    private int startRound() {
        return Math.max(1, plugin.getConfig().getInt("modules." + ID + ".start-round", 3));
    }

    private int amount() {
        return plugin.getConfig().getInt("modules." + ID + ".amount", 1000);
    }

    private boolean announce() {
        return plugin.getConfig().getBoolean("modules." + ID + ".announce", true);
    }

    private String message() {
        String raw = plugin.getConfig().getString("modules." + ID + ".message",
                "&e本回合人数较少，获得 &6+$%amount% &e经济补偿。");
        return raw == null ? "" : raw;
    }

    private String color(String raw) {
        return ChatColor.translateAlternateColorCodes('&', raw);
    }
}
