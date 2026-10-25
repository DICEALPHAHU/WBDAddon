package alphahu.wbdaddon.module;

import alphahu.wbdaddon.WBDAddon;
import alphahu.wbdaddon.api.AddonMode;
import com.tacz.bridge.spigot.api.event.TacZEntityHurtPreBridgeEvent;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * TacZ 枪伤的阵亡掉落桥接。
 *
 * <h2>为什么需要单独一个监听器</h2>
 *
 * <p>WarZBombDefuse 的枪械击杀<b>不经过 Bukkit 的 {@code EntityDamageEvent}</b>：
 * TacZ 在 Forge 层结算伤害，桥接插件把它转成
 * {@link TacZEntityHurtPreBridgeEvent}，而 WBD 的
 * {@code TacZBridgeListener.handleHurtPre()} 在这个事件里直接调用
 * {@code ArenaSession.eliminate()}。
 *
 * <p>因此只监听 Bukkit 伤害事件的 {@link ArenaDropsModule} 对枪杀永远不会触发，
 * 枪不掉、炸弹也不掉。这里补上同一条路径。
 *
 * <h2>为什么是 NORMAL</h2>
 *
 * <p>WBD 的动态监听注册在 {@code HIGHEST}。我们必须早于它：
 * 先把 C4 从死者身上取下并清掉 {@code currentBombCarrier}，
 * WBD 随后的 {@code eliminate()} 才不会把炸弹重新分配给另一个 T。
 *
 * <h2>为什么还要判友伤</h2>
 *
 * <p>WBD 在 {@code HIGHEST} 会取消队友伤害。我们更早执行，所以不能只看
 * "这一枪数值上会致死"：如果挨枪的是队友，WBD 最终会取消，炸弹不该掉。
 *
 * @author AlphaHu
 */
public final class TacZDropsListener implements Listener {

    private final WBDAddon plugin;
    private final ArenaDropsModule module;

    public TacZDropsListener(WBDAddon plugin, ArenaDropsModule module) {
        this.plugin = plugin;
        this.module = module;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onTacZHurtPre(TacZEntityHurtPreBridgeEvent event) {
        Entity hurt = event.getHurtEntity();
        if (!(hurt instanceof Player victim)) return;
        if (plugin.getApi() == null) return;

        AddonMode mode = plugin.getApi().getModeFor(victim);
        if (mode == null || !mode.isPlaying(victim) || !mode.isAlive(victim)) return;

        // 友伤：WBD 会在 HIGHEST 取消，不能因为数值致死就把包丢了
        if (event.getAttacker() instanceof Player killer
                && mode.isSameTeam(victim, killer)) {
            return;
        }

        // 与 WBD 的致死判定保持一致：base ×（爆头时 max(倍率, 1)）
        float damage = event.getBaseAmount();
        if (event.isHeadShot()) {
            damage *= Math.max(event.getHeadshotMultiplier(), 1.0f);
        }
        if (victim.getHealth() - damage > 0) return;

        module.handleLethal(victim, victim.getLocation().clone());
    }
}
