package alphahu.wbdaddon.killreports;

import alphahu.wbdaddon.WBDAddon;
import alphahu.wbdaddon.api.AddonMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * 伤害记录监听器。
 *
 * <p>用原版 EntityDamageByEntityEvent 记录「谁对谁打了多少」，
 * 供 TacZ 环境下的击杀归属校正与伤害总览使用。
 * （桥接事件 TacZEntityHurtBridgeEvent 在 Arclight 上不派发，实测不可用，故不采用。）
 *
 * <p>只记录同一模式、同一场地内、攻击者存活时的伤害；受害者不要求存活，
 * 以便把致命一击的伤害也计入。
 *
 * <p>判定走 {@link AddonMode} 而非 WBD 的 ArenaSession，
 * 因此团队死斗这类第三方模式的伤害同样会被记录。
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

        if (!isRecordable(attacker, victim)) return;

        damageTracker.recordDamage(attacker, victim, event.getDamage());
    }

    /**
     * 判断这次伤害是否值得记录。
     *
     * <p>要求双方属于同一个已注册模式、且处在同一场地，攻击者还活着。
     *
     * <p>只要求攻击者存活、不检查受害者：致死那一击触发时，
     * 模式可能已经先把受害者标记为死亡，若还要求 victim 存活就会漏掉致命一击的伤害。
     */
    private boolean isRecordable(Player attacker, Player victim) {
        // 插件卸载流程里服务可能已被撤下，而本监听器还没注销完；
        // 这时若直接解引用就会把一个 NPE 抛进事件总线
        if (plugin.getApi() == null) {
            return false;
        }

        AddonMode mode = plugin.getApi().getModeFor(attacker);
        if (mode == null || mode != plugin.getApi().getModeFor(victim)) {
            return false;
        }

        // 同一模式内还要限定同一场地：不同竞技场之间隔着世界或距离，
        // 本该打不到彼此，但判定松一点就会把无关数据记进同一份账本
        String arena = mode.getArenaId(attacker);
        if (arena == null || !arena.equals(mode.getArenaId(victim))) {
            return false;
        }

        return mode.isAlive(attacker);
    }
}