package alphahu.wbdaddon.module;

import com.tacz.bridge.spigot.api.event.TacZEntityHurtPreBridgeEvent;
import com.tacz.bridge.spigot.api.event.TacZGunFireBridgeEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * TacZ 射击/命中统计桥接。
 *
 * <p>单独一个类，只在 TacZ 桥接存在时才会被实例化和注册，
 * 避免桥接缺席时加载 {@code TacZ*} 事件类触发 {@code NoClassDefFoundError}。
 *
 * @author AlphaHu
 */
public final class TacZStatsListener implements Listener {

    private final RoundSummaryModule module;

    public TacZStatsListener(RoundSummaryModule module) {
        this.module = module;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFire(TacZGunFireBridgeEvent event) {
        if (event.getShooter() instanceof Player player) {
            module.countShot(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHurt(TacZEntityHurtPreBridgeEvent event) {
        if (event.getAttacker() instanceof Player player) {
            module.countHit(player, event.isHeadShot(), event.getBaseAmount());
            module.recordGunCause(player, event.getHurtEntity());
        }
    }
}
