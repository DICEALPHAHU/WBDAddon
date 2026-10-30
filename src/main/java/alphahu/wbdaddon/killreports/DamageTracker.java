package alphahu.wbdaddon.killreports;

import alphahu.wbdaddon.api.CombatStats;
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
 * <p>它同时是 {@link CombatStats} 的实现：第三方模式（如团队死斗）可以
 * 通过 {@code WbdAddonApi#getCombatStats()} 拿到同一个实例，
 * 复用这份伤害账本来校正 TacZ 环境下的击杀归属。
 *
 * @author AlphaHu
 */
public class DamageTracker implements CombatStats {

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
    @Override
    public void recordDamage(Player attacker, Player victim, double damage) {
        if (attacker == null || victim == null || damage <= 0) return;

        roundDamage
                .computeIfAbsent(victim.getUniqueId(), k -> new ConcurrentHashMap<>())
                .merge(attacker.getUniqueId(), damage, Double::sum);
        snapshotWeapon(attacker);
    }

    /**
     * 记下 attacker 此刻手里拿的是什么。
     *
     * <p>死亡播报原本是「淘汰事件触发时读杀手的主手物品」，但互杀场景下
     * 先死那个人的记录会在他自己阵亡后立刻切观战、主手被清空，
     * 等他的击杀结算时读到的就是空手 —— 播报于是写成「未知」或别人的武器。
     *
     * <p>所以在**每次造成伤害**时抓一份快照，播报时优先用它。
     */
    private void snapshotWeapon(Player attacker) {
        try {
            org.bukkit.inventory.ItemStack hand =
                    attacker.getInventory().getItemInMainHand();
            weaponByKiller.put(attacker.getUniqueId(),
                    new WeaponSnapshot(hand == null ? null : hand.clone(),
                            System.currentTimeMillis()));
        } catch (Throwable ignored) {
            // 读不到就沿用原来的「读主手」逻辑
        }
    }

    /** 造成最近一次伤害时手里的武器；超过 {@link #WEAPON_TTL_MILLIS} 视为过期。 */
    public org.bukkit.inventory.ItemStack weaponSnapshot(Player attacker) {
        if (attacker == null) return null;
        WeaponSnapshot snapshot = weaponByKiller.get(attacker.getUniqueId());
        if (snapshot == null) return null;
        if (System.currentTimeMillis() - snapshot.at() > WEAPON_TTL_MILLIS) {
            weaponByKiller.remove(attacker.getUniqueId());
            return null;
        }
        return snapshot.item();
    }

    /** 致死武器快照；超过 10 秒就不再当作「这一枪」的结果。 */
    private static final long WEAPON_TTL_MILLIS = 10_000L;

    private final Map<UUID, WeaponSnapshot> weaponByKiller = new ConcurrentHashMap<>();

    /** 某次伤害时手里那件武器。 */
    private record WeaponSnapshot(org.bukkit.inventory.ItemStack item, long at) {
    }

    /**
     * 获取指定受害者在本回合受到的所有伤害来源，按伤害降序排列。
     *
     * @param victimUuid 受害者 UUID
     * @return 攻击者 UUID 与累计伤害的列表，无记录时返回空列表
     */
    @Override
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
    @Override
    public void reset() {
        roundDamage.clear();
    }
}