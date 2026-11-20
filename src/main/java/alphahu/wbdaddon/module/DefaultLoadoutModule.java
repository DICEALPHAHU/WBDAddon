package alphahu.wbdaddon.module;

import alphahu.wbdaddon.WBDAddon;
import com.warz.bombdefuse.api.WbdRoundStartEvent;
import com.warz.bombdefuse.arena.ArenaSession;
import com.warz.bombdefuse.arena.PlayerRecord;
import com.warz.bombdefuse.model.GameState;
import com.warz.bombdefuse.model.Team;
import com.warz.bombdefuse.shop.ShopItem;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 开局默认装备：每回合给双方发一把默认手枪和一把默认刀。
 *
 * <h2>为什么</h2>
 *
 * <p>WBD 每回合会清空背包，玩家不买枪就空手上场，前几秒只能干看着。
 * CS 的做法是人人有手枪 + 刀，打不过还能靠刀翻盘，这里补齐这一层。
 *
 * <h2>物品从哪来</h2>
 *
 * <p>直接**克隆商店商品**的发放物品（{@code ShopItem.getGiveStack()}），
 * 这样发出去的和商店卖的是同一件东西 —— 弹药、配件、NBT 全对，不会造出空壳枪。
 * 配置里可以写商品 id，也可以写 TacZ 的 GunId（会自动去商店里找对应的那件）。
 *
 * <h2>默认枪是有标记的</h2>
 *
 * <p>发出去的物品会打一个 {@code wbd_default} 标记。买新手枪替换它时，
 * 装备槽守卫看到标记就知道**该把它丢到地上**，而不是按商店售价退款 ——
 * 否则白送的手枪一换就是 200 块，成了刷钱口子。
 *
 * <h2>为什么不能只靠回合开始事件（重要）</h2>
 *
 * <p>WBD 的 {@code startRound()} 顺序是：先置 FREEZE → 调 {@code prepareRoundPlayers()}
 * （这一层是 {@code runEntity} 的**延迟任务**，背包清空发生在这里）→ **然后**才
 * {@code callEvent(WbdRoundStartEvent)}。也就是说事件跑到我们手上时，清包任务往往
 * 还挂在队列里没执行，此刻发的东西会被随后的 {@code PlayerInventory.clear()} 一并抹掉。
 * 这就是「第一回合有装备、之后几回合没装备」的原因。
 *
 * <p>换边那一回合同理，而且更隐蔽：换边发生在 {@code prepareRoundPlayers()} 之前，
 * 监听者如果按缓存的老队伍发枪，就会把 T 的 G17 发到刚变成 CT 的人手上。
 *
 * <p>因此这里不再赌时序，改成「**意图登记 + 兑现校验**」：
 * <ul>
 *   <li>回合开始事件只登记「这个玩家这回合该拿什么」，不发东西；</li>
 *   <li>一个每 tick 跑的兑现任务检查登记过的玩家：该发的槽位空着、或背包刚被
 *       清空过，才真正发放，发完即从登记表里划掉；</li>
 *   <li>超过兑现窗口（默认 200 tick）仍没兑现的登记直接丢弃，避免残留到下一回合；</li>
 *   <li>每次兑现都重新读阵营，所以换边回来拿到的永远是**当前**那一方的默认枪。</li>
 * </ul>
 * 这样无论 WBD 什么时候清包、清几次，最终状态都是「人手一把默认枪 + 一把刀」。
 *
 * @author AlphaHu
 */
public class DefaultLoadoutModule implements AddonModule, Listener {

    private static final String ID = "default-loadout";

    /** 一条待兑现的发放意图。 */
    private record Order(UUID playerId, long expireAt) {
    }

    private final WBDAddon plugin;
    private final NamespacedKey defaultKey;

    /** 玩家 -> 待兑现的发放意图。 */
    private final Map<UUID, Order> pending = new ConcurrentHashMap<>();

    private boolean enabled;
    private boolean onlyIfMissing;
    private long delayTicks;
    private long expireTicks;
    private final List<String> tItems = new ArrayList<>();
    private final List<String> ctItems = new ArrayList<>();
    private String meleeId = "";

    private BukkitTask task;

    public DefaultLoadoutModule(WBDAddon plugin) {
        this.plugin = plugin;
        this.defaultKey = new NamespacedKey(plugin, "default_item");
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "默认装备";
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }

    @Override
    public void onEnable() {
        load();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        // 每 tick 兑现一批意图：WBD 的清包任务随时可能插在中间，只能靠轮询对齐
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        plugin.getLogger().info("默认装备已启动：T " + tItems + " / CT " + ctItems
                + " / 近战 " + (meleeId.isBlank() ? "无" : meleeId)
                + " / 订阅后 " + delayTicks + " tick 内兑现。");
    }

    @Override
    public void onDisable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        pending.clear();
        HandlerList.unregisterAll(this);
        plugin.getLogger().info("默认装备已卸载。");
    }

    private void load() {
        tItems.clear();
        ctItems.clear();
        String base = "modules." + ID + ".";
        enabled = plugin.getConfig().getBoolean(base + "enabled", true);
        onlyIfMissing = plugin.getConfig().getBoolean(base + "only-if-missing", true);
        delayTicks = Math.max(1L, plugin.getConfig().getLong(base + "delay-ticks", 40L));
        expireTicks = Math.max(delayTicks, plugin.getConfig().getLong(base + "expire-ticks", 200L));
        tItems.addAll(plugin.getConfig().getStringList(base + "t"));
        ctItems.addAll(plugin.getConfig().getStringList(base + "ct"));
        meleeId = plugin.getConfig().getString(base + "melee", "");
        if (meleeId == null) meleeId = "";
    }

    public void reload() {
        load();
    }

    // ------------------------------------------------------------------
    // 发装备
    // ------------------------------------------------------------------

    /**
     * 回合开始只是**登记意图**，不直接发东西。
     *
     * <p>此刻 WBD 的清包任务很可能还排在队列后面（见类注释），
     * 直接 {@code addItem} 会被随后的 {@code clear()} 吞掉。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRoundStart(WbdRoundStartEvent event) {
        if (!enabled) return;
        ArenaSession session = session(event.getArenaId());
        if (session == null) return;

        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerRecord record = session.getRecord(player);
            if (record == null || !record.isAlive()) continue;
            Team team = record.getTeam();
            if (team != Team.T && team != Team.CT) continue;
            pending.put(player.getUniqueId(), new Order(player.getUniqueId(), now + delayTicks));
        }
        if (debug()) {
            plugin.getLogger().info("[default-loadout] 第 " + event.getRound()
                    + " 回合登记了 " + pending.size() + " 名玩家的默认装备。");
        }
    }

    /**
     * 玩家中途重连：WBD 若把他直接放回战场（而不是让他排队下一回合），
     * 也要补一份默认装备。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!enabled) return;
        Player player = event.getPlayer();
        ArenaSession session = sessionOf(player);
        if (session == null) return;
        PlayerRecord record = session.getRecord(player);
        if (record == null || !record.isAlive()) return;
        Team team = record.getTeam();
        if (team != Team.T && team != Team.CT) return;
        pending.put(player.getUniqueId(),
                new Order(player.getUniqueId(), System.currentTimeMillis() + delayTicks));
    }

    // ------------------------------------------------------------------
    // 兑现
    // ------------------------------------------------------------------

    private void tick() {
        if (pending.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Order> entry : new ArrayList<>(pending.entrySet())) {
            UUID id = entry.getKey();
            Order order = entry.getValue();

            if (now < order.expireAt()) {
                // 还在观察窗口内，等 WBD 清包
                continue;
            }
            if (now > order.expireAt() + expireTicks) {
                pending.remove(id);
                continue;
            }

            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) {
                pending.remove(id);
                continue;
            }
            ArenaSession session = sessionOf(player);
            if (session == null) {
                pending.remove(id);
                continue;
            }
            PlayerRecord record = session.getRecord(player);
            if (record == null || !record.isAlive()) {
                pending.remove(id);
                continue;
            }
            // 清包还没发生（背包里还有 WBD 发的绿宝石商店道具之外的东西）时先别发，
            // 否则发出去的东西会被下一次 clear() 抹掉。
            if (isPreparing(record, player)) {
                continue;
            }

            // 阵营每次现读，换边自然拿到新一方的枪
            Team team = record.getTeam();
            if (team != Team.T && team != Team.CT) {
                pending.remove(id);
                continue;
            }
            give(player, team);
            pending.remove(id);
        }
    }

    /**
     * WBD 是否还处在「本回合准备中」——判断依据是 {@code queuedForNextRound}：
     * {@code prepareRoundPlayer()} 会把它置 true，并且紧接着清空背包。
     */
    private boolean isPreparing(PlayerRecord record, Player player) {
        try {
            if (record.isQueuedForNextRound()) return true;
        } catch (Throwable ignored) {
            // 老版本没有这个方法就跳过这层保护
        }
        return player.getInventory().getItem(0) == null
                && player.getInventory().getItem(1) == null
                && player.getInventory().getItem(2) == null
                && countNonAir(player.getInventory()) == 0
                && System.currentTimeMillis() < 0;
    }

    private int countNonAir(PlayerInventory inventory) {
        int count = 0;
        for (ItemStack stack : inventory.getContents()) {
            if (stack != null && !stack.getType().isAir()) count++;
        }
        return count;
    }

    private void give(Player player, Team team) {
        PlayerInventory inventory = player.getInventory();
        List<String> ids = team == Team.CT ? ctItems : tItems;

        if (!ids.isEmpty() && !(onlyIfMissing && hasCategory(inventory, "secondary"))) {
            ItemStack pistol = findItem(ids);
            if (pistol != null) {
                place(inventory, pistol, secondarySlot());
            }
        }

        if (!meleeId.isBlank() && !(onlyIfMissing && hasCategory(inventory, "melee"))) {
            ItemStack knife = findItem(List.of(meleeId));
            if (knife != null) {
                place(inventory, knife, meleeSlot());
            }
        }
        sync(player);
        if (debug()) {
            plugin.getLogger().info("[default-loadout] 已给 " + player.getName()
                    + "（" + team + "）发放默认装备：" + ids + " + "
                    + (meleeId.isBlank() ? "无近战" : meleeId));
        }
    }

    @SuppressWarnings("deprecation")
    private void sync(Player player) {
        try {
            player.updateInventory();
        } catch (Throwable ignored) {
            // Arclight 对 updateInventory 的实现有差异，失败不影响下一次刷新
        }
    }

    private void place(PlayerInventory inventory, ItemStack stack, int preferred) {
        ItemStack copy = stack.clone();
        mark(copy);
        if (preferred >= 0 && preferred <= 8) {
            ItemStack current = inventory.getItem(preferred);
            if (current == null || current.getType().isAir()) {
                inventory.setItem(preferred, copy);
                return;
            }
            // 槽位被 WBD 的商店道具占着，别覆盖它
            inventory.addItem(copy);
            return;
        }
        inventory.addItem(copy);
    }

    /** 按商品 id 或 GunId 找出要发的那件物品。 */
    private ItemStack findItem(List<String> ids) {
        var shop = plugin.getWbd() == null ? null : plugin.getWbd().getShopManager();
        if (shop == null) return null;
        for (String raw : ids) {
            String key = raw.trim();
            if (key.isEmpty()) continue;
            for (ShopItem item : shop.getItems()) {
                if (item.getGiveStack() == null) continue;
                if (item.getId().equalsIgnoreCase(key) || gunIdOf(item).equalsIgnoreCase(key)) {
                    return item.getGiveStack();
                }
            }
        }
        return null;
    }

    private String gunIdOf(ShopItem item) {
        try {
            var api = plugin.getTacZApi();
            if (api == null || item.getGiveStack() == null) return "";
            var id = api.getItemGunId(item.getGiveStack());
            return id == null || id.isEmpty() ? "" : id.orElse("");
        } catch (Throwable t) {
            return "";
        }
    }

    private boolean hasCategory(PlayerInventory inventory, String category) {
        try {
            var module = plugin.getModuleManager().getModule("loadout-guard");
            if (!(module instanceof LoadoutGuardModule guard)) return false;
            for (ItemStack stack : inventory.getStorageContents()) {
                if (stack == null || stack.getType().isAir()) continue;
                if (category.equals(guard.categoryKey(stack))) return true;
            }
        } catch (Throwable ignored) {
            // 守卫没启用时当作没有
        }
        return false;
    }

    private int secondarySlot() {
        return plugin.getConfig().getInt("modules.loadout-guard.slots.secondary", 1);
    }

    private int meleeSlot() {
        return plugin.getConfig().getInt("modules.loadout-guard.slots.melee", 2);
    }

    private boolean debug() {
        return plugin.getConfig().getBoolean("modules." + ID + ".debug", false);
    }

    private void mark(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(defaultKey, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
    }

    /** 这件物品是不是我们发出去的默认装备。 */
    public boolean isDefaultItem(ItemStack stack) {
        if (stack == null) return false;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return false;
        return meta.getPersistentDataContainer()
                .has(defaultKey, PersistentDataType.BYTE);
    }

    private ArenaSession session(String arenaId) {
        try {
            return plugin.getWbd().getArenaManager().getSession(arenaId);
        } catch (Throwable t) {
            return null;
        }
    }

    private ArenaSession sessionOf(Player player) {
        try {
            return plugin.getWbd().getArenaManager().getSession(player);
        } catch (Throwable t) {
            return null;
        }
    }
}