package alphahu.wbdaddon.killreports;

import alphahu.wbdaddon.WBDAddon;
import com.warz.bombdefuse.api.WbdRoundEndEvent;
import com.warz.bombdefuse.api.WbdRoundStartEvent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 回合统计监听器。
 *
 * <p>监听 WarZBombDefuse 的回合事件：
 * <ul>
 *   <li>WbdRoundStartEvent：清空上一回合的伤害数据</li>
 *   <li>WbdRoundEndEvent：给每个玩家单独发送本回合自己的伤害统计</li>
 * </ul>
 *
 * @author AlphaHu
 */
public class RoundStatsListener implements Listener {

    private final WBDAddon plugin;
    private final DamageTracker damageTracker;

    public RoundStatsListener(WBDAddon plugin, DamageTracker damageTracker) {
        this.plugin = plugin;
        this.damageTracker = damageTracker;
    }

    @EventHandler
    public void onRoundStart(WbdRoundStartEvent event) {
        damageTracker.reset();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRoundEnd(WbdRoundEndEvent event) {
        boolean showSummary = plugin.getConfig()
                .getBoolean("modules.kill-reports.show-round-summary", true);
        if (!showSummary || damageTracker.isEmpty()) {
            return;
        }

        sendDamageSummary();
    }

    /**
     * 每个造成过伤害的玩家，单独收到一份"你的伤害统计"。
     * 数据源是 victim -> (attacker -> damage)，先反向聚合成 attacker -> (victim -> damage)。
     */
    private void sendDamageSummary() {
        String title = plugin.getConfig()
                .getString("modules.kill-reports.round-summary-title",
                        "&6====== 你的伤害统计 ======");
        String footer = plugin.getConfig()
                .getString("modules.kill-reports.round-summary-footer",
                        "&6========================");
        String lineFormat = plugin.getConfig()
                .getString("modules.kill-reports.damage-line-format",
                        "你对 &c%victim% &7造成了 &e%damage% &7伤害");
        int decimals = plugin.getConfig()
                .getInt("modules.kill-reports.damage-decimal-places", 1);

        // 反向聚合：attacker UUID -> (victim UUID -> 累计伤害)
        Map<UUID, Map<UUID, Double>> attackerDamage = new LinkedHashMap<>();
        for (Map.Entry<UUID, Map<UUID, Double>> victimEntry : damageTracker.getAllDamage().entrySet()) {
            UUID victimUuid = victimEntry.getKey();
            for (Map.Entry<UUID, Double> source : victimEntry.getValue().entrySet()) {
                attackerDamage
                        .computeIfAbsent(source.getKey(), k -> new HashMap<>())
                        .merge(victimUuid, source.getValue(), Double::sum);
            }
        }

        for (Map.Entry<UUID, Map<UUID, Double>> attackerEntry : attackerDamage.entrySet()) {
            Player attacker = Bukkit.getPlayer(attackerEntry.getKey());
            if (attacker == null || !attacker.isOnline()) {
                continue;
            }

            // 该攻击者打过的目标，按伤害降序
            List<Map.Entry<UUID, Double>> targets = new ArrayList<>(attackerEntry.getValue().entrySet());
            targets.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

            attacker.sendMessage(color(title));
            for (Map.Entry<UUID, Double> target : targets) {
                Player victimPlayer = Bukkit.getPlayer(target.getKey());
                String victimName = (victimPlayer != null) ? victimPlayer.getName() : "未知玩家";

                String line = lineFormat
                        .replace("%victim%", victimName)
                        .replace("%damage%", String.format("%." + decimals + "f", target.getValue()));
                attacker.sendMessage(color(line));
            }
            attacker.sendMessage(color(footer));
        }
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}