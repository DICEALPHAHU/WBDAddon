package alphahu.wbdaddon.module;

import alphahu.wbdaddon.WBDAddon;
import alphahu.wbdaddon.killreports.DamageTracker;
import alphahu.wbdaddon.killreports.DeathMessageListener;
import alphahu.wbdaddon.killreports.RoundStatsListener;
import alphahu.wbdaddon.killreports.TacZDamageListener;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;

/**
 * 击杀报告模块。
 *
 * <p>功能：
 * <ul>
 *   <li>监听 WbdPlayerEliminatedEvent，显示击杀者与武器</li>
 *   <li>监听回合事件，在回合结束时广播伤害总览</li>
 *   <li>记录伤害数据，TacZ 可用时通过 TacZSpigotBridge 获取更准确的击杀归属</li>
 * </ul>
 *
 * @author AlphaHu
 */
public class KillReportsModule implements AddonModule {

    private final WBDAddon plugin;

    private DamageTracker damageTracker;

    private DeathMessageListener deathListener;
    private RoundStatsListener roundListener;

    private TacZDamageListener tacZListener;

    public KillReportsModule(WBDAddon plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getId() {
        return "kill-reports";
    }

    @Override
    public String getDisplayName() {
        return "击杀报告";
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }

    @Override
    public void onEnable() {
        this.damageTracker = new DamageTracker();

        // 死亡消息监听器：显示击杀者与武器
        deathListener = new DeathMessageListener(plugin, damageTracker);
        Bukkit.getPluginManager().registerEvents(deathListener, plugin);

        // 回合统计监听器：回合结束时广播伤害总览
        roundListener = new RoundStatsListener(plugin, damageTracker);
        Bukkit.getPluginManager().registerEvents(roundListener, plugin);

        // 伤害记录统一用原版 EntityDamageByEntityEvent：Arclight 上实际能收到真实伤害，
        // 而桥接事件 TacZEntityHurtBridgeEvent 在本环境不派发，故不使用。
        // 监听器内部已去掉 ignoreCancelled，让被取消的致死一发也能尝试记录。
        tacZListener = new TacZDamageListener(plugin, damageTracker);
        Bukkit.getPluginManager().registerEvents(tacZListener, plugin);
    }

    @Override
    public void onDisable() {
        // 显式注销监听器，避免 reload 时同一事件被处理多次
        if (deathListener != null) {
            HandlerList.unregisterAll(deathListener);
            deathListener = null;
        }
        if (roundListener != null) {
            HandlerList.unregisterAll(roundListener);
            roundListener = null;
        }
        if (tacZListener != null) {
            HandlerList.unregisterAll(tacZListener);
            tacZListener = null;
        }

        damageTracker = null;
    }

    public DamageTracker getDamageTracker() {
        return damageTracker;
    }
}