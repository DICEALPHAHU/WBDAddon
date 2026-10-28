package alphahu.wbdaddon.module;

import alphahu.wbdaddon.WBDAddon;
import com.warz.bombdefuse.WarZBombDefusePlugin;
import com.warz.bombdefuse.arena.ArenaSession;
import com.warz.bombdefuse.arena.PlayerRecord;
import com.warz.bombdefuse.shop.ShopItem;
import com.warz.bombdefuse.shop.ShopManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * 商店价格标签：在商品 lore 下面补一行价格。
 *
 * <h2>为什么加这个</h2>
 *
 * <p>WBD 原生的商店界面只显示物品自己的 lore，价格藏在 {@code shop.yml} 里，
 * 玩家不看配置根本不知道贵不贵，也就无从比较「买哪个更合算」。这里把售价直接
 * 贴到物品说明里，顺便按玩家的余额染个色：买得起是绿的，买不起是红的。
 *
 * <h2>怎么认商品</h2>
 *
 * <p>用 WBD 自己的 {@link ShopManager#isShopInventory(Inventory)} 认界面，
 * 再拿 {@link ShopItem#getDisplayStack()} 和格子里的物品逐个比对——
 * 比按页码/槽位推算稳，翻页了也不会错。翻页后 WBD 会重建界面，
 * 所以在点击事件后再刷一次。
 *
 * <p>只改客户端看到的 lore，不动 WBD 的购买逻辑：点哪个格子、扣多少钱
 * 仍然由 WBD 自己决定。
 *
 * @author AlphaHu
 */
public class ShopPriceModule implements AddonModule, Listener {

    private static final String ID = "shop-price";

    private final WBDAddon plugin;

    public ShopPriceModule(WBDAddon plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "商店价格标签";
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("商店价格标签已启动：商品 lore 下方显示售价（买得起绿、买不起红）。");
    }

    @Override
    public void onDisable() {
        HandlerList.unregisterAll(this);
        plugin.getLogger().info("商店价格标签已卸载。");
    }

    // ------------------------------------------------------------------
    // 事件
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        decorate(player, event.getInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory inventory = event.getInventory();
        // 翻页/切换阵营会重建同一个界面，下一 tick 再刷一遍
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && player.getOpenInventory().getTopInventory() != null) {
                decorate(player, player.getOpenInventory().getTopInventory());
            }
        });
        decorate(player, inventory);
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    private void decorate(Player player, Inventory inventory) {
        if (inventory == null) return;
        if (!plugin.getConfig().getBoolean("modules." + ID + ".enabled", true)) return;

        WarZBombDefusePlugin wbd = plugin.getWbd();
        if (wbd == null || wbd.getShopManager() == null) return;
        ShopManager shop = wbd.getShopManager();
        if (!shop.isShopInventory(inventory)) return;

        int money = moneyOf(player);
        boolean showMoney = plugin.getConfig()
                .getBoolean("modules." + ID + ".check-balance", true);

        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.getType().isAir()) continue;
            ShopItem item = match(shop, stack);
            if (item == null || item.getPrice() <= 0) continue;
            inventory.setItem(slot, decorateStack(stack, item.getPrice(), money, showMoney));
        }
    }

    /**
     * 找出这个格子对应哪件商品。
     *
     * <p>不比整个 ItemStack：WBD 渲染时可能给显示物品加上「已购买」「售罄」
     * 之类的 lore，{@code isSimilar} 就会失配。按「材质 + 显示名」比足够准，
     * 也不会因为 lore 变了就认不出。
     */
    private ShopItem match(ShopManager shop, ItemStack stack) {
        String name = plainName(stack);
        for (ShopItem item : shop.getItems()) {
            ItemStack display = item.getDisplayStack();
            if (display == null) continue;
            if (display.getType() != stack.getType()) continue;
            if (!plainName(display).equals(name)) continue;
            return item;
        }
        return null;
    }

    private String plainName(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return stripColor(meta.getDisplayName()).trim();
        }
        return stack.getType().name();
    }

    private ItemStack decorateStack(ItemStack stack, int price, int money, boolean showMoney) {
        ItemStack copy = stack.clone();
        ItemMeta meta = copy.getItemMeta();
        if (meta == null) return copy;

        List<String> lore = meta.hasLore() && meta.getLore() != null
                ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        lore.removeIf(line -> stripColor(line).startsWith("价格"));

        boolean affordable = !showMoney || money >= price;
        String format = plugin.getConfig().getString("modules." + ID + ".format",
                "&7价格：&e$%price%");
        String text = format.replace("%price%", String.valueOf(price));
        if (showMoney && !affordable) {
            text = plugin.getConfig().getString("modules." + ID + ".unaffordable-format",
                    "&7价格：&c$%price% &8(金钱不足)").replace("%price%", String.valueOf(price));
        }

        // 价格放在最下面，并空一行，和商品自己的 lore 分开
        if (!lore.isEmpty()) {
            lore.add("");
        }
        lore.add(color(text));
        meta.setLore(lore);
        copy.setItemMeta(meta);
        return copy;
    }

    private int moneyOf(Player player) {
        try {
            ArenaSession session = plugin.getWbd().getArenaManager().getSession(player);
            if (session == null) return 0;
            PlayerRecord record = session.getRecord(player);
            return record == null ? 0 : record.getMoney();
        } catch (Throwable t) {
            return 0;
        }
    }

    private String stripColor(String raw) {
        return ChatColor.stripColor(raw == null ? "" : raw);
    }

    private String color(String raw) {
        return ChatColor.translateAlternateColorCodes('&', raw == null ? "" : raw);
    }
}
