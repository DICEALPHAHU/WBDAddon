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
 *   <li>护甲槽被锁死：不能脱下、不能丢弃、不能拖拽</li>
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
                + (zeroProtection ? "，护甲值已抵消为 0" : "，保留原始护甲值") + "。");
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
        if (!isOursFor(inventory.getHelmet(), teamId)) inventory.setHelmet(pieces[0].clone());
        if (!isOursFor(inventory.getChestplate(), teamId)) inventory.setChestplate(pieces[1].clone());
        if (!isOursFor(inventory.getLeggings(), teamId)) inventory.setLeggings(pieces[2].clone());
        if (!isOursFor(inventory.getBoots(), teamId)) inventory.setBoots(pieces[3].clone());
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
        // 护甲槽本身也锁死，避免玩家把自己的东西塞进去或换出来
        if (event.getSlotType() == InventoryType.SlotType.ARMOR) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!isManaged(player)) return;

        // 只在玩家自己的背包视图里判断：别的容器（商店、箱子）raw slot 5..8
        // 指的是容器格子，不是护甲槽，不能误伤。
        if (event.getView().getType() != InventoryType.CRAFTING) return;

        // 玩家背包视图里，护甲槽是 raw slot 5..8
        boolean touchesArmor = event.getRawSlots().stream()
                .anyMatch(slot -> slot >= 5 && slot <= 8);
        if (touchesArmor) {
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
