package alphahu.wbdaddon.util;

import com.tacz.bridge.spigot.api.TacZSpigotApi;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 商店价格缓存：把玩家手里的物品映射回 WBD 商店的售价。
 *
 * <p>WBD 的 {@code shop.yml} 里每件商品都有 {@code price} 和序列化后的
 * {@code give-data}（{@code bukkit-object:...}）。这里反序列化出物品，
 * 用 TacZ 的 GunId、材质、显示名分别建立价格索引，供趣闻播报使用。
 *
 * <p>价格只读，不会改动商店文件；读取失败时对应项直接跳过。
 *
 * @author AlphaHu
 */
public final class ShopPriceCache {

    private final Map<String, Integer> gunPrices = new ConcurrentHashMap<>();
    private final Map<Material, Integer> materialPrices = new ConcurrentHashMap<>();
    private final Map<String, Integer> namePrices = new ConcurrentHashMap<>();

    private TacZSpigotApi tacZApi;

    public void load(File shopFile, TacZSpigotApi tacZApi) {
        this.tacZApi = tacZApi;
        gunPrices.clear();
        materialPrices.clear();
        namePrices.clear();
        if (shopFile == null || !shopFile.isFile()) return;

        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(shopFile);
            readSection(yaml.getConfigurationSection("shop.items"));
            ConfigurationSection teamShops = yaml.getConfigurationSection("shop.team-shops");
            if (teamShops != null) {
                for (String team : teamShops.getKeys(false)) {
                    readSection(teamShops.getConfigurationSection(team + ".items"));
                }
            }
        } catch (RuntimeException ignored) {
            // 商店配置坏了就当没有价格，不影响其它功能
        }
    }

    private void readSection(ConfigurationSection section) {
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            int price = section.getInt(key + ".price", -1);
            if (price < 0) continue;

            ItemStack item = deserialize(section.getString(key + ".give-data"));
            if (item == null) {
                // 没有完整数据时退回摘要里的材质
                Material material = Material.matchMaterial(
                        String.valueOf(section.getString(key + ".give.material", "")));
                if (material != null) {
                    materialPrices.putIfAbsent(material, price);
                }
                continue;
            }

            if (tacZApi != null) {
                String gunId = tacZApi.getItemGunId(item).orElse(null);
                if (gunId != null) {
                    gunPrices.put(gunId, price);
                }
            }
            materialPrices.putIfAbsent(item.getType(), price);

            if (item.hasItemMeta() && item.getItemMeta() != null
                    && item.getItemMeta().hasDisplayName()) {
                namePrices.putIfAbsent(
                        ChatColor.stripColor(item.getItemMeta().getDisplayName()), price);
            }
        }
    }

    /** 返回该物品在商店里的价格；查不到返回 0。 */
    public int priceOf(ItemStack item) {
        if (item == null || item.getType().isAir()) return 0;

        if (tacZApi != null) {
            String gunId = tacZApi.getItemGunId(item).orElse(null);
            if (gunId != null) {
                Integer price = gunPrices.get(gunId);
                if (price != null) return price;
            }
        }

        Integer material = materialPrices.get(item.getType());
        if (material != null) return material;

        if (item.hasItemMeta() && item.getItemMeta() != null
                && item.getItemMeta().hasDisplayName()) {
            Integer named = namePrices.get(
                    ChatColor.stripColor(item.getItemMeta().getDisplayName()));
            if (named != null) return named;
        }

        return 0;
    }

    /**
     * 只查 GunId 对应的价格，不做材质/显示名回退。
     *
     * <p>给“替换旧武器退款”用：所有 TACZ 枪共用
     * {@code TACZ_MODERN_KINETIC_GUN} 材质，如果 GunId 不在商店里却回退材质，
     * 会把第一把枪的价格套到一把根本没在商店卖过的枪上。查不到返回 -1。
     */
    public int gunPriceOf(String gunId) {
        if (gunId == null) return -1;
        Integer price = gunPrices.get(gunId);
        return price == null ? -1 : price;
    }

    public int gunCount() {
        return gunPrices.size();
    }

    public int materialCount() {
        return materialPrices.size();
    }

    private ItemStack deserialize(String data) {
        if (data == null || data.isEmpty()) return null;
        String base64 = data.startsWith("bukkit-object:")
                ? data.substring("bukkit-object:".length()) : data;
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            try (BukkitObjectInputStream in =
                         new BukkitObjectInputStream(new ByteArrayInputStream(bytes))) {
                Object object = in.readObject();
                return object instanceof ItemStack stack ? stack : null;
            }
        } catch (Throwable t) {
            return null;
        }
    }
}
