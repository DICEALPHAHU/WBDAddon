package alphahu.wbdaddon.util;

import com.tacz.bridge.spigot.api.TacZSpigotApi;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 武器名称解析。
 *
 * <p>把「玩家手里拿着什么」翻成一句能播报的文字。原本这段逻辑埋在
 * {@code DeathMessageListener} 的私有方法里，但第三方模式接入后同样需要它，
 * 于是提到工具类里，由 {@code WbdAddonApi#resolveWeaponName} 对外复用。
 *
 * <p>解析顺序：
 * <ol>
 *   <li>TacZ 可用且手里是枪 → 取 GunId 转成 {@code tacz:p320 → P320}</li>
 *   <li>物品有自定义显示名 → 用它（剥掉颜色码）</li>
 *   <li>物品有 lore → 用首行（剥掉颜色码）</li>
 *   <li>都没有 → 材质名，下划线转空格</li>
 * </ol>
 *
 * @author AlphaHu
 */
public final class WeaponNames {

    private WeaponNames() {
    }

    /**
     * 解析玩家当前手持武器的名称。
     *
     * @param killer      击杀者，为 null 时返回「未知」
     * @param tacZApi     TacZSpigotBridge API，不可用时传 null
     * @param tacZUsable  该 API 此刻是否真的可用（桥接插件在、且 TacZ 运行时活着）
     */
    public static String resolve(Player killer, TacZSpigotApi tacZApi, boolean tacZUsable) {
        if (killer == null) {
            return "未知";
        }

        ItemStack item = killer.getInventory().getItemInMainHand();
        if (item == null || item.getType().isAir()) {
            return "拳头";
        }

        // TacZ 可用时，优先取枪的真实 GunId 并人性化显示（tacz:p320 → P320）。
        // 显示名只存在于客户端资源包，服务端 bridge 只给 GunId，故按 GunId 转大写。
        if (tacZUsable && tacZApi != null) {
            String gunId = tacZApi.getItemGunId(item).orElse(null);
            if (gunId != null) {
                return humanizeGunId(gunId);
            }
        }

        // 非 TacZ 物品：优先自定义显示名，其次 lore 首行，最后回退类型名；
        // stripColor 去掉颜色码，避免出现 &a 之类乱码。
        if (item.hasItemMeta()) {
            var meta = item.getItemMeta();
            if (meta != null && meta.hasDisplayName()) {
                return ChatColor.stripColor(meta.getDisplayName());
            }
            if (meta != null && meta.hasLore() && meta.getLore() != null && !meta.getLore().isEmpty()) {
                return ChatColor.stripColor(meta.getLore().get(0));
            }
        }
        return item.getType().name().toLowerCase().replace("_", " ");
    }

    /** tacz:p320 → P320；tacz:ar_15 → AR 15；tacz:twx_r201 → TWX R201 */
    public static String humanizeGunId(String gunId) {
        String id = gunId.contains(":") ? gunId.substring(gunId.indexOf(':') + 1) : gunId;
        return Arrays.stream(id.split("_"))
                .filter(s -> !s.isEmpty())
                .map(String::toUpperCase)
                .collect(Collectors.joining(" "));
    }
}
