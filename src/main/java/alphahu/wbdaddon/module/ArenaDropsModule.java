package alphahu.wbdaddon.module;

import alphahu.wbdaddon.WBDAddon;
import alphahu.wbdaddon.api.AddonMode;
import alphahu.wbdaddon.mode.BombDefuseMode;
import com.tacz.bridge.spigot.api.TacZSpigotApi;
import com.warz.bombdefuse.arena.ArenaSession;
import com.warz.bombdefuse.util.ItemUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 阵亡掉落模块。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>WarZBombDefuse <b>不走原版死亡流程</b>：{@code GameListener.handleDamage()}
 * 在致死伤害时直接 {@code event.setCancelled(true)}，再自己调
 * {@code ArenaSession.eliminate()} 处理淘汰。于是 {@code PlayerDeathEvent} 不触发，
 * 玩家身上什么都不会掉 —— 哪怕服务端的 {@code keepInventory} 是 false。
 *
 * <h2>为什么必须在伤害事件里掉，而不是等淘汰事件</h2>
 *
 * <p>外观上 {@code WbdPlayerEliminatedEvent} 是最自然的挂载点，但实际不行：
 * {@code eliminate()} 会先调 {@code dropToSpectator()}，
 * 而它内部的 {@code applySpectatorState()} 第一件事就是
 * <b>{@code player.getInventory().clear()}</b>。等事件发出来时背包已经空了。
 *
 * <p>所以掉落必须抢在 WBD 之前完成：本模块的伤害监听用 {@code NORMAL}，
 * 而 WBD 是 {@code HIGHEST}，正好早一步。判定条件与 WBD 完全一致
 * （{@code 当前血量 - getFinalDamage() <= 0}），所以「我们会掉」等价于「WBD 会淘汰」。
 *
 * <h2>掉什么</h2>
 *
 * <ul>
 *   <li><b>枪</b>：通过 TacZSpigotBridge 的 GunId 判定，背包里的枪全部丢出</li>
 *   <li><b>C4</b>：可选，见下</li>
 * </ul>
 *
 * <h2>炸弹为什么不走 WBD 的自动转交</h2>
 *
 * <p>WBD 的设计是「炸弹永不落地」：携带者一死就调
 * {@code assignC4ToAliveTerrorist()} 把 {@code currentBombCarrier} 指给下一个存活 T，
 * 下一 tick 直接把 C4 塞给他。
 *
 * <p>本模块在同一个 {@code NORMAL} 监听里把 C4 从死者身上取下丢在地上，
 * 并通过反射把 {@code currentBombCarrier} 重置成一个不存在的 UUID，让 WBD 的
 * {@code isPossibleC4Carrier()} 判定为 false，从而<b>跳过自动转交</b>。
 * 拾取交给 WBD 自己的 {@code handleC4Pickup()}（它会校验物品上的 {@code c4}
 * 标记与阵营，仅 T 可捡），本模块再加一层兜底。
 *
 * <p><b>这是实验性行为</b>：依赖 WBD 的内部字段名与物品标记，WBD 升级后可能失效。
 * 失效时只会退化成「炸弹仍由 WBD 自动转交」，不会崩服，但地上会多出一个 C4。
 *
 * @author AlphaHu
 */
public class ArenaDropsModule implements AddonModule, Listener {

    /** 用来顶替 currentBombCarrier 的「不存在的人」。必须是合法 UUID，不能是 null。 */
    private static final UUID NO_CARRIER = new UUID(0L, 0L);

    private final WBDAddon plugin;

    /** 本模块丢出去的 C4 实体，用于兜底拾取规则与清理。 */
    private final Set<UUID> droppedC4 = ConcurrentHashMap.newKeySet();

    /**
     * 玩家手动丢出的 C4：物品实体 UUID -> 丢包者与「禁止他自己捡回」的截止时间。
     *
     * <p>WBD 的 {@code handleC4Drop()} 会把掉落物的 {@code pickupDelay} 设成 0，
     * 于是丢包者站在原地的下一 tick 就把它捡回去了，看起来像「丢不出去」。
     */
    private final Map<UUID, SelfDroppedC4> selfDropped = new ConcurrentHashMap<>();

    /** 每个玩家最近一次手持枪械的槽位，用于「死前刚切走」时兜底。 */
    private final Map<UUID, Integer> lastGunSlot = new ConcurrentHashMap<>();

    /** TacZ 桥接事件的监听器，桥接插件不在时为 null。 */
    private TacZDropsListener tacZListener;


    private Field bombCarrierField;
    private boolean reflectionBroken;

    public ArenaDropsModule(WBDAddon plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getId() {
        return "arena-drops";
    }

    @Override
    public String getDisplayName() {
        return "阵亡掉落";
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        resolveBombCarrierField();
        registerTacZListener();

        boolean dropC4 = plugin.getConfig().getBoolean("modules.arena-drops.drop-c4", true);
        plugin.getLogger().info("阵亡掉落已启动：枪械掉落"
                + (dropC4 ? "，C4 掉落（实验性）" : "，C4 不落地") + "。");
        if (dropC4 && reflectionBroken) {
            plugin.getLogger().warning("C4 掉落的反射准备失败，炸弹仍会由 WBD 自动转交给下一个 T。");
        }
    }

    /**
     * 接入 TacZ 桥接事件。
     *
     * <p>枪械击杀走的是 Forge 侧的 {@code TacZEntityHurtPreBridgeEvent}，
     * WBD 在这个事件里直接调 {@code eliminate()}，<b>不会</b>经过 Bukkit 的
     * {@code EntityDamageEvent}。所以只监听 Bukkit 伤害的话，枪杀永远不掉东西。
     *
     * <p>桥接插件不在时不注册：{@link TacZDropsListener} 的方法签名引用了桥接事件类，
     * 贸然加载会 {@code NoClassDefFoundError}。
     */
    private void registerTacZListener() {
        if (plugin.getTacZApi() == null) return;
        try {
            this.tacZListener = new TacZDropsListener(plugin, this);
            Bukkit.getPluginManager().registerEvents(tacZListener, plugin);
            plugin.getLogger().info("阵亡掉落已接入 TacZ 桥接事件（枪杀也会掉落）。");
        } catch (Throwable t) {
            this.tacZListener = null;
            plugin.getLogger().warning("接入 TacZ 桥接事件失败，枪杀不会触发掉落：" + t);
        }
    }

    @Override
    public void onDisable() {
        HandlerList.unregisterAll(this);
        if (tacZListener != null) {
            HandlerList.unregisterAll(tacZListener);
            tacZListener = null;
        }
        droppedC4.clear();
        selfDropped.clear();
        lastGunSlot.clear();
        plugin.getLogger().info("阵亡掉落已卸载。");
    }

    // ------------------------------------------------------------------
    // 抢在 WBD 清背包之前掉落
    // ------------------------------------------------------------------

    /**
     * 用 {@code NORMAL} 而不是 {@code MONITOR}：WBD 的伤害监听是 {@code HIGHEST}，
     * 而它在淘汰流程里会 {@code inventory.clear()}。必须比它更早执行。
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onLethalDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (plugin.getApi() == null) return;

        // 与 WBD 完全一致的致死判定，保证「我们掉」=「WBD 淘汰」
        if (player.getHealth() - event.getFinalDamage() > 0) return;

        AddonMode mode = plugin.getApi().getModeFor(player);
        if (mode == null || !mode.isPlaying(player)) return;
        // WBD 的淘汰流程只属于爆破模式
        if (!BombDefuseMode.MODE_ID.equals(mode.getModeId())) return;

        Location where = player.getLocation().clone();
        handleLethal(player, where);
    }

    /**
     * 致死时的统一入口：掉枪 + 掉 C4。
     *
     * <p>两条致伤路径都会调到这里：
     * <ul>
     *   <li>Bukkit {@link EntityDamageEvent} —— 摔落、爆炸等原版伤害</li>
     *   <li>TacZ 的 {@code TacZEntityHurtPreBridgeEvent} —— 枪械击杀。
     *       这条路径<b>不会</b>经过 Bukkit 伤害事件，WBD 直接在桥接事件里调
     *       {@code eliminate()}，所以必须单独监听，否则枪杀永远不掉东西。</li>
     * </ul>
     */
    public void handleLethal(Player victim, Location where) {
        if (plugin.getApi() == null) return;
        AddonMode mode = plugin.getApi().getModeFor(victim);
        if (mode == null || !mode.isPlaying(victim) || !mode.isAlive(victim)) return;
        if (!BombDefuseMode.MODE_ID.equals(mode.getModeId())) return;

        dropGuns(victim, where);
        maybeDropC4(victim, where, mode);
    }

    // ------------------------------------------------------------------
    // 记录「最近在用的枪」
    // ------------------------------------------------------------------

    /** 切换手持槽时记下：只要新槽里是枪，就视为「最近在用的武器」。 */
    @EventHandler
    public void onItemHeld(PlayerItemHeldEvent event) {
        TacZSpigotApi tacZ = plugin.getTacZApi();
        if (tacZ == null || !plugin.isTacZAvailable()) return;

        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItem(event.getNewSlot());
        if (item != null && !item.getType().isAir() && tacZ.getItemGunId(item).isPresent()) {
            lastGunSlot.put(player.getUniqueId(), event.getNewSlot());
        }
    }

    /** 主手/副手互换后，进到主手的那把如果是枪，也记下来。 */
    @EventHandler
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        TacZSpigotApi tacZ = plugin.getTacZApi();
        if (tacZ == null || !plugin.isTacZAvailable()) return;

        Player player = event.getPlayer();
        ItemStack toMain = event.getOffHandItem();
        if (toMain != null && !toMain.getType().isAir() && tacZ.getItemGunId(toMain).isPresent()) {
            lastGunSlot.put(player.getUniqueId(), player.getInventory().getHeldItemSlot());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastGunSlot.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------
    // 枪械掉落
    // ------------------------------------------------------------------

    /**
     * 掉落枪械。
     *
     * <p>默认只掉「死亡瞬间正在用的那一把」：手里拿着枪就是它；如果死前刚切到
     * C4 / 手雷，则回退到最近一次持过的枪。这样地上不会爆一堆装备。
     *
     * <p>想恢复「背包里的枪全掉」就把配置改成 {@code drop-mode: all}。
     */
    private void dropGuns(Player victim, Location where) {
        if (!plugin.getConfig().getBoolean("modules.arena-drops.drop-guns", true)) return;

        TacZSpigotApi tacZ = plugin.getTacZApi();
        if (tacZ == null || !plugin.isTacZAvailable()) return;

        PlayerInventory inventory = victim.getInventory();
        String mode = plugin.getConfig()
                .getString("modules.arena-drops.drop-mode", "held");

        if ("all".equalsIgnoreCase(mode)) {
            dropAllGuns(victim, where, tacZ, inventory);
            return;
        }

        int slot = resolveActiveGunSlot(victim, inventory, tacZ);
        if (slot < 0) return;

        ItemStack gun = inventory.getItem(slot);
        if (gun == null || gun.getType().isAir()) return;

        ItemStack drop = gun.clone();
        inventory.setItem(slot, null);
        where.getWorld().dropItemNaturally(where, drop);

        if (plugin.getConfig().getBoolean("modules.arena-drops.debug", false)) {
            plugin.getLogger().info("[arena-drops] " + victim.getName()
                    + " 阵亡，掉落其正在使用的枪于 " + describe(where) + "。");
        }
    }

    private void dropAllGuns(Player victim, Location where,
                             TacZSpigotApi tacZ, PlayerInventory inventory) {
        int dropped = 0;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType().isAir()) continue;
            // 只认 TacZ 的枪：弹药、配件、投掷物都不在这里掉
            if (tacZ.getItemGunId(item).isEmpty()) continue;

            ItemStack drop = item.clone();
            inventory.setItem(slot, null);
            where.getWorld().dropItemNaturally(where, drop);
            dropped++;
        }

        if (dropped > 0 && plugin.getConfig().getBoolean("modules.arena-drops.debug", false)) {
            plugin.getLogger().info("[arena-drops] " + victim.getName()
                    + " 阵亡，掉落 " + dropped + " 把枪于 " + describe(where) + "。");
        }
    }

    /** 死亡瞬间手里的枪；手里不是枪时回退到最近持过的那把。 */
    private int resolveActiveGunSlot(Player player, PlayerInventory inventory, TacZSpigotApi tacZ) {
        int held = inventory.getHeldItemSlot();
        ItemStack inHand = inventory.getItem(held);
        if (inHand != null && !inHand.getType().isAir() && tacZ.getItemGunId(inHand).isPresent()) {
            return held;
        }

        Integer last = lastGunSlot.get(player.getUniqueId());
        if (last != null && last >= 0 && last < inventory.getSize()) {
            ItemStack item = inventory.getItem(last);
            if (item != null && !item.getType().isAir() && tacZ.getItemGunId(item).isPresent()) {
                return last;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // C4 掉落（实验性）
    // ------------------------------------------------------------------

    private void maybeDropC4(Player player, Location where, AddonMode mode) {
        if (!plugin.getConfig().getBoolean("modules.arena-drops.drop-c4", true)) return;
        if (!"T".equals(mode.getTeamId(player))) return;

        int slot = findC4Slot(player);
        if (slot < 0) return;

        ArenaSession session = session(player);
        if (session == null) return;

        // 先取到物品再改 WBD 状态：万一取不到（竞态），不要留下
        // 「携带者已被清空、C4 却还在背包里」这种半截状态。
        ItemStack c4 = player.getInventory().getItem(slot);
        if (c4 == null) return;
        player.getInventory().setItem(slot, null);

        boolean cleared = clearBombCarrier(session);

        Item dropped = where.getWorld().dropItemNaturally(where, c4);
        applyC4Name(dropped);
        droppedC4.add(dropped.getUniqueId());

        plugin.getLogger().info("[arena-drops] 炸弹携带者 " + player.getName()
                + " 阵亡，C4 掉落在 " + describe(where) + "（仅 T 可拾取）。");
        if (!cleared) {
            plugin.getLogger().warning("[arena-drops] currentBombCarrier 重置失败，"
                    + "WBD 可能仍会把炸弹转交给下一个 T（地上会多出一个 C4 实体）。");
        }
    }

    /**
     * 把 WBD 的 {@code currentBombCarrier} 改成一个不存在的 UUID。
     *
     * <p>{@code isPossibleC4Carrier()} 的判定是
     * {@code hasC4(player) || player.getUniqueId().equals(currentBombCarrier)}。
     * C4 已经从背包拿走，再把这里指空，WBD 就不会再触发自动转交。
     *
     * <p>刻意用「不存在的 UUID」而不是 {@code null}：WBD 内部可能对
     * {@code currentBombCarrier} 直接调 {@code .equals(...)}，null 会 NPE。
     */
    private boolean clearBombCarrier(ArenaSession session) {
        if (reflectionBroken) return false;
        if (bombCarrierField == null) {
            resolveBombCarrierField();
        }
        if (bombCarrierField == null) return false;
        try {
            bombCarrierField.set(session, NO_CARRIER);
            return true;
        } catch (Throwable t) {
            reflectionBroken = true;
            plugin.getLogger().warning("[arena-drops] 无法写入 WBD 的 currentBombCarrier："
                    + t + "（C4 掉落将退化）");
            return false;
        }
    }

    private void resolveBombCarrierField() {
        try {
            Field field = ArenaSession.class.getDeclaredField("currentBombCarrier");
            field.setAccessible(true);
            this.bombCarrierField = field;
        } catch (Throwable t) {
            this.reflectionBroken = true;
            this.bombCarrierField = null;
            plugin.getLogger().warning("[arena-drops] 找不到 WBD 的 currentBombCarrier 字段，"
                    + "C4 掉落不可用：" + t);
        }
    }

    /**
     * 按 WBD 的 C4 配置识别物品。
     *
     * <p>WBD 自己是用物品上的 {@code c4} 标记（{@code ItemUtil.hasMarker}）来认的，
     * 这里改用「材质 + 显示名」，避免直接调用它的内部工具类；两者都读 WBD 的配置，
     * 服主改 C4 外观时会自动跟随。
     */
    private int findC4Slot(Player player) {
        Material material = c4Material();
        String name = c4Name();

        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType().isAir()) continue;
            // 优先用 WBD 自己的 c4 标记，材质/名字只作兜底
            if (plugin.getWbd() != null
                    && ItemUtil.hasMarker(item, plugin.getWbd(), "c4")) {
                return slot;
            }
            if (item.getType() != material) continue;
            if (name.isEmpty()) return slot;
            if (item.hasItemMeta() && item.getItemMeta() != null
                    && name.equals(item.getItemMeta().getDisplayName())) {
                return slot;
            }
        }
        return -1;
    }

    private Material c4Material() {
        String raw = plugin.getWbd().getConfig().getString("bomb.c4-item.material", "SLIME_BALL");
        Material material = Material.matchMaterial(raw == null ? "" : raw);
        return material == null ? Material.SLIME_BALL : material;
    }

    private String c4Name() {
        String raw = plugin.getWbd().getConfig().getString("bomb.c4-item.name", "&cC4 炸弹");
        return raw == null ? "" : ChatColor.translateAlternateColorCodes('&', raw);
    }

    /**
     * 给地上的 C4 加一个常显名牌。
     *
     * <p>物品本身已经有显示名，但那只在拾取后看得到；实体名牌才是站在旁边能看见的。
     */
    private void applyC4Name(Item item) {
        String raw = plugin.getConfig()
                .getString("modules.arena-drops.c4-display-name", "&cC4 炸弹");
        if (raw == null || raw.isEmpty()) {
            item.setCustomNameVisible(false);
            return;
        }
        item.setCustomName(ChatColor.translateAlternateColorCodes('&', raw));
        item.setCustomNameVisible(true);
    }

    // ------------------------------------------------------------------
    // 只有 T 能捡
    // ------------------------------------------------------------------

    /**
     * 兜底：WBD 的 {@code handleC4Pickup()} 已经会校验阵营，但那是围绕它自己的
     * 携带者状态实现的。我们丢出去的是普通掉落物，这里再按阵营拦一次，
     * 避免 CT 顺手捡走。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        UUID itemId = event.getItem().getUniqueId();

        // 手动丢出的 C4：短时间内禁止丢包者自己捡回（队友不受影响）
        SelfDroppedC4 self = selfDropped.get(itemId);
        if (self != null) {
            if (System.currentTimeMillis() > self.blockUntil()) {
                selfDropped.remove(itemId);
            } else if (self.dropper().equals(player.getUniqueId())) {
                event.setCancelled(true);
                return;
            } else {
                // 队友捡走了，解除标记与发光追踪
                selfDropped.remove(itemId);
            }
        }

        if (!droppedC4.contains(itemId)) return;

        AddonMode mode = plugin.getApi() == null ? null : plugin.getApi().getModeFor(player);
        boolean allowed = mode != null
                && "T".equals(mode.getTeamId(player))
                && mode.isAlive(player);
        if (!allowed) {
            event.setCancelled(true);
            return;
        }
        droppedC4.remove(itemId);
    }

    @EventHandler
    public void onDespawn(ItemDespawnEvent event) {
        UUID itemId = event.getEntity().getUniqueId();
        droppedC4.remove(itemId);
        selfDropped.remove(itemId);
    }

    /** 手动丢出的 C4：丢包者与「禁止他自捡」的截止时间。 */
    private record SelfDroppedC4(UUID dropper, long blockUntil) {
    }

    // ------------------------------------------------------------------
    // 放开 WBD 对 C4 丢弃的限制
    // ------------------------------------------------------------------

    /**
     * WBD 的 {@code GameListener.onDrop()} 只有在
     * 「allow-c4-drop-to-teammates + FREEZE/LIVE + T 方存活 + 非观战 + 未安放」
     * 全部满足时才放行，否则直接 {@code setCancelled(true)}。
     *
     * <p>这里在它之后（{@code HIGHEST} > WBD 的 {@code NORMAL}）把取消再撤销，
     * 让携带者在任意阶段都能把 C4 丢在地上 —— 配合上面的「阵亡掉落」，
     * 炸弹就可以像 CS 那样掉在地上等人捡，而不是被 WBD 锁死在背包里。
     *
     * <p>撤销取消后顺手把 {@code currentBombCarrier} 指空：炸弹已经不在任何人
     * 手上，如果还指向前携带者，他一旦阵亡 WBD 会再发一个 C4，地上就出现两个。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onC4Drop(PlayerDropItemEvent event) {
        if (plugin.getWbd() == null) return;

        Item itemEntity = event.getItemDrop();
        if (!ItemUtil.hasMarker(itemEntity.getItemStack(), plugin.getWbd(), "c4")) return;

        Player player = event.getPlayer();
        if (!isManagedT(player)) return;

        boolean wasCancelled = event.isCancelled();

        // 1. WBD 在 FREEZE/LIVE 之外会直接取消；按配置放开
        if (wasCancelled) {
            if (!plugin.getConfig().getBoolean("modules.arena-drops.allow-c4-drop", true)) return;
            event.setCancelled(false);
        }

        // 2. WBD 允许丢时会 setPickupDelay(0)，丢包者站在原地的下一 tick 就捡回去，
        //    表现就是「按了 Q 但炸弹还在手里」。给一个短延迟，并在一段时间内
        //    禁止丢包者本人拾取（队友不受影响）。
        long blockMillis = plugin.getConfig()
                .getLong("modules.arena-drops.c4-self-pickup-block-ms", 5000L);
        if (blockMillis > 0) {
            selfDropped.put(itemEntity.getUniqueId(),
                    new SelfDroppedC4(player.getUniqueId(), System.currentTimeMillis() + blockMillis));
        }
        itemEntity.setPickupDelay(10);
        applyC4Name(itemEntity);

        // 3. 炸弹已经不在任何人手上，避免 WBD 还认为携带者是丢包者
        ArenaSession session = session(player);
        if (session != null) {
            clearBombCarrier(session);
        }

        plugin.getLogger().info("[arena-drops] " + player.getName()
                + " 丢出 C4（WBD 原本" + (wasCancelled ? "已取消" : "允许")
                + "，自捡封锁 " + blockMillis + "ms）。");
    }

    /** 是否是「参赛 + 存活 + T 方」。只有 T 能持有 C4。 */
    private boolean isManagedT(Player player) {
        if (plugin.getApi() == null) return false;
        AddonMode mode = plugin.getApi().getModeFor(player);
        if (mode == null || !mode.isPlaying(player) || !mode.isAlive(player)) return false;
        return "T".equals(mode.getTeamId(player));
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private ArenaSession session(Player player) {
        if (plugin.getWbd() == null || plugin.getWbd().getArenaManager() == null) {
            return null;
        }
        return plugin.getWbd().getArenaManager().getSession(player);
    }

    private String describe(Location location) {
        return String.format("%s %.1f %.1f %.1f",
                location.getWorld() == null ? "?" : location.getWorld().getName(),
                location.getX(), location.getY(), location.getZ());
    }
}
