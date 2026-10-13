package alphahu.wbdaddon.killreports;

import alphahu.wbdaddon.WBDAddon;
import alphahu.wbdaddon.util.WeaponNames;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import com.warz.bombdefuse.api.WbdPlayerEliminatedEvent;

/**
 * 死亡消息监听器。
 *
 * <p>监听 WarZBombDefuse 的 WbdPlayerEliminatedEvent，
 * 获取击杀者与武器，替换默认的"xxx已被淘汰"消息。
 *
 * <p>如果 TacZSpigotBridge 可用，事件里的击杀者信息会包含枪械使用者。
 *
 * @author AlphaHu
 */
public class DeathMessageListener implements Listener {

    private final WBDAddon plugin;
    private final DamageTracker damageTracker;

    public DeathMessageListener(WBDAddon plugin, DamageTracker damageTracker) {
        this.plugin = plugin;
        this.damageTracker = damageTracker;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerEliminated(WbdPlayerEliminatedEvent event) {
        // 配置：是否替换默认淘汰消息
        boolean replaceDefault = plugin.getConfig()
                .getBoolean("modules.kill-reports.replace-default-death-message", true);

        Player victim = event.getVictim();
        Player killer = event.getKiller();

        String message;
        if (killer != null) {
            // 武器名解析已提到 WeaponNames 工具类，与 WbdAddonApi#resolveWeaponName 共用同一份实现
            String weapon = WeaponNames.resolve(killer, plugin.getTacZApi(), plugin.isTacZAvailable());
            message = plugin.getConfig()
                    .getString("modules.kill-reports.death-message-with-killer",
                            "&c%killer% &7使用 &e%weapon% &7淘汰了 &c%victim%")
                    .replace("%victim%", victim.getName())
                    .replace("%killer%", killer.getName())
                    .replace("%weapon%", weapon);
        } else {
            message = plugin.getConfig()
                    .getString("modules.kill-reports.death-message-no-killer",
                            "&c%victim% &7已被淘汰")
                    .replace("%victim%", victim.getName());
        }

        // 广播替换消息
        Bukkit.broadcastMessage(color(message));

        // 补记致死那一刻打掉的血量：TacZ 致死那一发的伤害在 Forge 层结算，Bukkit 事件收不到；
        // 实测淘汰事件触发时 victim.getHealth() 仍是死亡前的
        // 剩余血量（含血包回血），以其作为致死一发的有效伤害，总览才能累计出实际造成的
        // 总伤害，血包场景下可超过 maxHealth（如 A 打 120 + C 补记 180 = 300）。
        double missing = victim.getHealth();
        if (killer != null && missing > 0.01) {
            damageTracker.recordDamage(killer, victim, missing);
        }
    }

    /**
     * 武器名解析已迁到 {@link WeaponNames}：第三方模式接入后同样需要这段逻辑，
     * 留在监听器的私有方法里就没法复用了（见 WbdAddonApi#resolveWeaponName）。
     *
     * <p>顺带记一笔 Paper 的已知问题：切换槽位后立刻攻击，
     * 读到的可能仍是新槽位的物品。这是事件本身的时序问题，插件层面修不掉。
     */
    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}