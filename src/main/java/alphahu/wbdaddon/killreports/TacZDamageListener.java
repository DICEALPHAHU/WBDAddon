package alphahu.wbdaddon.killreports;

import alphahu.wbdaddon.WBDAddon;
import com.warz.bombdefuse.arena.ArenaSession;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * TacZ 伤害监听器。
 *
 * <p>当 TacZSpigotBridge 不可用时，回退到原版 EntityDamageByEntityEvent
 * 来记录伤害。当 TacZ 可用时，本监听器不注册，由桥接事件负责记录。
 *
 * <p>只记录同一竞技场内、攻击者存活时的伤害；受害者不要求存活，
 * 以便把致命一击的伤害也计入（致死时 WBD 可能已先把受害者标记为死亡）。
 *
 * @author AlphaHu
 */
public class TacZDamageListener implements Listener {

    private final WBDAddon plugin;
    private final DamageTracker damageTracker;

    public TacZDamageListener(WBDAddon plugin, DamageTracker damageTracker) {
        this.plugin = plugin;
        this.damageTracker = damageTracker;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;

        // 解析攻击者：直接攻击或投射物
        Player attacker = null;
        if (event.getDamager() instanceof Player p) {
            attacker = p;
        } else if (event.getDamager() instanceof org.bukkit.entity.Projectile proj
                && proj.getShooter() instanceof Player p) {
            attacker = p;
        }

        if (attacker == null || attacker.equals(victim)) return;

        // 只记录双方都在同一竞技场且都存活时的伤害
        if (!isInSameArena(attacker, victim)) return;

        damageTracker.recordDamage(attacker, victim, event.getDamage());
    }

    /**
     * 判断攻击者和受害者是否在同一竞技场、且双方都存活。
     * 通过 WarZBombDefuse 的 ArenaSession 判断。
     */
    private boolean isInSameArena(Player attacker, Player victim) {
        var arenaManager = plugin.getWbd().getArenaManager();

        ArenaSession attackerSession = arenaManager.getSession(attacker);
        ArenaSession victimSession = arenaManager.getSession(victim);

        // 双方必须在同一个竞技场
        if (attackerSession == null || attackerSession != victimSession) {
            return false;
        }

        // 只要求攻击者存活；受害者不检查 alive——
        // 因为致死那一击触发时，WBD 可能已先把受害者标记为死亡，
        // 若还要求 victim alive，就会漏掉致命一击的伤害。
        return attackerSession.getRecord(attacker).isAlive();
    }
}