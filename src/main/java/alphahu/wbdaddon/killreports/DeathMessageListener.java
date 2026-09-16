package alphahu.wbdaddon.killreports;

import alphahu.wbdaddon.WBDAddon;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import com.warz.bombdefuse.api.WbdPlayerEliminatedEvent;

import java.util.Arrays;
import java.util.stream.Collectors;

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
            String weapon = resolveWeaponName(killer);
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
     * 获取击杀者手持物品名称。
     *
     * <p>注意 Paper 的已知问题：切换槽位后立刻攻击，
     * 获取到的可能仍是新槽位的物品。这是 Paper 事件本身的问题，
     * 无法在插件层面完全修复。
     */
    private String resolveWeaponName(Player killer) {
        var item = killer.getInventory().getItemInMainHand();
        if (item.getType().isAir()) {
            return "拳头";
        }

        // TacZ 可用时，优先取枪的真实 GunId 并人性化显示（tacz:p320 → P320）。
        // 显示名只存在于客户端资源包，服务端 bridge 只给 GunId，故按 GunId 转大写。
        if (plugin.isTacZAvailable()) {
            var gunId = plugin.getTacZApi().getItemGunId(item).orElse(null);
            if (gunId != null) {
                return humanizeGunId(gunId);
            }
        }

        // 非 TacZ 物品：优先自定义显示名，其次 lore 首行，最后回退类型名；
        // stripColor 去掉颜色码，避免出现 &a 之类乱码。
        if (item.hasItemMeta()) {
            var meta = item.getItemMeta();
            if (meta.hasDisplayName()) {
                return ChatColor.stripColor(meta.getDisplayName());
            }
            if (meta.hasLore() && !meta.getLore().isEmpty()) {
                return ChatColor.stripColor(meta.getLore().get(0));
            }
        }
        return item.getType().name().toLowerCase().replace("_", " ");
    }

    /** tacz:p320 → P320；tacz:ar_15 → AR 15；tacz:twx_r201 → TWX R201 */
    private String humanizeGunId(String gunId) {
        String id = gunId.contains(":") ? gunId.substring(gunId.indexOf(':') + 1) : gunId;
        return Arrays.stream(id.split("_"))
                .filter(s -> !s.isEmpty())
                .map(s -> s.toUpperCase())
                .collect(Collectors.joining(" "));
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}