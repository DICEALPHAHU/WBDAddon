package alphahu.wbdaddon.killreports;

import alphahu.wbdaddon.WBDAddon;
import alphahu.wbdaddon.api.AddonMode;
import com.warz.bombdefuse.arena.ArenaSession;
import com.warz.bombdefuse.arena.PlayerRecord;
import com.warz.bombdefuse.model.GameState;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 间接伤害的击杀归属（手雷、燃烧瓶、区域云这类「没有直接攻击者」的伤害）。
 *
 * <h2>问题</h2>
 *
 * <p>WBD 只认两种击杀者：直接打人的玩家，和「投射物 → shooter」。
 * 模组手雷炸出来的伤害往往是 {@code ENTITY_EXPLOSION} / {@code FIRE} /
 * {@code MAGIC}，damager 可能是一个区域云、一个自定义实体，甚至干脆没有，
 * 于是 WBD 只能记成「无击杀者」——播报里没有凶手，也不给击杀数。
 *
 * <h2>做法</h2>
 *
 * <p>在 WBD 之前（{@code LOWEST}，WBD 是 {@code HIGHEST}）拦一道：
 * <ol>
 *   <li>能用 {@link Projectile#getShooter()} / {@link AreaEffectCloud#getSource()} /
 *       {@link TNTPrimed#getSource()} 解析出凶手的，就直接算清楚；</li>
 *   <li>解析不出来的（纯爆炸 / 火焰蔓延），退而求其次：找「最近一次扔投掷物」
 *       的人——同一场地、{@code radius} 格内、{@code seconds} 秒内；</li>
 *   <li>确认是这一下致死，就用正确的凶手调用 {@code session.eliminate(victim, owner)}，
 *       取消原事件（WBD 的伤害处理带 {@code ignoreCancelled}，不会重复结算）。</li>
 * </ol>
 *
 * <p>已经有直接攻击者的伤害不碰，完全交给 WBD，避免影响正常枪战。
 *
 * @author AlphaHu
 */
public class IndirectKillListener implements Listener {

    private final WBDAddon plugin;
    private final DamageTracker damageTracker;

    /** 最近一次投掷记录，key = 投掷者 UUID。 */
    private final Map<UUID, ThrowRecord> lastThrows = new ConcurrentHashMap<>();

    /** 本次致死用的武器名，供死亡播报复用，key = 受害者 UUID。 */
    private final Map<UUID, String> killWeapons = new ConcurrentHashMap<>();

    private boolean enabled;
    private double radius;
    private long windowMillis;
    private boolean debug;

    public IndirectKillListener(WBDAddon plugin, DamageTracker damageTracker) {
        this.plugin = plugin;
        this.damageTracker = damageTracker;
    }

    /** 读取配置，模块重载时调用。 */
    public void reload() {
        String base = "modules.kill-reports.indirect-";
        this.enabled = plugin.getConfig().getBoolean(base + "attribution", true);
        this.radius = Math.max(1.0, plugin.getConfig().getDouble(base + "radius", 12.0));
        this.windowMillis = Math.max(1L, plugin.getConfig().getLong(base + "seconds", 15L)) * 1000L;
        this.debug = plugin.getConfig().getBoolean("modules.kill-reports.debug", false);
    }

    /** 取走这次击杀的武器名（手雷 / 燃烧瓶），没有就返回 null。 */
    public String consumeWeapon(Player victim) {
        return victim == null ? null : killWeapons.remove(victim.getUniqueId());
    }

    // ------------------------------------------------------------------
    // 投掷记录
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (!enabled) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = event.getItem();
        if (!looksThrowable(item)) return;
        recordThrow(event.getPlayer(), item);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent event) {
        if (!enabled) return;
        if (!(event.getEntity().getShooter() instanceof Player player)) return;
        // 原版投射物（雪球 / 鸡蛋 / 箭）也可能被当作投掷物
        recordThrow(player, null);
    }

    private void recordThrow(Player player, ItemStack item) {
        if (player == null) return;
        String name = item == null ? "投掷物" : itemName(item);
        lastThrows.put(player.getUniqueId(),
                new ThrowRecord(player.getWorld().getName(),
                        player.getLocation().getX(), player.getLocation().getY(),
                        player.getLocation().getZ(), System.currentTimeMillis(), name));
        if (debug) {
            plugin.getLogger().info("[kill-reports] 记录投掷：" + player.getName() + " 扔了 " + name);
        }
    }

    /** 材质 / 显示名像不像投掷物（LRTactical 是 LRTACTICAL_THROWABLE）。 */
    private boolean looksThrowable(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        String material = item.getType().name().toUpperCase(Locale.ROOT);
        if (material.contains("THROWABLE") || material.contains("GRENADE")
                || material.contains("MOLOTOV") || material.contains("FLASH")
                || material.contains("SMOKE") || material.contains("FIREBOMB")) {
            return true;
        }
        if (!item.hasItemMeta()) return false;
        String name = itemName(item);
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("手雷") || lower.contains("手榴弹") || lower.contains("燃烧")
                || lower.contains("闪光") || lower.contains("烟雾") || lower.contains("震爆");
    }

    // ------------------------------------------------------------------
    // 伤害归属
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!enabled) return;
        if (!(event.getEntity() instanceof Player victim)) return;

        Entity damager = event instanceof EntityDamageByEntityEvent byEntity
                ? byEntity.getDamager() : null;

        // 有直接攻击者（玩家 / 投射物）时交给 WBD，别抢它的活
        if (directAttacker(damager) != null) return;

        Player owner = resolveOwner(damager, victim);
        if (owner == null || owner.equals(victim)) return;
        if (!recordable(owner, victim)) return;

        ArenaSession session = session(victim);
        if (session == null) return;
        // 只处理正常进行中的回合：冻结期 / 结算期的伤害 WBD 自己会取消
        if (session.getState() != GameState.LIVE) return;
        // 友伤不结算（WBD 的 cancel-friendly-fire 交给它自己处理）
        if (session.isSameTeam(owner, victim)) return;

        double damage = Math.max(0.0, event.getFinalDamage());
        damageTracker.recordDamage(owner, victim, damage);

        if (debug) {
            plugin.getLogger().info("[kill-reports] 间接伤害归属：" + owner.getName()
                    + " -> " + victim.getName() + "，原因 " + event.getCause()
                    + "，伤害 " + String.format("%.1f", damage)
                    + "，damager=" + (damager == null ? "无" : damager.getType()));
        }

        boolean lethal = victim.getHealth() - damage <= 0.0;
        if (!lethal) return;

        PlayerRecord record = session.getRecord(victim);
        if (record == null || !record.isAlive()) return;

        String weapon = lastThrowName(owner, victim, damager);
        killWeapons.put(victim.getUniqueId(), weapon);
        try {
            session.eliminate(victim, owner);
        } catch (Throwable t) {
            killWeapons.remove(victim.getUniqueId());
            plugin.getLogger().warning("[kill-reports] 间接击杀结算失败：" + t);
            return;
        }
        event.setCancelled(true);

        if (debug) {
            plugin.getLogger().info("[kill-reports] 间接击杀：" + owner.getName()
                    + " 用 " + weapon + " 淘汰了 " + victim.getName());
        }
    }

    /** WBD 自己能认出来的凶手：玩家本体，或投射物的 shooter。 */
    private Player directAttacker(Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile
                && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }

    /** 在 WBD 认不出的情况下继续找凶手。 */
    private Player resolveOwner(Entity damager, Player victim) {
        if (damager != null) {
            Player owner = ownerOfEntity(damager);
            if (owner != null) return owner;
        }
        return findThrower(victim);
    }

    private Player ownerOfEntity(Entity entity) {
        if (entity instanceof Player player) return player;
        if (entity instanceof Projectile projectile
                && projectile.getShooter() instanceof Player player) {
            return player;
        }
        if (entity instanceof AreaEffectCloud cloud
                && cloud.getSource() instanceof Player player) {
            return player;
        }
        if (entity instanceof TNTPrimed tnt && tnt.getSource() instanceof Player player) {
            return player;
        }
        // 自定义实体：反射试 getShooter / getSource / getOwner
        for (String methodName : new String[]{"getShooter", "getSource", "getOwner"}) {
            try {
                Method method = entity.getClass().getMethod(methodName);
                Object value = method.invoke(entity);
                if (value instanceof Player player) return player;
                if (value instanceof Entity inner) return ownerOfEntity(inner);
                if (value instanceof org.bukkit.projectiles.ProjectileSource source
                        && source instanceof Player player) {
                    return player;
                }
            } catch (Throwable ignored) {
                // 没有这个方法就试下一个
            }
        }
        return null;
    }

    /** 「最近一次扔投掷物」的人：同世界、半径内、时间窗内。 */
    private Player findThrower(Player victim) {
        long now = System.currentTimeMillis();
        String world = victim.getWorld().getName();
        Player best = null;
        double bestDistance = Double.MAX_VALUE;

        for (Map.Entry<UUID, ThrowRecord> entry : lastThrows.entrySet()) {
            ThrowRecord record = entry.getValue();
            if (!world.equals(record.world())) continue;
            long age = now - record.at();
            if (age < 0 || age > windowMillis) continue;
            double distance = record.distanceTo(victim);
            if (distance > radius) continue;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = Bukkit.getPlayer(entry.getKey());
            }
        }
        return best;
    }

    private String lastThrowName(Player owner, Player victim, Entity damager) {
        ThrowRecord record = lastThrows.get(owner.getUniqueId());
        if (record != null && record.name() != null && !record.name().isBlank()) {
            return record.name();
        }
        if (damager != null) {
            return damager.getType().name().toLowerCase(Locale.ROOT).replace("_", " ");
        }
        return "投掷物";
    }

    private boolean recordable(Player attacker, Player victim) {
        if (plugin.getApi() == null) return false;
        AddonMode mode = plugin.getApi().getModeFor(attacker);
        if (mode == null || mode != plugin.getApi().getModeFor(victim)) return false;
        String arena = mode.getArenaId(attacker);
        if (arena == null || !arena.equals(mode.getArenaId(victim))) return false;
        return true;
    }

    private ArenaSession session(Player player) {
        if (plugin.getWbd() == null || plugin.getWbd().getArenaManager() == null) return null;
        return plugin.getWbd().getArenaManager().getSession(player);
    }

    private String itemName(ItemStack item) {
        if (item.hasItemMeta() && item.getItemMeta() != null
                && item.getItemMeta().hasDisplayName()) {
            return ChatColor.stripColor(item.getItemMeta().getDisplayName());
        }
        return item.getType().name().toLowerCase(Locale.ROOT).replace("_", " ");
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        forget(event.getPlayer());
    }

    /** 清理某个玩家的投掷记录（退出 / 换场时调用）。 */
    public void forget(Player player) {
        if (player == null) return;
        lastThrows.remove(player.getUniqueId());
        killWeapons.remove(player.getUniqueId());
    }

    private record ThrowRecord(String world, double x, double y, double z, long at, String name) {
        double distanceTo(Player player) {
            var loc = player.getLocation();
            double dx = loc.getX() - x;
            double dy = loc.getY() - y;
            double dz = loc.getZ() - z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }
}
