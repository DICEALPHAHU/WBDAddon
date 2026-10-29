package alphahu.wbdaddon.economy;

import alphahu.wbdaddon.WBDAddon;
import alphahu.wbdaddon.module.AddonModule;
import alphahu.wbdaddon.module.LoadoutGuardModule;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * 按武器区分的击杀奖励（CS 式）。
 *
 * <h2>为什么单独一份配置</h2>
 *
 * <p>WBD 本体只有一个统一的 {@code kill-money}，刀杀和步枪杀拿一样多，
 * 于是「起大狙架点」和「拿刀绕后」在收益上没区别 —— 而 CS 里刀杀 1500、
 * AWP 100，正是靠这个差价把玩家往高风险打法上推。
 *
 * <h2>判定优先级</h2>
 *
 * <ol>
 *   <li>{@code guns}：最精确，按 TacZ 的 GunId 匹配（带不带命名空间都行）</li>
 *   <li>{@code materials}：非 TacZ 的武器（原版剑、模组刀）按材质匹配</li>
 *   <li>{@code categories}：按装备类别（近战 / 主武器 / 副武器 / 投掷物）</li>
 *   <li>{@code default}：都没匹配到时用它</li>
 * </ol>
 *
 * <p>配置成 {@code -1} 表示「这一类不接管」，交回 WBD 原本的统一奖励。
 *
 * @author AlphaHu
 */
public final class KillRewards {

    private final WBDAddon plugin;
    private final File file;

    private boolean enabled = true;
    /** 没匹配到任何规则时的奖励；-1 表示不接管。 */
    private int fallback = -1;
    private final Map<String, Integer> guns = new HashMap<>();
    private final Map<Material, Integer> materials = new HashMap<>();
    private final Map<String, Integer> categories = new HashMap<>();

    public KillRewards(WBDAddon plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "kill-rewards.yml");
        reload();
    }

    public void reload() {
        guns.clear();
        materials.clear();
        categories.clear();
        enabled = true;
        fallback = -1;

        if (!file.exists()) {
            writeTemplate();
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        enabled = yaml.getBoolean("enabled", true);
        fallback = yaml.getInt("default", -1);

        var gunsSection = yaml.getConfigurationSection("guns");
        if (gunsSection != null) {
            for (String key : gunsSection.getKeys(false)) {
                guns.put(normalizeGun(key), gunsSection.getInt(key, -1));
            }
        }

        var materialsSection = yaml.getConfigurationSection("materials");
        if (materialsSection != null) {
            for (String key : materialsSection.getKeys(false)) {
                Material material = Material.matchMaterial(key.trim().toUpperCase(Locale.ROOT));
                if (material == null) {
                    plugin.getLogger().warning("kill-rewards.yml 里的材质 " + key
                            + " 在当前服务端不存在，已忽略。");
                    continue;
                }
                materials.put(material, materialsSection.getInt(key, -1));
            }
        }

        var categoriesSection = yaml.getConfigurationSection("categories");
        if (categoriesSection != null) {
            for (String key : categoriesSection.getKeys(false)) {
                categories.put(key.trim().toLowerCase(Locale.ROOT),
                        categoriesSection.getInt(key, -1));
            }
        }

        plugin.getLogger().info("击杀奖励已载入：" + guns.size() + " 把枪 / "
                + materials.size() + " 种材质 / " + categories.size() + " 个类别。");
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * 算出这次击杀该发多少。
     *
     * <p>纯查询，没有副作用 —— 击杀播报要显示数字，结算逻辑要真正加钱，
     * 两边用同一份结果。
     *
     * @param killer         击杀者
     * @param indirectWeapon 间接击杀（手雷 / 燃烧瓶）时投掷物的显示名；直接击杀传 null
     * @return 奖励金额；{@code -1} 表示不接管（用 WBD 原本的统一奖励）
     */
    public int amount(Player killer, String indirectWeapon) {
        if (!enabled || killer == null) return -1;

        // 手雷 / 燃烧瓶：直接按投掷物类别算，手里拿的可能已经换成别的枪了
        if (indirectWeapon != null) {
            return categories.getOrDefault("throwable", fallback);
        }

        ItemStack weapon = killer.getInventory().getItemInMainHand();
        if (weapon == null || weapon.getType().isAir()) {
            return categories.getOrDefault("melee", fallback);
        }

        String gunId = gunIdOf(weapon);
        if (gunId != null) {
            Integer byGun = guns.get(normalizeGun(gunId));
            if (byGun != null) return byGun;
        }

        Integer byMaterial = materials.get(weapon.getType());
        if (byMaterial != null) return byMaterial;

        String category = categoryKey(weapon);
        if (category != null) {
            Integer byCategory = categories.get(category);
            if (byCategory != null) return byCategory;
            // 类别表里没写这一类，但写了 default：仍然按 default 走
            return fallback;
        }
        return fallback;
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private String normalizeGun(String raw) {
        String key = raw.trim().toLowerCase(Locale.ROOT);
        // 允许只写 gunId 不写命名空间
        return key;
    }

    private String gunIdOf(ItemStack stack) {
        try {
            var api = plugin.getTacZApi();
            if (api == null) return null;
            Optional<String> id = api.getItemGunId(stack);
            return id == null ? null : id.orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 借装备槽守卫的分类，保证「什么算近战」和锁格子那套是同一个判断。 */
    private String categoryKey(ItemStack stack) {
        try {
            AddonModule module = plugin.getModuleManager().getModule("loadout-guard");
            if (module instanceof LoadoutGuardModule guard) {
                return guard.categoryKey(stack);
            }
        } catch (Throwable ignored) {
            // 守卫被关掉时退回下面的材质判断
        }
        return fallbackCategory(stack);
    }

    private String fallbackCategory(ItemStack stack) {
        String material = stack.getType().name().toUpperCase(Locale.ROOT);
        if (material.startsWith("LRTACTICAL_MELEE") || material.endsWith("_SWORD")
                || material.endsWith("_AXE")) {
            return "melee";
        }
        if (material.startsWith("LRTACTICAL_THROWABLE")) return "throwable";
        if (material.equals("TACZ_MODERN_KINETIC_GUN")) {
            ItemMeta meta = stack.getItemMeta();
            if (meta != null && meta.hasDisplayName()) {
                String name = meta.getDisplayName().toLowerCase(Locale.ROOT);
                if (name.contains("手枪") || name.contains("pistol")) return "secondary";
            }
            return "primary";
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 模板
    // ------------------------------------------------------------------

    private void writeTemplate() {
        String content = """
                # ==========================================================
                # 按武器区分的击杀奖励
                # ----------------------------------------------------------
                # WBD 本体只有一个统一的 kill-money，刀杀和步枪杀拿一样多。
                # 这里是 CS 式做法：刀杀重赏、大狙低赏，用经济差推着玩家换打法。
                #
                # 判定优先级：guns（GunId）> materials（材质）> categories（类别）> default
                #
                # 任何一项写 -1 表示「这一类不接管」，交回 WBD 原本的统一奖励。
                # 改完 /wbdaddon reload 生效。
                # ==========================================================

                enabled: true

                # 没匹配到任何规则时的奖励（-1 = 不接管）
                default: 300

                # 按类别。键固定是 primary / secondary / melee / throwable
                # （分类结果与装备槽守卫共用同一套判断）
                categories:
                  primary: 300
                  secondary: 300
                  melee: 1500
                  throwable: 300

                # 按 TacZ GunId，最精确。带不带命名空间都行
                guns:
                  # 大狙一枪一个，收益压低才平衡
                  # "tacz:awp": 100
                  # "twx:twx_kcb": 100
                  # "tacz:m700": 100

                # 按 Bukkit 材质，给非 TacZ 的武器兜底
                materials: {}
                  # IRON_SWORD: 1500
                  # LRTACTICAL_MELEE: 1500
                """;
        try {
            if (!file.getParentFile().exists() && !file.getParentFile().mkdirs()) {
                return;
            }
            Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
            plugin.getLogger().info("已生成击杀奖励配置：kill-rewards.yml");
        } catch (IOException e) {
            plugin.getLogger().warning("生成 kill-rewards.yml 失败：" + e.getMessage());
        }
    }

    /** 供管理面板 / 调试用。 */
    public Logger logger() {
        return plugin.getLogger();
    }
}
