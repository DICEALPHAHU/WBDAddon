package alphahu.wbdaddon.api;

import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 回合内的伤害记录能力，由 kill-reports 模块提供，经 {@link WbdAddonApi} 对外复用。
 *
 * <p>它解决的是一个很具体的问题：TacZ 的枪械伤害在 Forge 层结算，
 * 服务端插件通过常规事件拿到的击杀归属经常是错的或干脆没有。
 * 记下「谁对谁打出了多少伤害」，就能在致死那一刻回查真正的输出者。
 *
 * <p>接口只有读写这一份累加表的能力，不含任何展示逻辑，
 * 第三方模式可以拿它做击杀归属校正与自己的伤害统计。
 *
 * @author AlphaHu
 */
public interface CombatStats {

    /**
     * 累加一次伤害。
     *
     * @param attacker 攻击者，null 或伤害非正数时忽略
     * @param victim   受害者
     * @param damage   本次伤害量
     */
    void recordDamage(Player attacker, Player victim, double damage);

    /**
     * 取某个受害者受到的伤害来源，按伤害从高到低排序。
     *
     * @param victimUuid 受害者 UUID
     * @return 攻击者 UUID 与累计伤害的列表；无记录时返回空列表
     */
    List<Map.Entry<UUID, Double>> getDamageSources(UUID victimUuid);

    /** 清空全部记录。爆破模式在每回合开始时调用；死斗模式可在每场比赛开始时调用。 */
    void reset();
}
