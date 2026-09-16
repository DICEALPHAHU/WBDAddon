package alphahu.wbdaddon.killreports;

import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 伤害数据记录器。
 *
 * <p>记录每个回合内"谁对谁造成了多少伤害"。
 * 数据结构：受害者 UUID -> (攻击者 UUID -> 累计伤害)。
 *
 * <p>每回合开始时清空，回合结束时读取并广播。
 *
 * @author AlphaHu
 */
public class DamageTracker {

    /** 受害者 UUID -> (攻击者 UUID -> 累计伤害) */
    private final Map<UUID, Map<UUID, Double>> roundDamage = new ConcurrentHashMap<>();

    /**
     * 记录一次伤害。
     * 只在同一竞技场的存活玩家之间调用。
     *
     * @param attacker 攻击者
     * @param victim   受害者
     * @param damage   本次伤害量
     */
    public void recordDamage(Player attacker, Player victim, double damage) {
        if (attacker == null || victim == null || damage <= 0) return;

        roundDamage
                .computeIfAbsent(victim.getUniqueId(), k -> new ConcurrentHashMap<>())
                .merge(attacker.getUniqueId(), damage, Double::sum);
    }

    /**
     * 获取指定受害者在本回合受到的所有伤害来源，按伤害降序排列。
     *
     * @param victimUuid 受害者 UUID
     * @return 攻击者 UUID 与累计伤害的列表，无记录时返回空列表
     */
    public List<Map.Entry<UUID, Double>> getDamageSources(UUID victimUuid) {
        Map<UUID, Double> sources = roundDamage.get(victimUuid);
        if (sources == null || sources.isEmpty()) {
            return Collections.emptyList();
        }

        List<Map.Entry<UUID, Double>> list = new ArrayList<>(sources.entrySet());
        list.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        return list;
    }

    /**
     * 获取本回合所有伤害记录，用于回合总览。
     * 返回的是不可修改视图，遍历时不会阻塞写入。
     */
    public Map<UUID, Map<UUID, Double>> getAllDamage() {
        return Collections.unmodifiableMap(roundDamage);
    }

    /** 本回合是否没有任何伤害记录。 */
    public boolean isEmpty() {
        return roundDamage.isEmpty();
    }

    /** 清空本回合数据。新回合开始时调用。 */
    public void reset() {
        roundDamage.clear();
    }
}