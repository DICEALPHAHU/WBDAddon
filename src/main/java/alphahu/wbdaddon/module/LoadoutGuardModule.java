package alphahu.wbdaddon.module;

import alphahu.wbdaddon.WBDAddon;
import alphahu.wbdaddon.util.ShopPriceCache;
import alphahu.wbdaddon.util.WeaponNames;
import com.tacz.bridge.spigot.api.TacZSpigotApi;
import com.tacz.bridge.spigot.api.model.TacZGunSummary;
import com.warz.bombdefuse.WarZBombDefusePlugin;
import com.warz.bombdefuse.api.WbdMoneyChangeEvent;
import com.warz.bombdefuse.api.WbdPlayerJoinArenaEvent;
import com.warz.bombdefuse.api.WbdRoundStartEvent;
import com.warz.bombdefuse.arena.ArenaSession;
import com.warz.bombdefuse.arena.PlayerRecord;
import com.warz.bombdefuse.model.GameState;
import com.warz.bombdefuse.model.Team;
import com.warz.bombdefuse.shop.ShopItem;
import com.warz.bombdefuse.shop.ShopManager;
import com.warz.bombdefuse.util.ItemUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 装备槽守卫：主武器 / 副武器 / 近战 / 投掷物的分类、限购、槽位锁定与替换退款。
 *
 * <h2>规则</h2>
 *
 * <ul>
 *   <li>主武器最多 1 把，固定快捷栏第 1 格；</li>
 *   <li>副武器（手枪）最多 1 把，固定第 2 格；</li>
 *   <li>近战武器最多 1 把，固定第 3 格；</li>
 *   <li>投掷物最多 4 颗，第 4 格放一堆，其余放背包；</li>
 *   <li>购买同类已满时，替换旧武器并退还旧武器的商店售价；</li>
 *   <li>拾取同类武器时，如果身上已经有一把，直接拦截——必须先丢掉旧的才能捡新的。</li>
 * </ul>
 *
 * <h2>为什么购买要“事后处理”</h2>
 *
 * <p>WBD 的商店点击在 {@code GameListener.onInventoryClick} 的 {@code LOWEST}
 * 阶段就已经完成扣钱和发物品，而且它<b>不检查事件是否已被取消</b>。
 * 作为第三方插件，我们没有机会在扣钱前拦截，只能在同一个 tick 的
 * {@code MONITOR} 阶段读取“购买计数是否 +1”，再把旧武器卖掉/退款、把新武器归位。
 *
 * <h2>为什么近战要能单独覆盖</h2>
 *
 * <p>不少近战模组是用 TACZ 的枪械框架做的，它们在 TACZ index 里的
 * {@code type} 往往不是 {@code melee}（常见是 {@code sniper}），只看 type
 * 会被当成主武器。这类物品在 {@code item-categories.yml} 的 {@code guns} 里
 * 显式标成 {@code melee} 即可。
 *
 * @author AlphaHu
 */
public final class LoadoutGuardModule implements AddonModule, Listener {

    /** 允许绕过本模块的权限。 */
    public static final String BYPASS_PERMISSION = "wbdaddon.loadout.bypass";

    /** 玩家背包主存储区槽位数（0-35）。 */
    private static final int STORAGE_SIZE = 36;

    /** Bukkit PlayerInventory 的副手槽位。 */
    private static final int OFFHAND_SLOT = 40;

    /** 商店展示物品 PDC 标记前缀，WBD 的 findShopItem 也按这个前缀解析。 */
    private static final String SHOP_MARKER_PREFIX = "shop:";

    private final WBDAddon plugin;

    /** 每个玩家每件商品本回合已购买次数，用来判断这次点击是否真的买成功了。 */
    private final Map<UUID, Map<String, Integer>> purchaseCounts = new ConcurrentHashMap<>();

    /** 最近一次扣钱记录，用来确认这次点击真的扣了钱（被 WBD 拒绝的点击不会扣钱）。 */
    private final Map<UUID, Spend> lastSpend = new ConcurrentHashMap<>();

    /**
     * 扣钱瞬间的背包快照，用来判断 WBD 到底把东西给没给出去。
     *
     * <p>WBD 的 {@code handleClick} 是「先扣钱 → 再发物品」，且它自己的
     * {@code InventoryClickEvent} 也注册在 {@code LOWEST}（比本插件更早），
     * 所以点击结束、我们能拿到事件时物品早就发完了。
     * 只有在扣钱那一刻抓一份快照，才能还原「发之前是什么样」。
     */
    private final Map<UUID, InventoryBefore> preGive = new ConcurrentHashMap<>();

    /** 同一个 tick 内多个事件只安排一次归位。 */
    private final Set<UUID> scheduledNormalize = ConcurrentHashMap.newKeySet();

    /** 提示冷却，避免玩家踩在掉落物上刷屏。 */
    private final Map<String, Long> messageCooldown = new ConcurrentHashMap<>();

    /** 已经提示过的 GunId，避免未知枪械刷控制台。 */
    private final Set<String> warnedGuns = ConcurrentHashMap.newKeySet();

    /** GunId -> 类别缓存。 */
    private final Map<String, Category> gunCategoryCache = new ConcurrentHashMap<>();

    private ShopPriceCache shopPrices;
    private File shopFile;
    private long shopFileModified = -1L;

    private int primarySlot = 0;
    private int secondarySlot = 1;
    private int meleeSlot = 2;
    private int throwableSlot = 3;

    private int primaryLimit = 1;
    private int secondaryLimit = 1;
    private int meleeLimit = 1;
    private int throwableLimit = 4;

    private boolean refundOldWeapon = true;
    /** 刀不要钱：买刀时直接换掉手里那把，不做同款拦截、也不退旧刀。 */
    private boolean meleeAlwaysSwap = true;
    /** 给经过分类的物品打 WBDAddon 标签，之后不再靠 TacZ 类型猜。 */
    private boolean tagItems = true;

    /** 物品分类标签的键（PDC）：primary / secondary / melee / throwable / utility。 */
    private final NamespacedKey tagKey;

    /** 每个类别是否启用（item-categories.yml 的 categories.*.enabled）。 */
    private final Map<Category, Boolean> enabledCategories = new EnumMap<>(Category.class);

    /** 买同类新武器时是否顶掉手里那把（categories.*.on-buy: replace / block）。 */
    private final Map<Category, Boolean> replaceOnBuy = new EnumMap<>(Category.class);

    /** 买「同一把」时怎么处理（categories.*.same-gun: inherit / replace / block）。 */
    private final Map<Category, SameGunMode> sameGunModes = new EnumMap<>(Category.class);

    /** 顶掉旧武器时是否退还它的商店价（categories.*.refund-old）。 */
    private final Map<Category, Boolean> refundOldFlags = new EnumMap<>(Category.class);

    /** item-categories.yml：服主可编辑的分类对照表（优先级最高）。 */
    private final Map<String, Category> tableGuns = new HashMap<>();
    private final Map<String, Category> tableNames = new HashMap<>();
    private final Map<String, Category> tableMaterials = new HashMap<>();
    private final Map<String, Category> tableTypes = new HashMap<>();
    private boolean dropDuplicates = true;
    private boolean purchaseSafety = true;
    private boolean protectOpenItem = true;
    private OpenKey openKey = OpenKey.DOUBLE_SWAP;

    /** 双击潜行的上一次时间，key = 玩家 UUID。 */
    private final Map<UUID, Long> lastSneak = new ConcurrentHashMap<>();

    /** 双击「交换副手」的上一次时间，key = 玩家 UUID。 */
    private final Map<UUID, Long> lastSwap = new ConcurrentHashMap<>();
    private boolean debug = false;
    private SameGunPolicy sameGunPolicy = SameGunPolicy.BLOCK;
    private long lockIntervalTicks = 10L;

    private BukkitTask lockTask;
    private volatile boolean active;

    private final Map<String, Category> typeCategories = new HashMap<>();
    private final Map<String, Category> gunOverrides = new HashMap<>();
    private final Set<Material> throwableMaterials = new HashSet<>();
    private final Set<Material> meleeMaterials = new HashSet<>();
    private final Set<Material> utilityMaterials = new HashSet<>();

    /** 这些材质买到/捡到后直接进背包（9-35），不占快捷栏。 */
    private final Set<Material> backpackMaterials = new HashSet<>();

    /** 配件材质：单独一个类别，只做「进背包」，不占快捷栏也不限购。 */
    private final Set<Material> attachmentMaterials = new HashSet<>();

    /** 功能道具该待的格子（默认第 6、7 格，0 基是 5、6）。 */
    private final List<Integer> utilitySlots = new ArrayList<>();
    private String taczGunMaterialName = "TACZ_MODERN_KINETIC_GUN";

    public LoadoutGuardModule(WBDAddon plugin) {
        this.plugin = plugin;
        this.tagKey = new NamespacedKey(plugin, "loadout");
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Override
    public String getId() {
        return "loadout-guard";
    }

    @Override
    public String getDisplayName() {
        return "装备槽守卫";
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }

    @Override
    public boolean isAvailable() {
        return active;
    }

    @Override
    public void onEnable() {
        purchaseCounts.clear();
        lastSpend.clear();
        preGive.clear();
        scheduledNormalize.clear();
        messageCooldown.clear();
        warnedGuns.clear();
        gunCategoryCache.clear();

        loadConfig();
        loadCategoryTable();
        this.active = true;

        // 本模块的类直接引用 TacZSpigotApi；缺桥接时 Bukkit 会拒绝注册事件，
        // 与其在控制台刷一条 Failed to register events，不如在这里明确跳过。
        if (plugin.getTacZApi() == null) {
            plugin.getLogger().warning("装备槽守卫：TacZSpigotBridge 不可用，"
                    + "本模块需要桥接 API 才能注册事件，已跳过。");
            this.active = false;
            return;
        }

        this.shopFile = new File(plugin.getWbd().getDataFolder(), "shop.yml");
        this.shopPrices = new ShopPriceCache();
        // onEnable 会复用同一个模块实例（/wbdaddon reload），
        // 必须把 mtime 清掉，否则新缓存会因为“文件没变”而跳过加载。
        this.shopFileModified = -1L;
        reloadPricesIfChanged();

        Bukkit.getPluginManager().registerEvents(this, plugin);

        long interval = Math.max(1L, lockIntervalTicks);
        this.lockTask = Bukkit.getScheduler().runTaskTimer(
                plugin, this::tickAll, 20L, interval);

        warnSlotConflicts();
        // /wbdaddon reload 或插件中途装入时，商店可能正开着；先把在场玩家的
        // 购买计数快照下来，避免第一次点击被当成“缓存缺失”而误判。
        try {
            snapshotAllArenaCounts();
        } catch (Throwable t) {
            plugin.getLogger().warning("装备槽守卫：初始化购买计数快照失败，"
                    + "首次购买可能被跳过：" + t);
        }
        plugin.getLogger().info("装备槽守卫已启动：" + slotSummary()
                + "（投掷物上限 " + (throwableLimit <= 0 ? "不限" : throwableLimit + " 颗）")
                + "；配件进背包；功能道具→" + utilitySlotSummary() + "。");
        if (!enabledCategories.isEmpty()) {
            StringBuilder off = new StringBuilder();
            for (Map.Entry<Category, Boolean> entry : enabledCategories.entrySet()) {
                if (!entry.getValue()) {
                    if (off.length() > 0) off.append("、");
                    off.append(entry.getKey().displayName);
                }
            }
            if (off.length() > 0) {
                plugin.getLogger().info("装备槽守卫：已关闭的类别 → " + off
                        + "（不锁格子、不限购、不拦截拾取）。");
            }
        }
        if (plugin.getTacZApi() == null) {
            plugin.getLogger().warning("装备槽守卫：TacZSpigotBridge 不可用，"
                    + "TACZ 枪械将无法分类（手枪/步枪都不会被限购）。");
        } else if (shopPrices != null && shopPrices.gunCount() == 0) {
            plugin.getLogger().warning("装备槽守卫：没有从 shop.yml 读到任何枪械价格，"
                    + "替换旧武器时将无法退款。");
        }
    }

    @Override
    public void onDisable() {
        this.active = false;
        if (lockTask != null) {
            lockTask.cancel();
            lockTask = null;
        }
        HandlerList.unregisterAll(this);
        purchaseCounts.clear();
        lastSpend.clear();
        preGive.clear();
        scheduledNormalize.clear();
        messageCooldown.clear();
        gunCategoryCache.clear();
        lastSneak.clear();
        lastSwap.clear();
        plugin.getLogger().info("装备槽守卫已卸载。");
    }

    // ------------------------------------------------------------------
    // 配置
    // ------------------------------------------------------------------

    private void loadConfig() {
        String base = "modules.loadout-guard.";

        this.primarySlot = plugin.getConfig().getInt(base + "slots.primary", 0);
        this.secondarySlot = plugin.getConfig().getInt(base + "slots.secondary", 1);
        this.meleeSlot = plugin.getConfig().getInt(base + "slots.melee", 2);
        this.throwableSlot = plugin.getConfig().getInt(base + "slots.throwable", 3);
        if (!validSlots()) {
            plugin.getLogger().warning("装备槽守卫：slots 配置不是 4 个互不相同的 0-8 槽位，"
                    + "已回退到 1/2/3/4 号格。");
            this.primarySlot = 0;
            this.secondarySlot = 1;
            this.meleeSlot = 2;
            this.throwableSlot = 3;
        }

        this.primaryLimit = Math.max(0, plugin.getConfig().getInt(base + "limits.primary", 1));
        this.secondaryLimit = Math.max(0, plugin.getConfig().getInt(base + "limits.secondary", 1));
        this.meleeLimit = Math.max(0, plugin.getConfig().getInt(base + "limits.melee", 1));
        this.throwableLimit = Math.max(0, plugin.getConfig().getInt(base + "limits.throwable", 4));
        if (primaryLimit != 1 || secondaryLimit != 1 || meleeLimit != 1) {
            plugin.getLogger().warning("装备槽守卫：主武器 / 副武器 / 近战固定各 1 把，"
                    + "limits.primary/secondary/melee 只用于展示，不会放宽限制。");
        }

        this.refundOldWeapon = plugin.getConfig().getBoolean(base + "refund-old-weapon", true);
        this.meleeAlwaysSwap = plugin.getConfig().getBoolean(base + "melee-always-swap", true);
        this.tagItems = plugin.getConfig().getBoolean(base + "tag-items", true);
        this.dropDuplicates = plugin.getConfig().getBoolean(base + "drop-duplicates", true);
        this.purchaseSafety = plugin.getConfig().getBoolean(base + "purchase-safety", true);
        this.protectOpenItem = plugin.getConfig().getBoolean(base + "protect-open-item", true);
        this.openKey = OpenKey.parse(plugin.getConfig().getString(base + "open-key", "double-swap"));
        this.debug = plugin.getConfig().getBoolean(base + "debug", false);
        this.lockIntervalTicks = Math.max(1L,
                plugin.getConfig().getLong(base + "lock-interval-ticks", 10L));
        this.sameGunPolicy = SameGunPolicy.parse(
                plugin.getConfig().getString(base + "same-gun-policy", "block"));

        this.taczGunMaterialName = plugin.getConfig().getString(
                base + "tacz-gun-material", "TACZ_MODERN_KINETIC_GUN");

        loadTypeCategories(base);
        loadGunOverrides(base);
        loadMaterials(base);
    }

    /** 启动日志用的一句话槽位摘要，关掉的类别直接标出来。 */
    private String slotSummary() {
        StringBuilder sb = new StringBuilder();
        appendSlotSummary(sb, Category.PRIMARY, primarySlot);
        appendSlotSummary(sb, Category.SECONDARY, secondarySlot);
        appendSlotSummary(sb, Category.MELEE, meleeSlot);
        appendSlotSummary(sb, Category.THROWABLE, throwableSlot);
        return sb.toString();
    }

    private void appendSlotSummary(StringBuilder sb, Category category, int slot) {
        if (sb.length() > 0) sb.append("，");
        sb.append(category.displayName).append("→第 ").append(slot + 1).append(" 格");
        if (!enabled(category)) sb.append("（已关闭）");
    }

    private boolean validSlots() {
        int[] slots = {primarySlot, secondarySlot, meleeSlot, throwableSlot};
        Set<Integer> seen = new HashSet<>();
        for (int slot : slots) {
            if (slot < 0 || slot > 8) return false;
            if (!seen.add(slot)) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 分类对照表（item-categories.yml）
    // ------------------------------------------------------------------

    /**
     * 载入服主可编辑的分类对照表。
     *
     * <p>它是武器分类的<b>最高优先级</b>：写在表里的物品按表走，
     * 没写的才继续按 PDC 标签 / TacZ 类型 / 材质推断。
     * 文件不存在时按当前 config.yml 的旧配置生成一份，保持行为不变。
     */
    private void loadCategoryTable() {
        File file = new File(plugin.getDataFolder(), "item-categories.yml");
        try {
            if (!file.exists()) {
                writeCategoryTemplate(file);
            }
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            readCategorySection(yaml, "guns", tableGuns);
            readCategorySection(yaml, "names", tableNames);
            readCategorySection(yaml, "materials", tableMaterials);
            readCategorySection(yaml, "types", tableTypes);
            if (!yaml.isConfigurationSection("categories")) {
                appendCategoryRuleBlock(file);
            }
            loadCategoryRules(yaml);
            plugin.getLogger().info("装备分类对照表已载入：枪械 " + tableGuns.size()
                    + " / 名称 " + tableNames.size()
                    + " / 材质 " + tableMaterials.size()
                    + " / 类型 " + tableTypes.size() + " 条。");
        } catch (Throwable t) {
            tableGuns.clear();
            tableNames.clear();
            tableMaterials.clear();
            tableTypes.clear();
            plugin.getLogger().warning("载入 item-categories.yml 失败，退回 config.yml 的分类规则：" + t);
        }
    }

    /**
     * 读取 `categories:` —— 每个类别是否启用、占哪一格、上限多少。
     *
     * <p>没写的字段沿用 config.yml 里的 `slots` / `limits`，所以老配置照样能用。
     */
    private void loadCategoryRules(YamlConfiguration yaml) {
        enabledCategories.clear();
        replaceOnBuy.clear();
        sameGunModes.clear();
        refundOldFlags.clear();
        for (Category category : new Category[]{Category.PRIMARY, Category.SECONDARY,
                Category.MELEE, Category.THROWABLE, Category.ATTACHMENT, Category.UTILITY}) {
            enabledCategories.put(category, true);
            replaceOnBuy.put(category, true);
            sameGunModes.put(category, SameGunMode.INHERIT);
            refundOldFlags.put(category, refundOldWeapon);
        }
        // 刀不要钱：默认直接换掉手里那把，也不退旧刀
        if (meleeAlwaysSwap) {
            sameGunModes.put(Category.MELEE, SameGunMode.REPLACE);
            refundOldFlags.put(Category.MELEE, false);
        }

        ConfigurationSection root = yaml.getConfigurationSection("categories");
        if (root == null) return;

        applyCategoryRule(root, "primary", Category.PRIMARY);
        applyCategoryRule(root, "secondary", Category.SECONDARY);
        applyCategoryRule(root, "melee", Category.MELEE);
        applyCategoryRule(root, "throwable", Category.THROWABLE);
        applyCategoryRule(root, "attachment", Category.ATTACHMENT);
        applyCategoryRule(root, "utility", Category.UTILITY);

        parseUtilitySlots(root);

        if (!validSlots()) {
            plugin.getLogger().warning("item-categories.yml 的 categories.*.slot 有重复或越界，"
                    + "已回退到 config.yml 的 slots 配置。");
            String base = "modules.loadout-guard.";
            this.primarySlot = plugin.getConfig().getInt(base + "slots.primary", 0);
            this.secondarySlot = plugin.getConfig().getInt(base + "slots.secondary", 1);
            this.meleeSlot = plugin.getConfig().getInt(base + "slots.melee", 2);
            this.throwableSlot = plugin.getConfig().getInt(base + "slots.throwable", 3);
        }
    }

    /**
     * 功能道具的落点。支持两种写法：
     * <pre>
     *   utility:
     *     slots: [5, 6]     # 0 基，5/6 = 第 6、7 格
     *     slot: 5           # 或者只写一个
     * </pre>
     * 不写就默认第 6、7 格，至少不会去抢前四格（武器和投掷物的位置）。
     */
    private String utilitySlotSummary() {
        StringBuilder sb = new StringBuilder();
        for (int slot : utilitySlots) {
            if (sb.length() > 0) sb.append(" / ");
            sb.append("第 ").append(slot + 1).append(" 格");
        }
        return sb.length() == 0 ? "未设置" : sb.toString();
    }

    private void parseUtilitySlots(ConfigurationSection root) {
        utilitySlots.clear();
        ConfigurationSection section = root.getConfigurationSection("utility");
        if (section != null) {
            if (section.isList("slots")) {
                for (int slot : section.getIntegerList("slots")) {
                    if (slot >= 0 && slot <= 8 && !utilitySlots.contains(slot)) {
                        utilitySlots.add(slot);
                    }
                }
            } else if (section.isSet("slot")) {
                int slot = section.getInt("slot", -1);
                if (slot >= 0 && slot <= 8) {
                    utilitySlots.add(slot);
                }
            }
        }
        if (utilitySlots.isEmpty()) {
            utilitySlots.add(5);
            utilitySlots.add(6);
        }
    }

    private void applyCategoryRule(ConfigurationSection root, String key, Category category) {
        ConfigurationSection section = root.getConfigurationSection(key);
        if (section == null) return;

        if (section.isSet("enabled")) {
            enabledCategories.put(category, section.getBoolean("enabled", true));
        }
        if (section.isSet("slot")) {
            int slot = section.getInt("slot", -1);
            if (slot >= 0 && slot <= 8) {
                switch (category) {
                    case PRIMARY -> primarySlot = slot;
                    case SECONDARY -> secondarySlot = slot;
                    case MELEE -> meleeSlot = slot;
                    case THROWABLE -> throwableSlot = slot;
                    default -> {
                    }
                }
            } else {
                plugin.getLogger().warning("item-categories.yml 里 categories." + key
                        + ".slot 必须是 0-8，已忽略。");
            }
        }
        if (section.isSet("on-buy")) {
            String raw = String.valueOf(section.getString("on-buy", "replace")).trim();
            replaceOnBuy.put(category, !"block".equalsIgnoreCase(raw) && !"off".equalsIgnoreCase(raw));
        }
        if (section.isSet("same-gun")) {
            String raw = String.valueOf(section.getString("same-gun", "inherit")).trim();
            sameGunModes.put(category, switch (raw.toLowerCase(Locale.ROOT)) {
                case "replace", "swap" -> SameGunMode.REPLACE;
                case "block", "deny" -> SameGunMode.BLOCK;
                default -> SameGunMode.INHERIT;
            });
        }
        if (section.isSet("refund-old")) {
            refundOldFlags.put(category, section.getBoolean("refund-old", true));
        }
        if (section.isSet("limit")) {
            int limit = Math.max(0, section.getInt("limit", 1));
            switch (category) {
                case PRIMARY -> primaryLimit = limit;
                case SECONDARY -> secondaryLimit = limit;
                case MELEE -> meleeLimit = limit;
                case THROWABLE -> throwableLimit = limit;
                default -> {
                }
            }
        }
    }

    /** 老文件没有 categories: 段落时补一段默认值，方便服主直接改。 */
    private void appendCategoryRuleBlock(File file) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append("# ============================================================\n");
        sb.append("# 各类别开关与落点\n");
        sb.append("#\n");
        sb.append("# enabled=false 表示这个类别完全不管理：不锁格子、不限购、不拦截拾取。\n");
        sb.append("# slot 是落点（0-8 为快捷栏，四项必须互不相同，也不要和 WBD 的 c4-slot 撞车）。\n");
        sb.append("# limit 只对 throwable 有意义（投掷物总颗数，0 = 不限）；\n");
        sb.append("#       主/副/近战固定各 1 把，写大了也不会放宽。\n");
        sb.append("# 不写或删掉这一段，就沿用 config.yml 里的 slots / limits。\n");
        sb.append("# ============================================================\n");
        sb.append("categories:\n");
        sb.append("  primary:\n    enabled: true\n    slot: ").append(primarySlot)
                .append("\n    on-buy: replace\n    same-gun: inherit\n    refund-old: true\n");
        sb.append("  secondary:\n    enabled: true\n    slot: ").append(secondarySlot)
                .append("\n    on-buy: replace\n    same-gun: inherit\n    refund-old: true\n");
        sb.append("  melee:\n    enabled: true\n    slot: ").append(meleeSlot)
                .append("\n    on-buy: replace\n    same-gun: replace\n    refund-old: false\n");
        sb.append("  throwable:\n    enabled: true\n    slot: ").append(throwableSlot)
                .append("\n    limit: ").append(Math.max(0, throwableLimit)).append("\n");
        sb.append("  attachment:\n    enabled: true\n    # 配件不进快捷栏，统一放背包 9-35 格\n");
        sb.append("  utility:\n    enabled: true\n")
                .append("    # 功能道具（拆弹器、医疗包…）只准待这几格，0 基：5/6 = 第 6、7 格\n")
                .append("    slots: [").append(utilitySlots.get(0));
        for (int i = 1; i < utilitySlots.size(); i++) {
            sb.append(", ").append(utilitySlots.get(i));
        }
        sb.append("]\n");

        try {
            java.nio.file.Files.writeString(file.toPath(), sb.toString(),
                    java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.APPEND);
            plugin.getLogger().info("已为 item-categories.yml 补上 categories 段落（类别开关与落点）。");
        } catch (Throwable t) {
            plugin.getLogger().warning("追加 categories 段落失败：" + t.getMessage());
        }
    }

    /** 该类别是否启用。 */
    private boolean enabled(Category category) {
        return enabledCategories.getOrDefault(category, Boolean.TRUE);
    }

    private void readCategorySection(YamlConfiguration yaml, String section,
                                     Map<String, Category> target) {
        target.clear();
        ConfigurationSection cs = yaml.getConfigurationSection(section);
        if (cs == null) return;
        for (String key : cs.getKeys(false)) {
            Category category = Category.parseTable(cs.getString(key));
            if (category == null) {
                plugin.getLogger().warning("item-categories.yml 里 " + section + "." + key
                        + " 的类别写法无法识别，已忽略。");
                continue;
            }
            target.put(key.trim().toLowerCase(Locale.ROOT), category);
        }
    }

    /** 按当前 config.yml 的旧配置生成对照表，保证升级后行为不变。 */
    private void writeCategoryTemplate(File file) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ============================================================\n");
        sb.append("# 装备分类对照表\n");
        sb.append("#\n");
        sb.append("# 决定每件物品属于哪一类，直接决定它能占哪个格子、能不能再买同类。\n");
        sb.append("# 可选值：primary（主武器）/ secondary（副武器）/ melee（近战）/\n");
        sb.append("#         throwable（投掷物）/ utility（功能道具，不限购）/ none（不纳入管理）\n");
        sb.append("#\n");
        sb.append("# 这张表优先级最高：写了就按表走，没写的才按物品标签 / TacZ 类型 / 材质推断。\n");
        sb.append("# 改完执行 /wbdaddon reload 生效。\n");
        sb.append("# ============================================================\n");
        sb.append("\n");
        sb.append("# ============================================================\n");
        sb.append("# 各类别开关与落点\n");
        sb.append("#   enabled=false 表示这个类别完全不管理：不锁格子、不限购、不拦截拾取\n");
        sb.append("#   slot 是落点（0-8 为快捷栏，四项必须互不相同，别和 WBD 的 c4-slot 撞车）\n");
        sb.append("#   limit 只对 throwable 有意义（0 = 不限）\n");
        sb.append("# ============================================================\n");
        sb.append("categories:\n");
        sb.append("  primary:\n    enabled: true\n    slot: ").append(primarySlot)
                .append("\n    on-buy: replace\n    same-gun: inherit\n    refund-old: true\n");
        sb.append("  secondary:\n    enabled: true\n    slot: ").append(secondarySlot)
                .append("\n    on-buy: replace\n    same-gun: inherit\n    refund-old: true\n");
        sb.append("  melee:\n    enabled: true\n    slot: ").append(meleeSlot)
                .append("\n    on-buy: replace\n    same-gun: replace\n    refund-old: false\n");
        sb.append("  throwable:\n    enabled: true\n    slot: ").append(throwableSlot)
                .append("\n    limit: ").append(Math.max(0, throwableLimit)).append("\n");
        sb.append("  attachment:\n    enabled: true\n    # 配件不进快捷栏，统一放背包 9-35 格\n");
        sb.append("  utility:\n    enabled: true\n")
                .append("    # 功能道具（拆弹器、医疗包…）只准待这几格，0 基：5/6 = 第 6、7 格\n")
                .append("    slots: [").append(utilitySlots.get(0));
        for (int i = 1; i < utilitySlots.size(); i++) {
            sb.append(", ").append(utilitySlots.get(i));
        }
        sb.append("]\n");
        sb.append("\n");
        sb.append("# 按 TacZ GunId 指定（大小写不敏感，可省略命名空间 tacz:）\n");
        sb.append("guns:\n");
        for (Map.Entry<String, Category> e : gunOverrides.entrySet()) {
            sb.append("  \"").append(e.getKey()).append("\": ")
                    .append(e.getValue().key()).append("\n");
        }
        sb.append("\n");
        sb.append("# 按物品显示名指定（去掉颜色码后比较），适合非 TacZ 物品\n");
        sb.append("names: {}\n");
        sb.append("\n");
        sb.append("# 按 Bukkit 材质指定\n");
        sb.append("materials:\n");
        appendMaterialEntries(sb, throwableMaterials, "throwable");
        appendMaterialEntries(sb, meleeMaterials, "melee");
        appendMaterialEntries(sb, utilityMaterials, "utility");
        sb.append("\n");
        sb.append("# 按 TacZ index 里的 type 兜底\n");
        sb.append("types:\n");
        for (Map.Entry<String, Category> e : typeCategories.entrySet()) {
            sb.append("  ").append(e.getKey()).append(": ")
                    .append(e.getValue().key()).append("\n");
        }

        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            java.nio.file.Files.writeString(file.toPath(), sb.toString(),
                    java.nio.charset.StandardCharsets.UTF_8);
            plugin.getLogger().info("已生成装备分类对照表模板：" + file.getName());
        } catch (Throwable t) {
            plugin.getLogger().warning("生成 item-categories.yml 失败：" + t.getMessage());
        }
    }

    private void appendMaterialEntries(StringBuilder sb, Set<Material> materials, String category) {
        for (Material material : materials) {
            sb.append("  ").append(material.name()).append(": ").append(category).append("\n");
        }
    }

    private void loadTypeCategories(String base) {
        typeCategories.clear();
        // 先铺一层默认值，再让配置覆盖/追加。
        typeCategories.put("pistol", Category.SECONDARY);
        typeCategories.put("rifle", Category.PRIMARY);
        typeCategories.put("smg", Category.PRIMARY);
        typeCategories.put("shotgun", Category.PRIMARY);
        typeCategories.put("sniper", Category.PRIMARY);
        typeCategories.put("mg", Category.PRIMARY);
        typeCategories.put("rpg", Category.PRIMARY);
        typeCategories.put("melee", Category.MELEE);

        var section = plugin.getConfig()
                .getConfigurationSection(base + "tacz-type-categories");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            Category category = Category.parse(section.getString(key));
            if (category == null) {
                plugin.getLogger().warning("装备槽守卫：tacz-type-categories." + key
                        + " 的类别无效，已忽略。");
                continue;
            }
            typeCategories.put(key.toLowerCase(Locale.ROOT), category);
        }
    }

    private void loadGunOverrides(String base) {
        gunOverrides.clear();
        var section = plugin.getConfig().getConfigurationSection(base + "gun-overrides");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            Category category = Category.parse(section.getString(key));
            if (category == null) {
                plugin.getLogger().warning("装备槽守卫：gun-overrides." + key
                        + " 的类别无效，已忽略。");
                continue;
            }
            gunOverrides.put(key.toLowerCase(Locale.ROOT), category);
        }
    }

    private void loadMaterials(String base) {
        throwableMaterials.clear();
        meleeMaterials.clear();
        utilityMaterials.clear();

        readMaterials(base + "throwable-materials",
                List.of("LRTACTICAL_THROWABLE"), throwableMaterials);
        readMaterials(base + "melee-materials",
                List.of("LRTACTICAL_MELEE"), meleeMaterials);
        readMaterials(base + "utility-materials",
                List.of("LRTACTICAL_CONSUMABLE", "TACZ_AMMO", "SHEARS", "POTION"),
                utilityMaterials);

        backpackMaterials.clear();
        readMaterials(base + "backpack-materials",
                List.of("TACZ_ATTACHMENT"), backpackMaterials);

        attachmentMaterials.clear();
        readMaterials(base + "attachment-materials",
                List.of("TACZ_ATTACHMENT"), attachmentMaterials);
    }

    private void readMaterials(String path, List<String> defaults, Set<Material> target) {
        List<String> names = plugin.getConfig().getStringList(path);
        if (names.isEmpty()) names = defaults;
        for (String raw : names) {
            if (raw == null || raw.isBlank()) continue;
            Material material = Material.matchMaterial(raw.trim().toUpperCase(Locale.ROOT));
            if (material == null) {
                if (debug) {
                    plugin.getLogger().warning("装备槽守卫：" + path + " 里的材质 "
                            + raw + " 在当前服务端不存在，已忽略。");
                }
                continue;
            }
            target.add(material);
        }
    }

    private void warnSlotConflicts() {
        try {
            var settings = plugin.getWbd().getConfigManager().getSettings();
            if (settings != null && settings.c4Slot >= 0
                    && isConfiguredSlot(settings.c4Slot)) {
                plugin.getLogger().warning("装备槽守卫：配置的装备槽位与 WBD 的 c4-slot="
                        + settings.c4Slot + " 冲突，请把 c4-slot 改成别的槽位。");
            }
        } catch (Throwable ignored) {
            // WBD 版本差异，不影响主功能
        }
        try {
            int openSlot = plugin.getWbd().getShopManager().getOpenItemSlot();
            if (isConfiguredSlot(openSlot)) {
                plugin.getLogger().warning("装备槽守卫：配置的装备槽位与 WBD 商店开启物品槽位="
                        + openSlot + " 冲突，请调整 shop.open-item.slot。");
            }
        } catch (Throwable ignored) {
            // 同上
        }
        try {
            for (ShopItem item : plugin.getWbd().getShopManager().getItems()) {
                if (!"defuse_kit".equals(item.getId())) continue;
                int slot = item.getGiveSlot();
                if (isConfiguredSlot(slot)) {
                    plugin.getLogger().warning("装备槽守卫：拆弹器的 give-slot="
                            + slot + " 与装备槽位冲突。");
                }
            }
        } catch (Throwable ignored) {
            // 同上
        }
        try {
            for (ShopItem item : plugin.getWbd().getShopManager().getItems()) {
                ItemStack give = item.getGiveStack();
                if (give == null || give.getType().isAir()) continue;
                Category category = classify(give);
                int categorySlot = slotOf(category);
                if (categorySlot >= 0 && item.getGiveSlot() == categorySlot) {
                    plugin.getLogger().warning("装备槽守卫：商品 " + item.getId()
                            + " 的 give-slot=" + categorySlot
                            + " 与它的装备槽相同。WBD 会直接覆盖旧武器，"
                            + "替换退款可能失效；建议把该商品改成 give-slot: -1。");
                }
            }
        } catch (Throwable ignored) {
            // 同上
        }
    }

    private boolean isConfiguredSlot(int slot) {
        return slot == primarySlot || slot == secondarySlot
                || slot == meleeSlot || slot == throwableSlot;
    }

    // ------------------------------------------------------------------
    // 分类
    // ------------------------------------------------------------------

    /**
     * 判定物品类别。
     *
     * <p>优先级（先命中先用）：
     * <ol>
     *   <li>{@code item-categories.yml} 的 <b>materials</b> —— 服主显式配置，最直观</li>
     *   <li>WBD 自带的 C4 / 拆弹器标记 → 功能道具</li>
     *   <li>枪械：{@code item-categories.yml} 的 <b>guns</b> → 物品标签 → TacZ type</li>
     *   <li>{@code item-categories.yml} 的 <b>names</b> → 物品标签</li>
     * </ol>
     */
    private Category classify(ItemStack item) {
        if (item == null || item.getType().isAir()) return Category.NONE;

        // 1. 材质对照表：最直观，也最便宜
        Category byMaterial = tableMaterials.get(item.getType().name().toLowerCase(Locale.ROOT));
        if (byMaterial != null) return byMaterial;

        // 2. WBD 自己的标记
        WarZBombDefusePlugin wbd = plugin.getWbd();
        if (wbd != null) {
            String marker = ItemUtil.getMarker(item, wbd);
            if ("c4".equals(marker) || "defuse_kit".equals(marker)) {
                return Category.UTILITY;
            }
        }

        Material type = item.getType();
        if (attachmentMaterials.contains(type)) return Category.ATTACHMENT;
        Category legacyMaterial = legacyMaterialCategory(type);
        if (legacyMaterial != null) return legacyMaterial;

        // 3. 枪械
        if (type.name().equals(taczGunMaterialName)) {
            TacZSpigotApi api = plugin.getTacZApi();
            if (api == null) {
                warnOnce("no-tacz-api");
                return Category.NONE;
            }
            String gunId;
            try {
                gunId = api.getItemGunId(item).orElse(null);
            } catch (Throwable t) {
                warnOnce("no-tacz-api");
                return Category.NONE;
            }
            if (gunId == null) return Category.NONE;

            Category byGun = tableGuns.get(gunId.toLowerCase(Locale.ROOT));
            if (byGun == null && gunId.contains(":")) {
                byGun = tableGuns.get(gunId.substring(gunId.indexOf(':') + 1)
                        .toLowerCase(Locale.ROOT));
            }
            if (byGun != null) return byGun;

            Category tagged = readTag(item);
            if (tagged != null) return tagged;

            Category cached = gunCategoryCache.get(gunId);
            if (cached != null) return cached;

            Category resolved = resolveGunCategory(api, gunId);
            gunCategoryCache.put(gunId, resolved);
            return resolved;
        }

        // 4. 显示名对照表（非 TacZ 物品、自定义名字的东西）
        String name = displayName(item);
        if (!name.isEmpty()) {
            Category byName = tableNames.get(name.toLowerCase(Locale.ROOT));
            if (byName != null) return byName;
        }

        // 5. 物品标签兜底
        Category tagged = readTag(item);
        if (tagged != null) return tagged;

        return Category.NONE;
    }

    /** config.yml 里的旧材质列表（新表没写时继续生效，向下兼容）。 */
    private Category legacyMaterialCategory(Material type) {
        if (meleeMaterials.contains(type)) return Category.MELEE;
        if (throwableMaterials.contains(type)) return Category.THROWABLE;
        if (utilityMaterials.contains(type)) return Category.UTILITY;
        return null;
    }

    /** 读取物品上的 WBDAddon 分类标签；没有或无效返回 null。 */
    private Category readTag(ItemStack item) {
        if (!tagItems || item == null || item.getType().isAir()) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        String raw = meta.getPersistentDataContainer()
                .get(tagKey, PersistentDataType.STRING);
        if (raw == null) return null;
        try {
            return Category.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 给物品打分类标签，已经一致就不动它；返回是否真的改了。 */
    private boolean tagItem(ItemStack item, Category category) {
        if (!tagItems || item == null || item.getType().isAir()
                || category == null || category == Category.NONE) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        String want = category.name();
        String now = meta.getPersistentDataContainer()
                .get(tagKey, PersistentDataType.STRING);
        if (want.equals(now)) return false;
        meta.getPersistentDataContainer().set(tagKey, PersistentDataType.STRING, want);
        item.setItemMeta(meta);
        return true;
    }

    private boolean clearTag(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        if (!meta.getPersistentDataContainer().has(tagKey, PersistentDataType.STRING)) {
            return false;
        }
        meta.getPersistentDataContainer().remove(tagKey);
        item.setItemMeta(meta);
        return true;
    }

    // ------------------------------------------------------------------
    // 标签重建
    // ------------------------------------------------------------------

    /**
     * 清掉该玩家背包里的 WBDAddon 分类标签并重新分类。
     *
     * <p>标签是「认一次就固定」的，万一把某把枪认错了（比如先打了标签、
     * 之后才在 `gun-overrides` 里补规则），用它把标签洗掉重认。
     *
     * @return 清掉标签的物品数量
     */
    /** 给外部（比如 /wbdaddon shopscan）用的分类查询，返回 primary/secondary/... 的小写名。 */
    public String categoryKey(ItemStack stack) {
        return classify(stack).key();
    }

    public int retag(Player player) {
        PlayerInventory inventory = player.getInventory();
        int cleared = 0;
        for (int slot = 0; slot < STORAGE_SIZE; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (clearTag(item)) {
                setItemAt(inventory, slot, item);
                cleared++;
            }
        }
        ItemStack offhand = inventory.getItemInOffHand();
        if (clearTag(offhand)) {
            setItemAt(inventory, OFFHAND_SLOT, offhand);
            cleared++;
        }
        normalize(player);
        return cleared;
    }

    private Category resolveGunCategory(TacZSpigotApi api, String gunId) {
        Category override = gunOverrides.get(gunId.toLowerCase(Locale.ROOT));
        if (override != null) return override;

        String type;
        try {
            type = api.getGun(gunId).map(TacZGunSummary::type).orElse(null);
        } catch (Throwable t) {
            warnOnce("gun:" + gunId);
            return Category.PRIMARY;
        }
        if (type != null) {
            String key = type.toLowerCase(Locale.ROOT);
            Category mapped = tableTypes.get(key);
            if (mapped == null) {
                mapped = typeCategories.get(key);
            }
            if (mapped != null) return mapped;
        }

        warnOnce("gun:" + gunId);
        return Category.PRIMARY;
    }

    private String gunId(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        if (!item.getType().name().equals(taczGunMaterialName)) return null;
        TacZSpigotApi api = plugin.getTacZApi();
        if (api == null) return null;
        try {
            return api.getItemGunId(item).orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean sameItem(ItemStack a, ItemStack b) {
        if (a == null || b == null) return false;
        String idA = gunId(a);
        String idB = gunId(b);
        if (idA != null || idB != null) {
            return idA != null && idA.equals(idB);
        }
        if (a.getType() != b.getType()) return false;
        String nameA = displayName(a);
        String nameB = displayName(b);
        if (!nameA.isEmpty() && !nameB.isEmpty()) return nameA.equals(nameB);
        return true;
    }

    private boolean sameThrowableType(ItemStack a, ItemStack b) {
        if (a == null || b == null) return false;
        if (a.getType() != b.getType()) return false;
        String nameA = displayName(a);
        String nameB = displayName(b);
        if (!nameA.isEmpty() && !nameB.isEmpty()) return nameA.equals(nameB);
        return true;
    }

    private String displayName(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return "";
        var meta = item.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return "";
        return ChatColor.stripColor(meta.getDisplayName());
    }

    private String itemName(ItemStack item) {
        if (item == null || item.getType().isAir()) return "未知物品";
        String display = displayName(item);
        if (!display.isEmpty()) return display;
        String id = gunId(item);
        if (id != null) return WeaponNames.humanizeGunId(id);
        return item.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private void warnOnce(String key) {
        if (!warnedGuns.add(key)) return;
        if (key.startsWith("gun:")) {
            plugin.getLogger().warning("装备槽守卫：枪械 " + key.substring(4)
                    + " 不在 gun-overrides 里，且 TACZ type 也没有映射，"
                    + "已按主武器处理。请确认它是不是近战/特殊武器。");
        } else {
            plugin.getLogger().warning("装备槽守卫：无法读取 TACZ 枪械类型，"
                    + "请检查 TacZSpigotBridge 是否正常加载。");
        }
    }

    // ------------------------------------------------------------------
    // 槽位查找
    // ------------------------------------------------------------------

    private int slotOf(Category category) {
        return switch (category) {
            case PRIMARY -> primarySlot;
            case SECONDARY -> secondarySlot;
            case MELEE -> meleeSlot;
            case THROWABLE -> throwableSlot;
            default -> -1;
        };
    }

    private List<SlotItem> find(Player player, Category category) {
        List<SlotItem> result = new ArrayList<>();
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < STORAGE_SIZE; slot++) {
            ItemStack item = inventory.getItem(slot);
            Category actual = classify(item);
            if (actual == category) {
                if (tagItem(item, actual)) {
                    setItemAt(inventory, slot, item);
                }
                result.add(new SlotItem(slot, item));
            }
        }
        ItemStack offhand = inventory.getItemInOffHand();
        Category offhandCategory = classify(offhand);
        if (offhandCategory == category) {
            if (tagItem(offhand, offhandCategory)) {
                setItemAt(inventory, OFFHAND_SLOT, offhand);
            }
            result.add(new SlotItem(OFFHAND_SLOT, offhand));
        }
        return result;
    }

    private ItemStack itemAt(PlayerInventory inventory, int slot) {
        return slot == OFFHAND_SLOT ? inventory.getItemInOffHand() : inventory.getItem(slot);
    }

    private void setItemAt(PlayerInventory inventory, int slot, ItemStack item) {
        if (slot == OFFHAND_SLOT) {
            inventory.setItemInOffHand(item);
        } else {
            inventory.setItem(slot, item);
        }
    }

    private int findEmptyStorageSlot(PlayerInventory inventory, int excludeA, int excludeB) {
        for (int slot = 0; slot < STORAGE_SIZE; slot++) {
            if (slot == excludeA || slot == excludeB) continue;
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType().isAir()) return slot;
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // 归位 / 去重
    // ------------------------------------------------------------------

    private void tickAll() {
        if (!active) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isManaged(player)) {
                normalize(player);
            }
        }
    }

    private void normalize(Player player) {
        if (!active || !isManaged(player)) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        // 先清掉占着预留格子、又不属于那里的东西，再让各分类归位
        enforceReservedSlots(player, inventory);
        if (enabled(Category.PRIMARY)) {
            normalizeCategory(player, inventory, Category.PRIMARY, primarySlot);
        }
        if (enabled(Category.SECONDARY)) {
            normalizeCategory(player, inventory, Category.SECONDARY, secondarySlot);
        }
        if (enabled(Category.MELEE)) {
            normalizeCategory(player, inventory, Category.MELEE, meleeSlot);
        }
        if (enabled(Category.THROWABLE)) {
            normalizeThrowables(player, inventory);
        }
        if (enabled(Category.UTILITY)) {
            normalizeUtility(player, inventory);
        }
        moveToBackpack(player, inventory);
        ensureOpenItem(player);
    }

    private void normalizeCategory(Player player, PlayerInventory inventory,
                                   Category category, int targetSlot) {
        List<SlotItem> items = find(player, category);
        if (items.isEmpty()) return;

        int keepSlot = -1;
        for (SlotItem slotItem : items) {
            if (slotItem.slot == targetSlot) {
                keepSlot = slotItem.slot;
                break;
            }
        }
        if (keepSlot < 0) keepSlot = items.get(0).slot;

        for (SlotItem slotItem : items) {
            if (slotItem.slot == keepSlot) continue;
            if (!dropDuplicates) {
                // 排查模式：不删也不丢，保留现场，方便看看到底是哪一步多出来的
                continue;
            }
            setItemAt(inventory, slotItem.slot, null);
            dropAtFeet(player, slotItem.item);
            if (allowMessage(player, "duplicate", 3000L)) {
                msg(player, "duplicate-dropped", Map.of(
                        "category", category.displayName,
                        "item", itemName(slotItem.item)));
            }
            if (debug) {
                plugin.getLogger().info("[loadout] " + player.getName()
                        + " 多出的 " + category.displayName + " 已丢弃："
                        + itemName(slotItem.item));
            }
        }

        ItemStack keep = itemAt(inventory, keepSlot);
        if (keep != null && keepSlot != targetSlot) {
            moveItem(player, keep, keepSlot, targetSlot);
        }
    }

    private void normalizeThrowables(Player player, PlayerInventory inventory) {
        if (throwableLimit > 0) {
            int total = countThrowables(player);
            if (total > throwableLimit) {
                int excess = total - throwableLimit;
                int removed = removeThrowableAmount(player, null, excess, true);
                if (removed > 0 && allowMessage(player, "throwable-excess", 3000L)) {
                    msg(player, "throwable-excess", Map.of(
                            "limit", String.valueOf(throwableLimit),
                            "amount", String.valueOf(removed)));
                }
            }
        }

        List<SlotItem> stacks = find(player, Category.THROWABLE);
        if (stacks.isEmpty()) return;

        boolean targetHasThrowable = stacks.stream()
                .anyMatch(slotItem -> slotItem.slot == throwableSlot);
        if (!targetHasThrowable) {
            SlotItem first = stacks.get(0);
            moveItem(player, first.item, first.slot, throwableSlot);
        }
    }

    private void moveItem(Player player, ItemStack item, int fromSlot, int targetSlot) {
        if (item == null || item.getType().isAir() || fromSlot == targetSlot) return;
        PlayerInventory inventory = player.getInventory();

        ItemStack occupant = itemAt(inventory, targetSlot);
        if (occupant != null && !occupant.getType().isAir()) {
            int empty = findEmptyStorageSlot(inventory, targetSlot, fromSlot);
            if (empty >= 0) {
                setItemAt(inventory, empty, occupant);
            } else {
                dropAtFeet(player, occupant);
                if (debug) {
                    plugin.getLogger().info("[loadout] " + player.getName()
                            + " 背包已满，槽位 " + targetSlot
                            + " 的 " + itemName(occupant) + " 已丢到脚下。");
                }
            }
        }

        setItemAt(inventory, fromSlot, null);
        setItemAt(inventory, targetSlot, item);
        sync(player);
    }

    @SuppressWarnings("deprecation")
    private void sync(Player player) {
        try {
            player.updateInventory();
        } catch (Throwable ignored) {
            // 某些混合端（Arclight）对 updateInventory 的实现可能有差异，失败不影响下次刷新
        }
    }

    private void dropAtFeet(Player player, ItemStack item) {
        if (item == null || item.getType().isAir()) return;
        Location location = player.getLocation();
        Item drop = player.getWorld().dropItemNaturally(location, item.clone());
        drop.setPickupDelay(40);
        try {
            drop.setOwner(player.getUniqueId());
        } catch (Throwable ignored) {
            // 老版本/混合端没有 setOwner 时忽略
        }
    }

    private int countThrowables(Player player) {
        int total = 0;
        for (SlotItem slotItem : find(player, Category.THROWABLE)) {
            total += Math.max(0, slotItem.item.getAmount());
        }
        return total;
    }

    /**
     * 从背包里扣除指定数量的投掷物。
     *
     * @param template    要扣的类型；null 表示任意投掷物
     * @param amount      要扣的数量
     * @param dropRemoved true = 扣下来的物品丢到脚下；false = 直接删除（用于购买超限退款）
     * @return 实际扣掉的数量
     */
    private int removeThrowableAmount(Player player, ItemStack template,
                                      int amount, boolean dropRemoved) {
        if (amount <= 0) return 0;

        int remaining = amount;
        // 两轮：第一轮只扣同类型，第二轮再兜底扣任意投掷物。
        // 每轮都重新扫描背包，避免拿着“扣之前”的 ItemStack 重复扣同一格。
        for (int pass = 0; pass < 2 && remaining > 0; pass++) {
            List<SlotItem> stacks = find(player, Category.THROWABLE);
            // 先从背包扣，再动第 4 格
            stacks.sort(Comparator.comparingInt(slotItem ->
                    slotItem.slot == throwableSlot ? 1 : 0));
            for (SlotItem slotItem : stacks) {
                if (remaining <= 0) break;
                if (pass == 0 && template != null
                        && !sameThrowableType(slotItem.item, template)) {
                    continue;
                }
                int take = Math.min(remaining, slotItem.item.getAmount());
                applyRemove(player, player.getInventory(), slotItem, take, dropRemoved);
                remaining -= take;
            }
        }

        return amount - remaining;
    }

    private void applyRemove(Player player, PlayerInventory inventory,
                             SlotItem slotItem, int take, boolean dropRemoved) {
        if (take <= 0) return;
        int left = slotItem.item.getAmount() - take;
        if (left <= 0) {
            setItemAt(inventory, slotItem.slot, null);
        } else {
            ItemStack copy = slotItem.item.clone();
            copy.setAmount(left);
            setItemAt(inventory, slotItem.slot, copy);
        }
        if (dropRemoved) {
            ItemStack drop = slotItem.item.clone();
            drop.setAmount(take);
            dropAtFeet(player, drop);
        }
        sync(player);
    }

    // ------------------------------------------------------------------
    // 购买
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMoneyChange(WbdMoneyChangeEvent event) {
        if (event.getDelta() >= 0) return;
        Player player = event.getPlayer();
        if (player == null) return;
        lastSpend.put(player.getUniqueId(),
                new Spend(-event.getDelta(), System.currentTimeMillis()));
        if (purchaseSafety) {
            // 扣钱发生在 WBD「发物品」之前，这一瞬间的背包就是判定基准
            preGive.put(player.getUniqueId(),
                    new InventoryBefore(snapshotContents(player), System.currentTimeMillis()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        ShopManager shop = shopManager();
        if (shop == null || !shop.isShopInventory(event.getView().getTopInventory())) {
            return;
        }
        reloadPricesIfChanged();
        // 先归位再快照：保证购买判定时旧武器已经在固定槽里，
        // 同款枪的“旧/新”不会因为旧枪在背包里而认反。
        normalize(player);
        scheduleNormalize(player);
        snapshotCounts(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        ShopManager shop = shopManager();
        if (shop != null && shop.isShopInventory(event.getView().getTopInventory())) {
            handleShopClick(player, event, shop);
            return;
        }

        if (isManaged(player)) {
            scheduleNormalize(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && isManaged(player)) {
            scheduleNormalize(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        ShopManager shop = shopManager();
        if (shop != null && shop.isShopInventory(event.getView().getTopInventory())) {
            scheduleNormalize(player);
        }
    }

    private void handleShopClick(Player player, InventoryClickEvent event, ShopManager shop) {
        int rawSlot = event.getRawSlot();
        var top = event.getView().getTopInventory();
        if (rawSlot < 0 || rawSlot >= top.getSize()) return;

        ItemStack clicked = top.getItem(rawSlot);
        if (clicked == null || clicked.getType().isAir()) return;

        WarZBombDefusePlugin wbd = plugin.getWbd();
        if (wbd == null) return;
        String marker = ItemUtil.getMarker(clicked, wbd);
        if (!marker.startsWith(SHOP_MARKER_PREFIX)) return;

        String itemId = marker.substring(SHOP_MARKER_PREFIX.length());
        ShopItem shopItem = findShopItem(shop, itemId, rawSlot, player);
        if (shopItem == null) return;

        ArenaSession session = session(player);
        if (session == null) return;

        // 正常情况下打开商店时已经快照过。这里用 computeIfAbsent 兜底：
        // 即使缓存被回合重置清掉，也能按“本轮从 0 开始”判断第一次购买。
        Map<String, Integer> counts = purchaseCounts.computeIfAbsent(
                player.getUniqueId(), ignored -> new HashMap<>());

        int before = counts.getOrDefault(itemId, 0);
        int after = session.getShopPurchaseCount(player, itemId);
        if (after < before) {
            // 回合重置后购买计数会归零；把缓存一起纠正，避免第一次购买被漏掉
            before = 0;
        }
        if (after <= before) {
            // 钱不够 / 不在购买时间 / 不在购买区 / 达到限购，WBD 会直接拒绝
            return;
        }
        counts.put(itemId, after);

        // 付费商品必须看到同 tick 的真实扣钱记录；否则可能是 WBD 拒绝购买、
        // 或者购买计数因重载被误读，绝不能因此给玩家退款。
        int price = shopItem.getPrice();
        if (price > 0 && !hasRecentSpend(player, price)) {
            return;
        }

        ItemStack give = shopItem.getGiveStack();
        if (give == null || give.getType().isAir()) {
            scheduleNormalize(player);
            return;
        }

        // 先确认东西真的进包了：WBD 对 addItem 的返回值「无动于衷」，
        // 背包满时物品直接蒸发、钱照扣；配了 give-slot 的还会直接覆盖那一格。
        if (recoverLostPurchase(player, session, shopItem, give)) {
            scheduleNormalize(player);
            return;
        }

        Category category = classify(give);
        if (category == Category.NONE || category == Category.UTILITY) {
            scheduleNormalize(player);
            return;
        }

        // WBD 的 handleClick 已经在 LOWEST 阶段完成扣钱/发物品，这里同步处理即可。
        // 不用下一 tick：避免玩家在 50ms 内死亡/离场导致购买处理不到。
        if (!isManaged(player)) return;
        handlePurchase(player, session, shopItem, give, category);
        normalize(player);
    }

    /**
     * 购买后核对「东西真的到手了吗」。
     *
     * <p>WBD 的发放逻辑有两条路，两条都可能把东西吞掉：
     *
     * <ul>
     *   <li>{@code give-slot >= 0}：直接 {@code setItem(slot, stack)}，
     *       那一格原本的东西被顶掉，凭空消失；</li>
     *   <li>{@code give-slot < 0}：{@code addItem(...)}，背包全满时返回的
     *       剩余堆叠被直接丢弃，物品蒸发。</li>
     * </ul>
     *
     * <p>两种情况下钱都已经扣过了。这里用扣钱瞬间的快照对比，把损失补回来：
     * 新物品没进包 → 按比例退款；旧格子被顶掉 → 把旧物品还回去。
     *
     * @return 已经处理（退款 / 归还）返回 true，调用方不要再走同类替换逻辑
     */
    private boolean recoverLostPurchase(Player player, ArenaSession session,
                                        ShopItem shopItem, ItemStack give) {
        if (!purchaseSafety) return false;

        InventoryBefore before = preGive.remove(player.getUniqueId());
        if (before == null || System.currentTimeMillis() - before.at() > 500L) {
            return false;
        }
        ItemStack[] was = before.contents();
        ItemStack[] now = snapshotContents(player);

        int expected = Math.max(1, give.getAmount());
        int gained = amountOf(now, give) - amountOf(was, give);

        // 落点格子：WBD 会直接覆盖，被覆盖掉的旧物品要还给玩家
        int slot = shopItem.getGiveSlot();
        if (slot >= 0 && slot < was.length) {
            ItemStack old = was[slot];
            if (old != null && !old.getType().isAir() && !sameShopItem(old, give)) {
                int oldBefore = amountOf(was, old);
                int oldAfter = amountOf(now, old);
                if (oldAfter < oldBefore) {
                    for (int i = 0; i < oldBefore - oldAfter; i++) {
                        restoreStack(player, old);
                    }
                    msg(player, "slot-restored", Map.of("item", itemName(old)));
                    if (debug) {
                        plugin.getLogger().info("[loadout] " + player.getName()
                                + " 的落点格被购买覆盖，已归还 " + itemName(old));
                    }
                }
            }
        }

        if (gained >= expected) {
            return false;
        }

        int missing = expected - Math.max(0, gained);
        int price = shopItem.getPrice();
        int refundAmount = price <= 0 ? 0
                : Math.max(1, (int) Math.round(price * (double) missing / expected));
        if (refundAmount > 0) {
            refund(session, player, refundAmount);
            msg(player, "purchase-missing", Map.of("price", String.valueOf(refundAmount)));
        } else {
            msg(player, "purchase-missing-free", Map.of());
        }
        if (debug) {
            plugin.getLogger().warning("[loadout] " + player.getName() + " 购买 "
                    + itemName(give) + " 没有进背包（期望 " + expected + "，实得 "
                    + Math.max(0, gained) + "），退款 " + refundAmount + "。");
        }
        return true;
    }

    /** 背包存储区快照（元素逐个 clone，避免后续改物品把快照也带偏）。 */
    private ItemStack[] snapshotContents(Player player) {
        ItemStack[] contents = player.getInventory().getStorageContents();
        ItemStack[] copy = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            copy[i] = contents[i] == null ? null : contents[i].clone();
        }
        return copy;
    }

    /** 统计某件商品在背包里一共有多少个（按类型 + 显示名，忽略 PDC 标记）。 */
    private int amountOf(ItemStack[] contents, ItemStack sample) {
        int total = 0;
        for (ItemStack stack : contents) {
            if (stack != null && sameShopItem(stack, sample)) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    /**
     * 商店物品的「同一件」判断。
     *
     * <p>不用 {@link ItemStack#isSimilar}：WBD 发拆弹器时会顺手写一个
     * {@code item_marker} PDC，和快照里的同款物品就差这一点，isSimilar 会判成不同。
     */
    private boolean sameShopItem(ItemStack a, ItemStack b) {
        if (a == null || b == null || a.getType() != b.getType()) return false;
        String nameA = a.hasItemMeta() ? a.getItemMeta().getDisplayName() : "";
        String nameB = b.hasItemMeta() ? b.getItemMeta().getDisplayName() : "";
        return Objects.equals(nameA, nameB);
    }

    /** 放回背包，放不下就丢在脚下（总比凭空消失好）。 */
    private void restoreStack(Player player, ItemStack stack) {
        ItemStack copy = stack.clone();
        Map<Integer, ItemStack> left = player.getInventory().addItem(copy);
        for (ItemStack drop : left.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), drop);
        }
    }

    private ShopItem findShopItem(ShopManager shop, String itemId, int slot, Player player) {
        ArenaSession session = session(player);
        Team team = session == null ? null : session.getTeam(player);
        ShopItem fallback = null;
        for (ShopItem item : shop.getItems()) {
            if (!itemId.equals(item.getId())) continue;
            if (item.getSlot() != slot) continue;
            if (item.getTeam() != null && item.getTeam() != team) continue;
            if (item.getTeam() != null) return item;
            fallback = item;
        }
        return fallback;
    }

    private void handlePurchase(Player player, ArenaSession session, ShopItem shopItem,
                                ItemStack give, Category category) {
        if (!enabled(category)) {
            // 这个类别被关掉了：不替换、不退款、不归位，交还给 WBD 原生行为
            return;
        }
        switch (category) {
            case PRIMARY, SECONDARY, MELEE ->
                    handleWeaponPurchase(player, session, shopItem, give, category);
            case THROWABLE ->
                    handleThrowablePurchase(player, session, shopItem, give);
            default -> {
                // 功能道具不限购，只做槽位归位
            }
        }
    }

    /**
     * 从购买后的同类物品里区分“旧武器”和“刚买的新武器”。
     *
     * <p>这里刻意不依赖任何缓存，只看背包现状：
     * <ol>
     *   <li>只有 1 把且就是刚买的枪 → 旧武器已不在，按“无旧武器”处理；</li>
     *   <li>只有 1 把但不是刚买的枪 → 新物品没进背包（背包满），按“新物品缺失”处理；</li>
     *   <li>2 把 → 用 GunId/材质+显示名认出刚买的那把，另一把就是旧武器；</li>
     *   <li>2 把同款 → 固定槽里的当旧武器，另一把当新武器；</li>
     *   <li>3 把以上 → 只挑一对处理，多余的交给 normalize 丢掉。</li>
     * </ol>
     */
    private PurchaseItems resolvePurchaseItems(List<SlotItem> items, ItemStack give,
                                               int targetSlot) {
        if (items.isEmpty()) return new PurchaseItems(null, null);

        if (items.size() == 1) {
            SlotItem only = items.get(0);
            return sameItem(only.item, give)
                    ? new PurchaseItems(null, only)
                    : new PurchaseItems(only, null);
        }

        if (items.size() == 2) {
            SlotItem a = items.get(0);
            SlotItem b = items.get(1);
            boolean aMatch = sameItem(a.item, give);
            boolean bMatch = sameItem(b.item, give);

            if (aMatch && bMatch) {
                // 同款枪：固定槽里的那把是旧武器
                if (a.slot == targetSlot) return new PurchaseItems(a, b);
                if (b.slot == targetSlot) return new PurchaseItems(b, a);
                return new PurchaseItems(a, b);
            }
            if (aMatch) return new PurchaseItems(b, a);
            if (bMatch) return new PurchaseItems(a, b);

            // 两把都不是刚买的枪：说明新物品没进背包，旧武器还在
            SlotItem old = a.slot == targetSlot ? a : (b.slot == targetSlot ? b : a);
            return new PurchaseItems(old, null);
        }

        // 3 把以上：先找刚买的那把；找不到就说明新物品没进背包。
        SlotItem target = items.stream()
                .filter(slotItem -> slotItem.slot == targetSlot)
                .findFirst().orElse(null);
        List<SlotItem> matches = items.stream()
                .filter(slotItem -> sameItem(slotItem.item, give))
                .toList();

        if (matches.size() >= 2) {
            // 同款多把：固定槽里那把是旧武器，另一把是同款的新/多余物品
            SlotItem old = target != null && sameItem(target.item, give)
                    ? target
                    : matches.get(0);
            SlotItem newItem = matches.stream()
                    .filter(slotItem -> slotItem.slot != old.slot)
                    .findFirst().orElse(null);
            return new PurchaseItems(old, newItem);
        }

        if (matches.size() == 1) {
            SlotItem newItem = matches.get(0);
            SlotItem old = target != null && target.slot != newItem.slot
                    ? target
                    : items.stream()
                    .filter(slotItem -> slotItem.slot != newItem.slot)
                    .findFirst().orElse(null);
            return new PurchaseItems(old, newItem);
        }

        SlotItem old = target != null ? target : items.get(0);
        return new PurchaseItems(old, null);
    }

    private void handleWeaponPurchase(Player player, ArenaSession session, ShopItem shopItem,
                                      ItemStack give, Category category) {
        PlayerInventory inventory = player.getInventory();
        int targetSlot = slotOf(category);
        int price = shopItem.getPrice();

        List<SlotItem> items = find(player, category);
        PurchaseItems resolved = resolvePurchaseItems(items, give, targetSlot);
        SlotItem oldItem = resolved.oldItem();
        SlotItem newItem = resolved.newItem();

        if (newItem == null) {
            // 背包满了 / WBD 发了但没进背包：把这次购买的钱退回去，旧武器保留
            refund(session, player, price);
            msg(player, "purchase-missing", Map.of("price", String.valueOf(price)));
            if (debug) {
                plugin.getLogger().warning("[loadout] " + player.getName()
                        + " 购买了 " + itemName(give) + "，但背包里找不到新物品，已退款 "
                        + price + "。");
            }
            return;
        }

        if (oldItem == null) {
            // 本来就没有同类武器：直接归位
            moveItem(player, newItem.item, newItem.slot, targetSlot);
            return;
        }

        boolean same = sameItem(oldItem.item, newItem.item);

        // 同一把枪：先看这个类别的 same-gun 策略，没配就沿用全局 same-gun-policy
        SameGunMode mode = sameGunModes.getOrDefault(category, SameGunMode.INHERIT);
        SameGunPolicy policy = switch (mode) {
            case BLOCK -> SameGunPolicy.BLOCK;
            case REPLACE -> SameGunPolicy.REPLACE_NO_REFUND;
            case INHERIT -> sameGunPolicy;
        };

        if (same && policy == SameGunPolicy.BLOCK) {
            setItemAt(inventory, newItem.slot, null);
            if (price > 0) {
                refund(session, player, price);
                msg(player, "same-gun-blocked", Map.of(
                        "gun", itemName(newItem.item),
                        "price", String.valueOf(price)));
            } else {
                msg(player, "same-gun-blocked-free", Map.of(
                        "gun", itemName(newItem.item)));
            }
            if (debug) {
                plugin.getLogger().info("[loadout] " + player.getName()
                        + " 重复购买 " + itemName(newItem.item) + "，已取消并退款。");
            }
            return;
        }

        // 不同枪但同类：这个类别配了「不顶掉」就拒绝购买
        if (!same && !replaceOnBuy.getOrDefault(category, Boolean.TRUE)) {
            setItemAt(inventory, newItem.slot, null);
            String oldName = itemName(oldItem.item);
            if (price > 0) {
                refund(session, player, price);
                msg(player, "buy-blocked", Map.of(
                        "old", oldName,
                        "new", itemName(newItem.item),
                        "price", String.valueOf(price)));
            } else {
                msg(player, "buy-blocked-free", Map.of(
                        "old", oldName,
                        "new", itemName(newItem.item)));
            }
            if (debug) {
                plugin.getLogger().info("[loadout] " + player.getName() + " 已装备 "
                        + oldName + "，按类别策略取消购买 " + itemName(newItem.item));
            }
            return;
        }

        int refund = 0;
        boolean refundOld = refundOldFlags.getOrDefault(category, refundOldWeapon);
        if (refundOld && policy != SameGunPolicy.REPLACE_NO_REFUND) {
            refund = refundPrice(oldItem.item);
        }

        String oldName = itemName(oldItem.item);
        String newName = itemName(newItem.item);
        setItemAt(inventory, oldItem.slot, null);
        if (refund > 0) {
            refund(session, player, refund);
        }
        moveItem(player, newItem.item, newItem.slot, targetSlot);

        if (category == Category.MELEE) {
            msg(player, "melee-swapped", Map.of("old", oldName, "new", newName));
        } else if (refund > 0) {
            msg(player, "replaced", Map.of(
                    "old", oldName,
                    "new", newName,
                    "refund", String.valueOf(refund)));
        } else {
            msg(player, "replaced-no-refund", Map.of(
                    "old", oldName,
                    "new", newName));
        }
        if (debug) {
            plugin.getLogger().info("[loadout] " + player.getName() + " 替换 "
                    + oldName + " -> " + newName + "，退款 " + refund + "。");
        }
    }

    private void handleThrowablePurchase(Player player, ArenaSession session,
                                         ShopItem shopItem, ItemStack give) {
        if (throwableLimit > 0) {
            int total = countThrowables(player);
            int over = total - throwableLimit;
            if (over > 0) {
                int amount = Math.max(1, give.getAmount());
                int remove = Math.min(over, amount);
                int removed = removeThrowableAmount(player, give, remove, false);
                int refund = removed >= amount
                        ? shopItem.getPrice()
                        : (int) Math.floor(shopItem.getPrice() * (double) removed / amount);
                if (refund > 0) {
                    refund(session, player, refund);
                    msg(player, "throwable-full", Map.of(
                            "limit", String.valueOf(throwableLimit),
                            "refund", String.valueOf(refund)));
                } else {
                    msg(player, "throwable-full-no-refund", Map.of(
                            "limit", String.valueOf(throwableLimit)));
                }
                if (debug) {
                    plugin.getLogger().info("[loadout] " + player.getName()
                            + " 投掷物超限，移除 " + removed + "，退款 " + refund + "。");
                }
            }
        }
        normalizeThrowables(player, player.getInventory());
    }

    private int refundPrice(ItemStack oldItem) {
        if (oldItem == null || shopPrices == null) return 0;
        try {
            String id = gunId(oldItem);
            if (id != null) {
                // TACZ 枪只认 GunId 的精确价格，绝不回退材质：
                // 所有枪共用 TACZ_MODERN_KINETIC_GUN，回退会把别的枪价格套上来。
                return Math.max(0, shopPrices.gunPriceOf(id));
            }
            if (oldItem.getType().name().equals(taczGunMaterialName)) {
                // 是 TACZ 枪却拿不到 GunId：宁可退款 0，也不要套错价格
                return 0;
            }
            // 非 TACZ 物品（LRTactical、原版道具）才允许按材质/显示名查价
            return shopPrices.priceOf(oldItem);
        } catch (Throwable t) {
            if (debug) {
                plugin.getLogger().warning("[loadout] 查询旧武器价格失败："
                        + itemName(oldItem) + " - " + t);
            }
            return 0;
        }
    }

    private boolean refund(ArenaSession session, Player player, int amount) {
        if (amount <= 0) return false;
        try {
            boolean ok = session.addMoney(player, amount);
            if (!ok && debug) {
                plugin.getLogger().warning("[loadout] " + player.getName()
                        + " 退款 " + amount + " 失败（可能已达上限）。");
            }
            return ok;
        } catch (Throwable t) {
            plugin.getLogger().warning("装备槽守卫：给 " + player.getName()
                    + " 退款 " + amount + " 时出错：" + t);
            return false;
        }
    }

    private boolean hasRecentSpend(Player player, int price) {
        Spend spend = lastSpend.get(player.getUniqueId());
        return spend != null
                && spend.amount() == price
                && System.currentTimeMillis() - spend.at() <= 500L;
    }

    private void snapshotAllArenaCounts() {
        WarZBombDefusePlugin wbd = plugin.getWbd();
        if (wbd == null || wbd.getArenaManager() == null) return;
        for (ArenaSession session : wbd.getArenaManager().getSessions()) {
            for (PlayerRecord record : session.getPlayers()) {
                Player player = Bukkit.getPlayer(record.getUuid());
                if (player != null && player.isOnline()) {
                    normalize(player);
                    snapshotCounts(player);
                }
            }
        }
    }

    private void snapshotCounts(Player player) {
        ArenaSession session = session(player);
        ShopManager shop = shopManager();
        if (session == null || shop == null) return;

        Map<String, Integer> counts = new HashMap<>();
        for (ShopItem item : shop.getItems()) {
            counts.put(item.getId(),
                    session.getShopPurchaseCount(player, item.getId()));
        }
        purchaseCounts.put(player.getUniqueId(), counts);
    }

    // ------------------------------------------------------------------
    // 拾取
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!isManaged(player)) return;

        ItemStack item = event.getItem().getItemStack();
        if (item == null || item.getType().isAir()) return;

        Category category = classify(item);
        if (category != Category.UTILITY && category != Category.NONE && !enabled(category)) {
            scheduleNormalize(player);
            return;
        }
        switch (category) {
            case PRIMARY, SECONDARY, MELEE -> {
                if (!find(player, category).isEmpty()) {
                    event.setCancelled(true);
                    if (allowMessage(player, "pickup-" + category, 1500L)) {
                        msg(player, "pickup-blocked", Map.of(
                                "category", category.displayName));
                    }
                    return;
                }
                scheduleNormalize(player);
            }
            case THROWABLE -> {
                if (throwableLimit > 0
                        && countThrowables(player) + Math.max(0, item.getAmount()) > throwableLimit) {
                    event.setCancelled(true);
                    if (allowMessage(player, "pickup-throwable", 1500L)) {
                        msg(player, "throwable-pickup-blocked", Map.of(
                                "limit", String.valueOf(throwableLimit)));
                    }
                    return;
                }
                scheduleNormalize(player);
            }
            default -> {
                // 功能道具不限制
            }
        }
    }

    // ------------------------------------------------------------------
    // 其它生命周期
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoinArena(WbdPlayerJoinArenaEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;
        scheduleNormalize(player);
        snapshotCounts(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRoundStart(WbdRoundStartEvent event) {
        // TACZ 枪包可能在两回合之间 reload，清掉类型缓存重新解析
        gunCategoryCache.clear();
        String arenaId = event.getArenaId();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (inArena(player, arenaId)) {
                purchaseCounts.remove(player.getUniqueId());
            }
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!active) return;
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!inArena(player, arenaId)) continue;
                normalize(player);
                snapshotCounts(player);
            }
        });
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        scheduleNormalize(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (protectOpenItem && isOpenItem(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            msg(player, "open-item-locked", Map.of());
            return;
        }
        if (!isManaged(player)) return;
        // 丢弃后下一 tick 再归位；购买判定本身只看背包现状，不依赖缓存
        scheduleNormalize(player);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();

        // 快捷键优先：双击 F（Shift + F 预留给 /wbda 菜单，这里不碰）
        if (openKey == OpenKey.DOUBLE_SWAP && doubleTapped(player)
                && openShopByKey(player)) {
            event.setCancelled(true);
            return;
        }

        // 绿宝石换到副手 = 把它藏起来，直接拦
        if (protectOpenItem
                && (isOpenItem(event.getMainHandItem()) || isOpenItem(event.getOffHandItem()))) {
            event.setCancelled(true);
            return;
        }

        boolean bySwapKey = switch (openKey) {
            case SWAP_HANDS -> true;
            case SNEAK_SWAP_HANDS -> player.isSneaking();
            default -> false;
        };
        if (bySwapKey && openShopByKey(player)) {
            event.setCancelled(true);
            return;
        }

        if (isManaged(player)) {
            scheduleNormalize(player);
        }
    }

    /**
     * 450ms 内又按了一次「交换副手」。
     *
     * <p>单按仍然是原版换手，只有连按两下才当快捷键——F 本身平时很少按，
     * 比连按 Shift 那种“蹲走时手滑两下”可靠得多。
     */
    private boolean doubleTapped(Player player) {
        long now = System.currentTimeMillis();
        Long last = lastSwap.put(player.getUniqueId(), now);
        return last != null && now - last <= 450L;
    }

    /** 双击潜行（Shift）开商店。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onToggleSneak(PlayerToggleSneakEvent event) {
        if (openKey != OpenKey.DOUBLE_SNEAK || !event.isSneaking()) return;
        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        Long last = lastSneak.put(player.getUniqueId(), now);
        if (last != null && now - last <= 350L && openShopByKey(player)) {
            lastSneak.remove(player.getUniqueId());
        }
    }

    // ------------------------------------------------------------------
    // 商店开物品
    // ------------------------------------------------------------------

    /** 是不是 WBD 的商店开物品（绿宝石）。 */
    private boolean isOpenItem(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return false;
        WarZBombDefusePlugin wbd = plugin.getWbd();
        return wbd != null && ItemUtil.hasMarker(stack, wbd, "shop_open");
    }

    /**
     * 用按键打开商店。
     *
     * <p>只对「正在比赛、还活着、处于冻结或进行中」的玩家生效，
     * 免得在大厅或观战按 F 也弹一个商店出来。
     */
    private boolean openShopByKey(Player player) {
        if (!active) return false;
        if (player.getOpenInventory().getType() != org.bukkit.event.inventory.InventoryType.CRAFTING) {
            return false;
        }
        ArenaSession session = session(player);
        if (session == null) return false;
        PlayerRecord record = session.getRecord(player);
        if (record == null || !record.isAlive()) return false;
        GameState state = session.getState();
        if (state != GameState.FREEZE && state != GameState.LIVE) return false;
        ShopManager shop = shopManager();
        if (shop == null) return false;
        try {
            shop.open(player, session);
            return true;
        } catch (Throwable t) {
            if (debug) {
                plugin.getLogger().warning("[loadout] 按键开商店失败：" + t);
            }
            return false;
        }
    }

    /**
     * 保证绿宝石一直待在固定格子里。
     *
     * <p>被拖走、被购买顶掉、被别的插件清掉，都会在下一秒补回来；
     * 优先把背包里现有的那颗挪回原位，实在没有才新建一颗，避免复制。
     */
    /**
     * 功能道具归位：只准待在 utilitySlots（默认第 6、7 格）。
     *
     * <p>买拆弹器/医疗包时 WBD 只保证「塞进背包」，实际会落在第一个空格，
     * 经常是第 1 格，把主武器挤走。这里把落在别处（尤其前四格）的功能道具
     * 挪到 6/7；6/7 都被占了就退回背包，实在没地方就留在原地——
     * 优先级：别丢东西 &gt; 别抢前四格。
     */
    private void normalizeUtility(Player player, PlayerInventory inventory) {
        if (utilitySlots.isEmpty()) return;
        List<SlotItem> items = find(player, Category.UTILITY);
        if (items.isEmpty()) return;

        Set<Integer> allowed = new HashSet<>(utilitySlots);
        boolean moved = false;
        for (SlotItem item : items) {
            if (allowed.contains(item.slot())) continue;
            // C4 的槽位归 WBD 管（c4-slot），别去动它
            if (isC4(item.item())) continue;

            Integer target = null;
            for (int slot : utilitySlots) {
                ItemStack occupant = itemAt(inventory, slot);
                if (occupant == null || occupant.getType().isAir()) {
                    target = slot;
                    break;
                }
            }
            if (target == null) {
                // 6/7 都占了：退到背包，别占着前四格
                for (int slot = 9; slot < STORAGE_SIZE; slot++) {
                    ItemStack occupant = itemAt(inventory, slot);
                    if (occupant == null || occupant.getType().isAir()) {
                        target = slot;
                        break;
                    }
                }
            }
            if (target == null) continue;

            setItemAt(inventory, target, item.item());
            setItemAt(inventory, item.slot(), null);
            moved = true;
        }
        if (moved) {
            sync(player);
        }
    }

    /**
     * 预留槽位不许被「没管理的物品」占用。
     *
     * <p>快捷栏位置是分好的：1-4 武器/投掷物、5 C4、6-7 功能道具、9 商店绿宝石。
     * 只要有别的东西（泥土、面包、别人掉的枪）占着这些格子，就把它挪到
     * 「没被预留」的格子里；没有空位再退到背包 9-35；实在没地方就原地不动。
     *
     * <p>顺序很重要：先清场，再让 {@code normalizeCategory} 把各自的物品摆回槽位。
     */
    private void enforceReservedSlots(Player player, PlayerInventory inventory) {
        Set<Integer> reserved = reservedSlots();
        if (reserved.isEmpty()) return;
        int openSlot = openItemSlot();

        boolean moved = false;
        for (int slot : reserved) {
            if (slot < 0 || slot > 8) continue;
            ItemStack stack = itemAt(inventory, slot);
            if (stack == null || stack.getType().isAir()) continue;
            if (slotAccepts(slot, stack)) continue;

            int target = -1;
            for (int candidate = 0; candidate < 9; candidate++) {
                if (reserved.contains(candidate) || candidate == openSlot) continue;
                ItemStack occupant = itemAt(inventory, candidate);
                if (occupant == null || occupant.getType().isAir()) {
                    target = candidate;
                    break;
                }
            }
            if (target < 0) {
                for (int candidate = 9; candidate < STORAGE_SIZE; candidate++) {
                    ItemStack occupant = itemAt(inventory, candidate);
                    if (occupant == null || occupant.getType().isAir()) {
                        target = candidate;
                        break;
                    }
                }
            }
            if (target < 0) {
                if (debug) {
                    plugin.getLogger().info("[loadout] " + player.getName()
                            + " 的第 " + (slot + 1) + " 格被 " + itemName(stack)
                            + " 占用，但找不到空位可挪");
                }
                continue;
            }
            setItemAt(inventory, target, stack);
            setItemAt(inventory, slot, null);
            moved = true;
            if (debug) {
                plugin.getLogger().info("[loadout] " + player.getName() + " 的 "
                        + itemName(stack) + " 从预留格 " + (slot + 1)
                        + " 挪到第 " + (target + 1) + " 格");
            }
        }
        if (moved) {
            sync(player);
        }
    }

    /** 当前被判为「预留」的快捷栏格子。 */
    private Set<Integer> reservedSlots() {
        Set<Integer> slots = new LinkedHashSet<>();
        if (enabled(Category.PRIMARY)) slots.add(primarySlot);
        if (enabled(Category.SECONDARY)) slots.add(secondarySlot);
        if (enabled(Category.MELEE)) slots.add(meleeSlot);
        if (enabled(Category.THROWABLE)) slots.add(throwableSlot);
        if (enabled(Category.UTILITY)) {
            slots.addAll(utilitySlots);
            int c4 = c4Slot();
            if (c4 >= 0) slots.add(c4);
        }
        slots.removeIf(slot -> slot < 0 || slot > 8);
        return slots;
    }

    /** 这个格子能不能放这件东西。 */
    private boolean slotAccepts(int slot, ItemStack stack) {
        Category actual = classify(stack);
        if (actual == Category.NONE || actual == Category.ATTACHMENT) {
            return false;
        }
        if (slot == primarySlot) return actual == Category.PRIMARY;
        if (slot == secondarySlot) return actual == Category.SECONDARY;
        if (slot == meleeSlot) return actual == Category.MELEE;
        if (slot == throwableSlot) return actual == Category.THROWABLE;
        if (utilitySlots.contains(slot)) {
            return actual == Category.UTILITY && !isC4(stack);
        }
        if (slot == c4Slot()) return isC4(stack);
        return false;
    }

    private int c4Slot() {
        try {
            var settings = plugin.getWbd().getConfigManager().getSettings();
            return settings == null ? -1 : settings.c4Slot;
        } catch (Throwable t) {
            return -1;
        }
    }

    private int openItemSlot() {
        try {
            return plugin.getWbd().getShopManager().getOpenItemSlot();
        } catch (Throwable t) {
            return -1;
        }
    }

    private boolean isC4(ItemStack item) {
        try {
            WarZBombDefusePlugin wbd = plugin.getWbd();
            return wbd != null && "c4".equals(ItemUtil.getMarker(item, wbd));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 把「不该占快捷栏」的东西挪到背包（9-35）。
     *
     * <p>WBD 发商品走的是 {@code addItem}，而 {@code addItem} 会<b>先填快捷栏</b>——
     * 买个配件结果占掉 1-9 格，手里正用的枪、投掷物就被挤走了。
     * 所以买完/捡到后统一把这类物品塞回背包。
     *
     * <p>背包满了就不动它（总比把东西弄丢强）。
     */
    private void moveToBackpack(Player player, PlayerInventory inventory) {
        if (!enabled(Category.ATTACHMENT)) return;
        if (backpackMaterials.isEmpty() && attachmentMaterials.isEmpty()) return;
        boolean moved = false;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.getType().isAir()) continue;
            if (!backpackMaterials.contains(stack.getType())
                    && !(attachmentMaterials.contains(stack.getType())
                    && classify(stack) == Category.ATTACHMENT)) {
                continue;
            }

            int empty = -1;
            for (int candidate = 9; candidate < STORAGE_SIZE; candidate++) {
                ItemStack occupied = inventory.getItem(candidate);
                if (occupied == null || occupied.getType().isAir()) {
                    empty = candidate;
                    break;
                }
            }
            if (empty < 0) continue;
            setItemAt(inventory, empty, stack);
            setItemAt(inventory, slot, null);
            moved = true;
            if (debug) {
                plugin.getLogger().info("[loadout] " + player.getName() + " 的 "
                        + itemName(stack) + " 已从快捷栏 " + slot + " 挪到背包 " + empty);
            }
        }
        if (moved) {
            sync(player);
        }
    }

    private void ensureOpenItem(Player player) {
        if (!protectOpenItem) return;
        ShopManager shop = shopManager();
        if (shop == null) return;
        int slot = shop.getOpenItemSlot();
        PlayerInventory inventory = player.getInventory();
        if (slot < 0 || slot >= inventory.getSize()) return;

        ArenaSession session = session(player);
        if (session == null) return;
        PlayerRecord record = session.getRecord(player);
        if (record == null || !record.isAlive()) return;
        GameState state = session.getState();
        if (state != GameState.FREEZE && state != GameState.LIVE) return;

        if (isOpenItem(inventory.getItem(slot))) return;

        for (int i = 0; i < inventory.getSize(); i++) {
            if (i == slot) continue;
            ItemStack stack = inventory.getItem(i);
            if (isOpenItem(stack)) {
                moveItem(player, stack, i, slot);
                return;
            }
        }
        inventory.setItem(slot, shop.createOpenItem());
        sync(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        purchaseCounts.remove(uuid);
        lastSpend.remove(uuid);
        preGive.remove(uuid);
        lastSneak.remove(uuid);
        lastSwap.remove(uuid);
        scheduledNormalize.remove(uuid);
        messageCooldown.keySet().removeIf(key -> key.startsWith(uuid + ":"));
    }

    private void scheduleNormalize(Player player) {
        if (!active || player == null || !player.isOnline()) return;
        UUID uuid = player.getUniqueId();
        if (!scheduledNormalize.add(uuid)) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            scheduledNormalize.remove(uuid);
            if (active && player.isOnline()) {
                normalize(player);
            }
        });
    }

    private boolean inArena(Player player, String arenaId) {
        ArenaSession session = session(player);
        return session != null && session.getConfig() != null
                && arenaId.equals(session.getConfig().getId());
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private boolean isManaged(Player player) {
        if (player == null || !player.isOnline()) return false;
        if (player.hasPermission(BYPASS_PERMISSION)) {
            if (debug) {
                plugin.getLogger().info("[loadout] " + player.getName()
                        + " 拥有 " + BYPASS_PERMISSION + "，跳过装备槽管理");
            }
            return false;
        }

        ArenaSession session = session(player);
        if (session == null) return false;
        if (session.isSpectator(player)) return false;

        PlayerRecord record = session.getRecord(player);
        return record != null && record.isAlive();
    }

    private ArenaSession session(Player player) {
        WarZBombDefusePlugin wbd = plugin.getWbd();
        if (wbd == null || wbd.getArenaManager() == null) return null;
        return wbd.getArenaManager().getSession(player);
    }

    private ShopManager shopManager() {
        WarZBombDefusePlugin wbd = plugin.getWbd();
        return wbd == null ? null : wbd.getShopManager();
    }

    private void reloadPricesIfChanged() {
        if (shopFile == null || shopPrices == null) return;
        long modified = shopFile.isFile() ? shopFile.lastModified() : -1L;
        if (modified == shopFileModified) return;
        shopFileModified = modified;
        shopPrices.load(shopFile, plugin.getTacZApi());
        if (debug) {
            plugin.getLogger().info("[loadout] 已重载商店价格缓存："
                    + shopPrices.gunCount() + " 把枪 / "
                    + shopPrices.materialCount() + " 种材质。");
        }
    }

    private boolean allowMessage(Player player, String key, long cooldownMs) {
        String mapKey = player.getUniqueId() + ":" + key;
        long now = System.currentTimeMillis();
        Long last = messageCooldown.get(mapKey);
        if (last != null && now - last < cooldownMs) return false;
        messageCooldown.put(mapKey, now);
        return true;
    }

    private void msg(Player player, String key, Map<String, String> placeholders) {
        String raw = plugin.getConfig().getString("modules.loadout-guard.messages." + key);
        if (raw == null || raw.isEmpty()) return;
        String text = raw;
        for (var entry : placeholders.entrySet()) {
            text = text.replace("%" + entry.getKey() + "%", entry.getValue());
        }
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', text));
    }

    // ------------------------------------------------------------------
    // 内部类型
    // ------------------------------------------------------------------

    private record SlotItem(int slot, ItemStack item) {
    }

    private record Spend(int amount, long at) {
    }

    /** 扣钱那一刻的背包存储区快照。 */
    private record InventoryBefore(ItemStack[] contents, long at) {
    }

    private record PurchaseItems(SlotItem oldItem, SlotItem newItem) {
    }

    /** 打开商店的按键。服务端只能感知「交换副手」，所以选项就这两个有意义。 */
    private enum OpenKey {
        /** 潜行 + 交换副手：F 单独按仍然是原来的换手，误触不了。 */
        SNEAK_SWAP_HANDS,
        /** 交换副手：按 F 直接开商店。 */
        SWAP_HANDS,
        /** 连按两下潜行（Shift）。 */
        DOUBLE_SNEAK,
        /** 连按两下交换副手（F）。 */
        DOUBLE_SWAP,
        /** 关掉按键，只用绿宝石右键 / /wbd buy。 */
        OFF;

        static OpenKey parse(String raw) {
            if (raw == null) return SNEAK_SWAP_HANDS;
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "swap-hands", "swap", "f" -> SWAP_HANDS;
                case "double-sneak", "sneak2", "double-shift" -> DOUBLE_SNEAK;
                case "double-swap", "swap2", "double-f" -> DOUBLE_SWAP;
                case "off", "none", "disabled", "false" -> OFF;
                default -> SNEAK_SWAP_HANDS;
            };
        }
    }

    /** 每个类别「买同一把枪」时的策略；inherit = 沿用全局 same-gun-policy。 */
    private enum SameGunMode {
        INHERIT,
        REPLACE,
        BLOCK
    }

    private enum SameGunPolicy {
        BLOCK,
        REPLACE_NO_REFUND,
        REPLACE_REFUND;

        static SameGunPolicy parse(String raw) {
            if (raw == null) return BLOCK;
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "replace-no-refund" -> REPLACE_NO_REFUND;
                case "replace-refund" -> REPLACE_REFUND;
                default -> BLOCK;
            };
        }
    }

    private enum Category {
        PRIMARY("主武器"),
        SECONDARY("副武器"),
        MELEE("近战武器"),
        THROWABLE("投掷物"),
        ATTACHMENT("配件"),
        UTILITY("功能道具"),
        NONE("物品");

        private final String displayName;

        Category(String displayName) {
            this.displayName = displayName;
        }

        /** 对照表用的写法：额外支持 none / ignore / skip 表示「不纳入管理」。 */
        static Category parseTable(String raw) {
            if (raw == null) return null;
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "none", "ignore", "skip", "off" -> NONE;
                default -> parse(raw);
            };
        }

        /** 写回对照表时的字符串。 */
        String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Category parse(String raw) {
            if (raw == null) return null;
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "primary" -> PRIMARY;
                case "secondary" -> SECONDARY;
                case "melee" -> MELEE;
                case "throwable" -> THROWABLE;
                case "attachment", "attach", "scope" -> ATTACHMENT;
                case "utility" -> UTILITY;
                case "none", "ignore" -> NONE;
                default -> null;
            };
        }
    }
}
