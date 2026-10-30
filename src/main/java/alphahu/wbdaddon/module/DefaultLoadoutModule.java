package alphahu.wbdaddon.module;

import alphahu.wbdaddon.WBDAddon;
import alphahu.wbdaddon.api.AddonMode;
import com.warz.bombdefuse.api.WbdRoundStartEvent;
import com.warz.bombdefuse.arena.ArenaSession;
import com.warz.bombdefuse.arena.PlayerRecord;
import com.warz.bombdefuse.shop.ShopItem;
import com.warz.bombdefuse.model.Team;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
 * @author AlphaHu
 */
public class DefaultLoadoutModule implements AddonModule, Listener {

    private static final String ID = "default-loadout";

    private final WBDAddon plugin;
    private final NamespacedKey defaultKey;

    private boolean enabled;
    private boolean onlyIfMissing;
    private final List<String> tItems = new ArrayList<>();
    private final List<String> ctItems = new ArrayList<>();
    private String meleeId = "";

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
        plugin.getLogger().info("默认装备已启动：T " + tItems + " / CT " + ctItems
                + " / 近战 " + (meleeId.isBlank() ? "无" : meleeId) + "。");
    }

    @Override
    public void onDisable() {
        HandlerList.unregisterAll(this);
        plugin.getLogger().info("默认装备已卸载。");
    }

    private void load() {
        tItems.clear();
        ctItems.clear();
        String base = "modules." + ID + ".";
        enabled = plugin.getConfig().getBoolean(base + "enabled", true);
        onlyIfMissing = plugin.getConfig().getBoolean(base + "only-if-missing", true);
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

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRoundStart(WbdRoundStartEvent event) {
        if (!enabled) return;
        ArenaSession session = session(event.getArenaId());
        if (session == null) return;

        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerRecord record = session.getRecord(player);
            if (record == null || !record.isAlive()) continue;
            Team team = record.getTeam();
            if (team != Team.T && team != Team.CT) continue;
            give(player, team);
        }
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
                if (debug()) {
                    plugin.getLogger().info("[default-loadout] 发给 " + player.getName()
                            + " 近战：" + meleeId);
                }
            }
        }
        player.updateInventory();
    }

    private void place(PlayerInventory inventory, ItemStack stack, int preferred) {
        ItemStack copy = stack.clone();
        mark(copy);
        if (preferred >= 0 && preferred <= 8) {
            inventory.setItem(preferred, copy);
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
}
