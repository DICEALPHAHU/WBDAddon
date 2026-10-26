package alphahu.wbdaddon.module;

import alphahu.wbdaddon.WBDAddon;
import alphahu.wbdaddon.api.AddonMode;
import com.warz.bombdefuse.api.WbdPlayerLeaveArenaEvent;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 队服模块：给 T / CT 穿固定颜色、<b>零防护</b>的染色皮革甲。
 *
 * <h2>为什么用护甲而不是皮肤</h2>
 *
 * <p>玩家皮肤由客户端决定。整合包里的 CustomSkinLoader 会按用户名去 Mojang/镜像拉取，
 * 服务端下发的皮肤会被它盖掉；改用 SkinsRestorer 又得让所有玩家换客户端。
 * 染色皮革甲是纯服务端方案，不动客户端、不依赖任何插件。
 *
 * <h2>怎么做到「零防护」</h2>
 *
 * <p>皮革甲自带 7 点护甲（头 1 / 胸 3 / 腿 2 / 靴 1）。给每件加一个数值相同的
 * <b>负数</b> {@code GENERIC_ARMOR} 属性修饰符，两者抵消，总护甲就是 0。
 * 同时上 {@code Unbreakable} 和 {@code HIDE_ATTRIBUTES}，玩家既看不到那行奇怪的属性，
 * 也不会把队服穿坏。视觉上仍然是完整的红/蓝皮革甲。
 *
 * <h2>约束</h2>
 *
 * <ul>
 *   <li>只对「参赛且存活」的玩家生效</li>
 *   <li>只锁<b>本模块发的队服</b>：不能脱下、不能丢弃、不能拖拽；
 *       玩家自己的护甲默认不碰（见 {@code overwrite-existing}）</li>
 *   <li>死亡重生、回合重置后由定时任务自动补穿</li>
 *   <li>离开竞技场时移除，避免污染 WBD 的背包快照</li>
 * </ul>
 *
 * @author AlphaHu
 */
public class TeamArmorModule implements AddonModule, Listener {

    /** 皮革甲四件的原始护甲值，用来抵消。 */
    private static final double HELMET_DEFENSE = 1.0;
    private static final double CHEST_DEFENSE = 3.0;
    private static final double LEGS_DEFENSE = 2.0;
    private static final double BOOTS_DEFENSE = 1.0;

    private final WBDAddon plugin;

    private NamespacedKey markerKey;
    /** 记录这件队服属于哪一队，换边后据此判断需要换色。 */
    private NamespacedKey teamKey;
    private BukkitTask task;

    private ItemStack[] tPieces;
    private ItemStack[] ctPieces;

    /**
     * 是否强行顶掉玩家原有的护甲。
     *
     * <p>默认 {@code false}：只往空槽里穿、或替换本模块自己发过的队服；
     * 玩家自己穿的钻石套、下界合金套一律不碰，避免把人家的装备顶掉。
     */
    private boolean overwriteExisting;

    public TeamArmorModule(WBDAddon plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getId() {
        return "team-armor";
    }

    @Override
    public String getDisplayName() {
        return "队伍护甲";
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }

    @Override
    public void onEnable() {
        this.markerKey = new NamespacedKey(plugin, "team_armor");
        this.teamKey = new NamespacedKey(plugin, "team_armor_team");

        boolean zeroProtection = plugin.getConfig()
                .getBoolean("modules.team-armor.zero-protection", true);
        this.overwriteExisting = plugin.getConfig()
                .getBoolean("modules.team-armor.overwrite-existing", false);
        DyeColor tColor = dye("modules.team-armor.t-color", DyeColor.RED);
        DyeColor ctColor = dye("modules.team-armor.ct-color", DyeColor.BLUE);

        this.tPieces = buildSet(tColor, zeroProtection, "T");
        this.ctPieces = buildSet(ctColor, zeroProtection, "CT");

        Bukkit.getPluginManager().registerEvents(this, plugin);

        long interval = plugin.getConfig()
                .getLong("modules.team-armor.update-interval-ticks", 20L);
        this.task = Bukkit.getScheduler().runTaskTimer(
                plugin, this::tick, 0L, Math.max(1L, interval));

        plugin.getLogger().info("队伍护甲已启动：T=" + tColor + "，CT=" + ctColor
                + (zeroProtection ? "，护甲值已抵消为 0" : "，保留原始护甲值")
                + (overwriteExisting ? "，强制覆盖原有护甲" : "，只填空槽/替换自己的队服") + "。");
    }

    @Override
    public void onDisable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        HandlerList.unregisterAll(this);

        for (Player player : Bukkit.getOnlinePlayers()) {
            clearFrom(player);
        }
        tPieces = null;
        ctPieces = null;
        plugin.getLogger().info("队伍护甲已卸载。");
    }

    // ------------------------------------------------------------------
    // 定时补穿
    // ------------------------------------------------------------------

    private void tick() {
        if (plugin.getApi() == null) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            ensureFor(player);
        }
    }

    private void ensureFor(Player player) {
        if (plugin.getApi() == null) return;

        AddonMode mode = plugin.getApi().getModeFor(player);
        if (mode == null || !mode.isPlaying(player) || !mode.isAlive(player)) {
            return;
        }
        String teamId = mode.getTeamId(player);
        if (!"T".equals(teamId) && !"CT".equals(teamId)) {
            return;
        }

        ItemStack[] pieces = "T".equals(teamId) ? tPieces : ctPieces;
        if (pieces == null) return;

        PlayerInventory inventory = player.getInventory();
        ensurePiece(player, inventory.getHelmet(), pieces[0], inventory::setHelmet, teamId);
        ensurePiece(player, inventory.getChestplate(), pieces[1], inventory::setChestplate, teamId);
        ensurePiece(player, inventory.getLeggings(), pieces[2], inventory::setLeggings, teamId);
        ensurePiece(player, inventory.getBoots(), pieces[3], inventory::setBoots, teamId);
    }

    /**
     * 确保某个护甲槽是本队的队服。
     *
     * <ul>
     *   <li>已经是本队队服 → 不动</li>
     *   <li>空槽，或旧队服（换边后颜色不对）→ 直接换</li>
     *   <li>玩家自己的护甲 → 只有 {@code overwrite-existing: true} 才动，
     *       且原护甲会丢到玩家脚下，不会凭空销毁</li>
     * </ul>
     */
    private void ensurePiece(Player player, ItemStack current, ItemStack piece,
                             Consumer<ItemStack> setter, String teamId) {
        if (isOursFor(current, teamId)) return;

        boolean empty = current == null || current.getType().isAir();
        boolean ours = isOurs(current);
        if (!empty && !ours && !overwriteExisting) {
            // 玩家自己的护甲，默认绝不抢占
            return;
        }
        if (!empty && !ours) {
            // 覆盖模式：把原护甲丢到脚下，至少不凭空销毁
            player.getWorld().dropItemNaturally(player.getLocation(), current.clone());
        }
        setter.accept(piece.clone());
    }

    // ------------------------------------------------------------------
    // 锁住护甲槽
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        // 只管参赛中的玩家：大厅里玩家自己的护甲不能被误锁
        if (!isManaged(player)) return;

        // 光标上拿着队服、或正点到队服 —— 一律拦下
        if (isOurs(event.getCurrentItem()) || isOurs(event.getCursor())) {
            event.setCancelled(true);
            return;
        }
        // 玩家自己的护甲不锁：overwrite-existing=false 时本来就该让他自由更换。
        // 但要挡住「Shift 点击把别的头盔塞进我们占着的槽位」，否则队服会被顶掉。
        if (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            EquipmentSlot target = armorSlotOf(event.getCurrentItem());
            if (target != null && isOurs(armorItem(player.getInventory(), target))) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!isManaged(player)) return;

        // 只在玩家自己的背包视图里判断：别的容器（商店、箱子）raw slot 5..8
        // 指的是容器格子，不是护甲槽，不能误伤。
        if (event.getView().getType() != InventoryType.CRAFTING) return;

        // 玩家背包视图里，护甲槽是 raw slot 5..8。
        // 只拦「碰到队服」的拖拽；玩家自己的护甲随他拖。
        boolean touchesOurs = event.getRawSlots().stream()
                .anyMatch(slot -> slot >= 5 && slot <= 8
                        && isOurs(event.getView().getItem(slot)));
        boolean draggingOurs = event.getNewItems().values().stream()
                .anyMatch(this::isOurs);
        if (touchesOurs || draggingOurs) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!isManaged(event.getPlayer())) return;
        if (isOurs(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    /** 该玩家此刻是否受本模块管理（参赛 + 存活 + 分到了 T/CT）。 */
    private boolean isManaged(Player player) {
        if (player == null || plugin.getApi() == null) return false;
        AddonMode mode = plugin.getApi().getModeFor(player);
        if (mode == null || !mode.isPlaying(player) || !mode.isAlive(player)) return false;
        String teamId = mode.getTeamId(player);
        return "T".equals(teamId) || "CT".equals(teamId);
    }

    // ------------------------------------------------------------------
    // 重生 / 离开
    // ------------------------------------------------------------------

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        // 重生流程会重建背包，下一 tick 再补
        Bukkit.getScheduler().runTask(plugin, () -> ensureFor(player));
    }

    @EventHandler
    public void onLeave(WbdPlayerLeaveArenaEvent event) {
        Player player = event.getPlayer();
        if (player != null) {
            clearFrom(player);
        }
    }

    // ------------------------------------------------------------------
    // 构建物品
    // ------------------------------------------------------------------

    private ItemStack[] buildSet(DyeColor color, boolean zeroProtection, String teamId) {
        String tag = teamId.toLowerCase();
        return new ItemStack[]{
                piece(Material.LEATHER_HELMET, color, HELMET_DEFENSE,
                        EquipmentSlot.HEAD, tag + "-helmet", teamId, zeroProtection),
                piece(Material.LEATHER_CHESTPLATE, color, CHEST_DEFENSE,
                        EquipmentSlot.CHEST, tag + "-chest", teamId, zeroProtection),
                piece(Material.LEATHER_LEGGINGS, color, LEGS_DEFENSE,
                        EquipmentSlot.LEGS, tag + "-legs", teamId, zeroProtection),
                piece(Material.LEATHER_BOOTS, color, BOOTS_DEFENSE,
                        EquipmentSlot.FEET, tag + "-boots", teamId, zeroProtection)
        };
    }

    private ItemStack piece(Material material, DyeColor color, double defense,
                            EquipmentSlot slot, String tag, String teamId,
                            boolean zeroProtection) {
        ItemStack item = new ItemStack(material);
        LeatherArmorMeta meta = (LeatherArmorMeta) item.getItemMeta();

        meta.setColor(color.getColor());
        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_ATTRIBUTES);

        if (zeroProtection && defense > 0) {
            // 负数抵消该件自带的护甲值；数值相等，所以总护甲 = 0
            meta.addAttributeModifier(Attribute.GENERIC_ARMOR, new AttributeModifier(
                    UUID.nameUUIDFromBytes(("wbd-team-armor-" + tag).getBytes(StandardCharsets.UTF_8)),
                    "wbd_team_armor_" + tag,
                    -defense,
                    AttributeModifier.Operation.ADD_NUMBER,
                    slot));
        }

        meta.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(teamKey, PersistentDataType.STRING, teamId);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isOurs(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null
                && markerKey != null
                && meta.getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE);
    }

    /** 这件队服是不是「本模块发的、且属于当前这一队」。换边后颜色不对就会返回 false，触发重穿。 */
    private boolean isOursFor(ItemStack item, String teamId) {
        if (!isOurs(item)) return false;
        ItemMeta meta = item.getItemMeta();
        String tag = meta == null ? null
                : meta.getPersistentDataContainer().get(teamKey, PersistentDataType.STRING);
        return teamId.equals(tag);
    }

    private void clearFrom(Player player) {
        PlayerInventory inventory = player.getInventory();
        if (isOurs(inventory.getHelmet())) inventory.setHelmet(null);
        if (isOurs(inventory.getChestplate())) inventory.setChestplate(null);
        if (isOurs(inventory.getLeggings())) inventory.setLeggings(null);
        if (isOurs(inventory.getBoots())) inventory.setBoots(null);
    }

    /** 根据物品材质推断它会被 Shift 点击穿到哪个护甲槽；不是护甲返回 null。 */
    private EquipmentSlot armorSlotOf(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        String name = item.getType().name();
        if (name.endsWith("_HELMET") || name.endsWith("_HEAD") || name.endsWith("_SKULL")
                || name.equals("CARVED_PUMPKIN")) {
            return EquipmentSlot.HEAD;
        }
        if (name.endsWith("_CHESTPLATE") || name.equals("ELYTRA")) {
            return EquipmentSlot.CHEST;
        }
        if (name.endsWith("_LEGGINGS")) {
            return EquipmentSlot.LEGS;
        }
        if (name.endsWith("_BOOTS")) {
            return EquipmentSlot.FEET;
        }
        return null;
    }

    private ItemStack armorItem(PlayerInventory inventory, EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> inventory.getHelmet();
            case CHEST -> inventory.getChestplate();
            case LEGS -> inventory.getLeggings();
            case FEET -> inventory.getBoots();
            default -> null;
        };
    }

    private DyeColor dye(String path, DyeColor fallback) {
        String raw = plugin.getConfig().getString(path);
        if (raw == null) return fallback;
        try {
            return DyeColor.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("配置 " + path + " 的值 " + raw
                    + " 不是合法颜色，改用 " + fallback + "。");
            return fallback;
        }
    }
}
