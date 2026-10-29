package alphahu.wbdaddon.module;

import alphahu.wbdaddon.WBDAddon;
import alphahu.wbdaddon.economy.KillRewards;
import com.warz.bombdefuse.api.WbdPlayerEliminatedEvent;
import com.warz.bombdefuse.arena.ArenaSession;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

/**
 * 按武器区分的击杀奖励（CS 式）。
 *
 * <p>WBD 在 {@code eliminate()} 里已经按 {@code kill-money} 发过一份，
 * 这里只补**差额**：目标奖励减去 WBD 已发的那份。这样：
 *
 * <ul>
 *   <li>不碰 WBD 的结算流程，也不会重复发钱；</li>
 *   <li>WBD 以后改 {@code kill-money}，这里自动跟着变，不用同步两份数字；</li>
 *   <li>把钱写回同一个 {@code PlayerRecord}，受金钱上限约束。</li>
 * </ul>
 *
 * <p>规则见 {@link KillRewards}，配置文件是 {@code kill-rewards.yml}。
 *
 * @author AlphaHu
 */
public class KillRewardModule implements AddonModule, Listener {

    private static final String ID = "kill-reward";

    private final WBDAddon plugin;

    private KillRewards rewards;

    public KillRewardModule(WBDAddon plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "击杀奖励";
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }

    @Override
    public void onEnable() {
        this.rewards = new KillRewards(plugin);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("击杀奖励已启动：按 kill-rewards.yml 给不同武器发不同赏金。");
    }

    @Override
    public void onDisable() {
        HandlerList.unregisterAll(this);
        plugin.getLogger().info("击杀奖励已卸载。");
    }

    public KillRewards getRewards() {
        return rewards;
    }

    /**
     * 给击杀播报用：这次击杀会发多少（不产生副作用）。
     *
     * @return 金额；{@code -1} 表示不接管
     */
    public int previewReward(Player killer, String indirectWeapon) {
        return rewards == null ? -1 : rewards.amount(killer, indirectWeapon);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEliminated(WbdPlayerEliminatedEvent event) {
        if (rewards == null || !rewards.enabled()) return;

        Player killer = event.getKiller();
        Player victim = event.getVictim();
        if (killer == null || victim == null || killer.equals(victim)) return;

        // 间接击杀（手雷 / 燃烧瓶）用的是投掷物类别
        String indirectWeapon = null;
        AddonModule reports = plugin.getModuleManager().getModule("kill-reports");
        if (reports instanceof KillReportsModule module
                && module.getIndirectListener() != null) {
            indirectWeapon = module.getIndirectListener().peekWeapon(victim);
        }

        int target = rewards.amount(killer, indirectWeapon);
        if (target < 0) return;

        ArenaSession session = sessionOf(killer);
        if (session == null) return;

        int paidByWbd = baseKillReward();
        int delta = target - paidByWbd;
        if (delta == 0) return;

        try {
            session.addMoney(killer, delta);
            if (plugin.getConfig().getBoolean("modules." + ID + ".debug", false)) {
                plugin.getLogger().info("[kill-reward] " + killer.getName() + " 击杀 "
                        + victim.getName() + " → 目标 $" + target + "，WBD 已发 $" + paidByWbd
                        + "，补差额 $" + delta);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("击杀奖励补差额失败：" + t);
        }
    }

    /** WBD 自己的统一击杀奖励，用来算差额。 */
    private int baseKillReward() {
        try {
            var settings = plugin.getWbd().getConfigManager().getSettings();
            return settings == null ? 0 : settings.killMoney;
        } catch (Throwable t) {
            return 0;
        }
    }

    private ArenaSession sessionOf(Player player) {
        try {
            return plugin.getWbd().getArenaManager().getSession(player);
        } catch (Throwable t) {
            return null;
        }
    }
}
