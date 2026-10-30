package alphahu.wbdaddon.util;

import com.tacz.bridge.spigot.api.TacZSpigotApi;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
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
 *   <li>{@code weapon-names.yml} 里配置的显示名（按 GunId 或物品显示名匹配）</li>
 *   <li>TacZ 可用且手里是枪 → 取 GunId 转成 {@code tacz:p320 → P320}</li>
 *   <li>物品有自定义显示名 → 用它（剥掉颜色码）</li>
 *   <li>物品有 lore → 用首行（剥掉颜色码）</li>
 *   <li>都没有 → 材质名，下划线转空格</li>
 * </ol>
 *
 * <p>转换表是<b>可选</b>的：不配的时候行为和以前完全一样。
 *
 * @author AlphaHu
 */
public final class WeaponNames {

    /** GunId / 显示名（小写） → 播报用的名字。 */
    private static volatile Map<String, String> table = Map.of();

    private WeaponNames() {
    }

    // ------------------------------------------------------------------
    // 转换表
    // ------------------------------------------------------------------

    /**
     * 载入 {@code weapon-names.yml}；文件不存在时先生成一份模板。
     *
     * @param file    插件目录下的 weapon-names.yml
     * @param logger  用于提示
     * @param tacZApi 用来列出所有 GunId 生成模板，可为 null
     */
    public static void load(File file, Logger logger, TacZSpigotApi tacZApi) {
        try {
            if (file == null) return;
            if (!file.exists()) {
                writeTemplate(file, tacZApi, logger);
            }
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            Map<String, String> map = new HashMap<>();
            ConfigurationSection items = yaml.getConfigurationSection("items");
            if (items != null) {
                for (String key : items.getKeys(false)) {
                    String value = items.getString(key);
                    if (value == null || value.isBlank()) continue;
                    map.put(normalize(key), value);
                }
            }
            table = Map.copyOf(map);
            logger.info("武器名转换表已载入 " + map.size() + " 条"
                    + (map.isEmpty() ? "（未配置，枪名按 GunId 自动转换）。" : "。"));
        } catch (Throwable t) {
            table = Map.of();
            logger.warning("载入 weapon-names.yml 失败，将退回自动转换的枪名：" + t);
        }
    }

    /** 清空转换表（插件卸载时调用）。 */
    public static void clear() {
        table = Map.of();
    }

    /** 当前转换表条目数。 */
    public static int size() {
        return table.size();
    }

    /**
     * 首次运行时生成模板：把所有 GunId 列出来，值先填自动转换的名字。
     *
     * <p>这样服主只需要把右边改成自己想要的中文名，不用自己去翻 GunId。
     */
    private static void writeTemplate(File file, TacZSpigotApi tacZApi, Logger logger) {
        List<String> guns = new ArrayList<>();
        if (tacZApi != null) {
            try {
                if (tacZApi.isAvailable()) {
                    guns.addAll(tacZApi.getGunIds());
                }
            } catch (Throwable ignored) {
                // 桥接不可用时就不列了，模板照样生成
            }
        }
        Collections.sort(guns, String.CASE_INSENSITIVE_ORDER);

        StringBuilder sb = new StringBuilder();
        sb.append("# ============================================================\n");
        sb.append("# 武器名转换表\n");
        sb.append("#\n");
        sb.append("# 击杀播报里的 %weapon% 取这里配置的名字，没配的仍按 GunId 自动转换。\n");
        sb.append("#   \"tacz:ak47\": \"AK-47\"\n");
        sb.append("#   → AlphaHu 使用 AK-47 淘汰了 Steve\n");
        sb.append("#\n");
        sb.append("# 匹配规则：\n");
        sb.append("#   · 大小写不敏感\n");
        sb.append("#   · 可以写完整 GunId（tacz:ak47），也可以省略命名空间（ak47）\n");
        sb.append("#   · 非 TacZ 物品也可以按它的显示名匹配\n");
        sb.append("# 改完执行 /wbdaddon reload 生效，不用重启。\n");
        sb.append("# ============================================================\n");
        sb.append("items:\n");
        if (guns.isEmpty()) {
            sb.append("  # 没有检测到 TacZ 枪械列表，按需自己补：\n");
            sb.append("  # \"tacz:ak47\": \"AK-47\"\n");
        } else {
            for (String gunId : guns) {
                sb.append("  \"").append(gunId).append("\": \"")
                        .append(humanizeGunId(gunId).replace("\"", "\\\""))
                        .append("\"\n");
            }
        }

        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            Files.writeString(file.toPath(), sb.toString(), StandardCharsets.UTF_8);
            logger.info("已生成武器名转换表模板：" + file.getName()
                    + (guns.isEmpty() ? "" : "（含 " + guns.size() + " 把枪）"));
        } catch (IOException e) {
            logger.warning("生成 weapon-names.yml 失败：" + e.getMessage());
        }
    }

    /** 查表：先按完整 ID，再按去掉命名空间的 ID；查不到返回 null。 */
    private static String lookup(String rawKey) {
        if (rawKey == null || table.isEmpty()) return null;
        String key = normalize(rawKey);
        String value = table.get(key);
        if (value != null) return value;
        int colon = key.indexOf(':');
        if (colon >= 0) {
            value = table.get(key.substring(colon + 1));
        }
        return value;
    }

    private static String normalize(String key) {
        return key.trim().toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

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
        return resolveItem(killer.getInventory().getItemInMainHand(), tacZApi, tacZUsable);
    }

    /**
     * 按物品本身解析名字。
     *
     * <p>给「互杀」场景用：击杀结算时杀手可能已经阵亡切观战、主手被清空，
     * 这时得用伤害瞬间抓的快照去解析，而不是再读一次主手。
     */
    public static String resolveItem(ItemStack item, TacZSpigotApi tacZApi, boolean tacZUsable) {
        if (item == null || item.getType().isAir()) {
            return "拳头";
        }

        // TacZ 可用时，优先取枪的真实 GunId；转换表命中就用配置的名字。
        if (tacZUsable && tacZApi != null) {
            String gunId = tacZApi.getItemGunId(item).orElse(null);
            if (gunId != null) {
                String mapped = lookup(gunId);
                if (mapped != null) return mapped;
                return humanizeGunId(gunId);
            }
        }

        // 非 TacZ 物品：优先自定义显示名，其次 lore 首行，最后回退类型名；
        // stripColor 去掉颜色码，避免出现 &a 之类乱码。
        if (item.hasItemMeta()) {
            var meta = item.getItemMeta();
            if (meta != null && meta.hasDisplayName()) {
                String display = ChatColor.stripColor(meta.getDisplayName());
                String mapped = lookup(display);
                return mapped != null ? mapped : display;
            }
            if (meta != null && meta.hasLore() && meta.getLore() != null && !meta.getLore().isEmpty()) {
                String lore = ChatColor.stripColor(meta.getLore().get(0));
                String mapped = lookup(lore);
                return mapped != null ? mapped : lore;
            }
        }
        return item.getType().name().toLowerCase().replace("_", " ");
    }

    /** tacz:p320 → P320；tacz:ar_15 → AR 15；tacz:ump45 → UMP45 */
    public static String humanizeGunId(String gunId) {
        String id = gunId.contains(":") ? gunId.substring(gunId.indexOf(':') + 1) : gunId;
        return Arrays.stream(id.split("_"))
                .filter(s -> !s.isEmpty())
                .map(String::toUpperCase)
                .collect(Collectors.joining(" "));
    }
}
