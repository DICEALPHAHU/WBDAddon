package alphahu.wbdaddon;

import alphahu.wbdaddon.api.WbdAddonApi;
import alphahu.wbdaddon.command.WBDAddonCommand;
import alphahu.wbdaddon.mode.ModeRegistry;
import alphahu.wbdaddon.module.ArenaDropsModule;
import alphahu.wbdaddon.module.ArenaHungerModule;
import alphahu.wbdaddon.module.BombDefuseSoundModule;
import alphahu.wbdaddon.module.JourneyMapModule;
import alphahu.wbdaddon.module.KillReportsModule;
import alphahu.wbdaddon.module.MatchStatusModule;
import alphahu.wbdaddon.module.ModuleManager;
import alphahu.wbdaddon.module.RoundEventsModule;
import alphahu.wbdaddon.module.TeamArmorModule;
import com.warz.bombdefuse.WarZBombDefusePlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import com.tacz.bridge.spigot.api.TacZSpigotApi;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * WBDAddon 主类。
 *
 * <p>这是 WarZBombDefuse 的第三方附加组件合集，本身不实现任何具体功能，
 * 只负责：
 * <ul>
 *   <li>加载 config.yml</li>
 *   <li>注册并启停各功能模块（KillReports、JourneyMap 等）</li>
 *   <li>持有 WarZBombDefuse 插件实例的引用，供各模块使用</li>
 *   <li>注册 /wbdaddon 主命令</li>
 *   <li>但它不会变成猫娘或者酒狐（恼）</li>
 * </ul>
 *
 * <p>所有具体功能都在 module 包下的模块里实现，主类不参与业务逻辑。
 *
 * @author AlphaHu
 */
public final class WBDAddon extends JavaPlugin {

    private static WBDAddon instance;

    // WarZBombDefuse 插件实例。plugin.yml 里声明了 depend，这里一定不为 null。
    // 没有 WarZBombDefuse，用这个插件又有何意义？
    private WarZBombDefusePlugin wbd;

    /** 模块管理器。 */
    private ModuleManager moduleManager;

    /**
     * 模式注册表，同时也是对外发布的 {@link WbdAddonApi} 实现。
     *
     * <p>它的存在让 WBDAddon 的各模块不再直接认识 WarZBombDefuse 的 ArenaSession：
     * 内置爆破模式与第三方模式（如团队死斗）都以 AddonMode 的形式注册进来，
     * 模块只问「这个玩家参赛了吗、还活着吗、属于哪一队」。
     */
    private ModeRegistry modeRegistry;

    /** TacZSpigotBridge API，TacZ 不可用时为 null。 */
    private TacZSpigotApi tacZApi;

    /** TacZSpigotBridge 是否可用。 */
    private boolean tacZAvailable = false;

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Override
    public void onEnable() {

        Bukkit.getConsoleSender().sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&b========================================"));
        Bukkit.getConsoleSender().sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&b  WBDAddon &ev" + pluginVersion()));
        Bukkit.getConsoleSender().sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&b  构建：&e" + buildStamp()));
        Bukkit.getConsoleSender().sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&b  作者：&dAlphaHu"));
        Bukkit.getConsoleSender().sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&b  对接 WarZBombDefuse，整合击杀报告与 JourneyMap 桥接"));
        Bukkit.getConsoleSender().sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&b  暴露 WbdAddonApi，其他模式可实现 AddonMode 复用本插件功能"));
        Bukkit.getConsoleSender().sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&b  未来或许会有更好的模块，可以在仓库中提交Pull Request"));
        Bukkit.getConsoleSender().sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&b  意见反馈QQ：&d2387629002"));
        Bukkit.getConsoleSender().sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&b  开源仓库：&egithub.com/DICEALPHAHU/WBDAddon"));
        Bukkit.getConsoleSender().sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&b========================================"));

        instance = this;

        // 1. 加载配置
        saveDefaultConfig();
        reloadConfig();

        // 2. 获取 WarZBombDefuse 实例
        //    plugin.yml 里写了 depend: [WarZBombDefuse]，
        //    Bukkit 保证在 onEnable 执行时 WBD 已经加载完成。
        this.wbd = (WarZBombDefusePlugin) Bukkit.getPluginManager().getPlugin("WarZBombDefuse");

        if (wbd == null) {
            // 理论上不会走到这里，作为防御性编程保留。
            getLogger().severe("无法获取 WarZBombDefuse 实例，插件将卸载。");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // 3. 尝试接入 TacZSpigotBridge（可选依赖）
        setupTacZ();

        // 4. 注册模块
        this.moduleManager = new ModuleManager(this);
        moduleManager.register(new KillReportsModule(this));
        moduleManager.register(new MatchStatusModule(this));
        moduleManager.register(new BombDefuseSoundModule(this));
        moduleManager.register(new ArenaHungerModule(this));
        moduleManager.register(new ArenaDropsModule(this));
        moduleManager.register(new TeamArmorModule(this));
        moduleManager.register(new RoundEventsModule(this));
        moduleManager.register(new JourneyMapModule(this));

        // 5. 启用所有模块
        moduleManager.enableAll();

        // 6. 建立模式注册表并对其他插件发布 API
        //    放在模块启用之后：API 的 getCombatStats() 要能取到 kill-reports 模块的 DamageTracker，
        //    模块还没启用时那个 getter 只会返回 null。
        //    顺序上，内置的爆破模式必须最先注册，玩家归属判定是先到先得。
        this.modeRegistry = new ModeRegistry(this);
        modeRegistry.registerBuiltIn();
        Bukkit.getServicesManager().register(
                WbdAddonApi.class, modeRegistry, this, ServicePriority.Normal);

        // 7. 注册命令
        WBDAddonCommand command = new WBDAddonCommand(this);
        var cmd = getCommand("wbdaddon");
        if (cmd != null) {
            cmd.setExecutor(command);
            cmd.setTabCompleter(command);
        }

        getLogger().info("WBDAddon 已加载，共启用 " + moduleManager.getEnabledModules().size() + " 个模块，"
                + "已注册 " + modeRegistry.getModes().size() + " 个游戏模式。");
        getLogger().info("已发布 WbdAddonApi v" + modeRegistry.getApiVersion()
                + "，第三方模式可实现 AddonMode 接入本插件的附加功能。");
    }

    @Override
    public void onDisable() {
        // 先把服务从 ServicesManager 撤下，这样第三方模式不会再拿到它；
        // 但**不在这时把字段置 null** —— 本插件自己的模块此刻还没停，
        // 它们可能还在跑最后一个 tick 并通过 getApi() 查询玩家归属，
        // 置 null 会让那些调用直接 NPE。撤下服务已经足以阻止外部继续使用。
        if (modeRegistry != null) {
            Bukkit.getServicesManager().unregister(WbdAddonApi.class, modeRegistry);
        }

        // 再禁用所有模块（取消任务、清理队伍、注销监听器）
        if (moduleManager != null) {
            moduleManager.disableAll();
        }

        modeRegistry = null;
        getLogger().info("WBDAddon 已卸载。");
    }

    // ------------------------------------------------------------------
    // 重载
    // ------------------------------------------------------------------

    /**
     * 重载配置并重新启停所有模块。
     * 由 /wbdaddon reload 命令调用。
     *
     * <p>只重启模块，不动模式注册表：第三方模式注册进来的 {@code AddonMode}
     * 属于别的插件，本插件既没有权限也没有理由在重载时把它们清掉。
     * 如果模式插件自己想跟着重载，它会自己调用 {@code registerMode} 覆盖旧实现。
     */
    public void reload() {
        reloadConfig();
        moduleManager.disableAll();
        moduleManager.enableAll();
        getLogger().info("配置已重载，模块已重新启停。");
    }

    /**
     * 读取 plugin.yml 里的版本号。
     * 新 API getPluginMeta() 仍标记为实验性，故这里沿用 getDescription()，
     * 并用抑制注解消除弃用警告（弃用但稳定的 API 比实验 API 更值得信赖）。
     */
    @SuppressWarnings("deprecation")
    private String pluginVersion() {
        return getDescription().getVersion();
    }

    /**
     * 读取打包时 git-commit-id 插件生成的 git.properties，
     * 在启动横幅里标明这份构建来自哪个提交，方便确认服务端上装的到底是哪一份。
     */
    private String buildStamp() {
        try (InputStream in = getResource("git.properties")) {
            if (in == null) {
                // 直接在 IDE 里跑 class 文件时不会有这个文件
                return "未知（非打包产物）";
            }
            Properties props = new Properties();
            props.load(in);
            return props.getProperty("git.commit.id.abbrev", "?")
                    + "  " + props.getProperty("git.build.time", "?");
        } catch (IOException e) {
            return "未知";
        }
    }

    // ------------------------------------------------------------------
    // TacZSpigotBridge 接入
    // ------------------------------------------------------------------

    /**
     * 尝试接入 TacZSpigotBridge。
     *
     * <p>RegisteredServiceProvider 的 provider 恒非 null，所以只要 rsp 存在，
     * tacZApi 就一定可用；真正要判断的是 TacZ 运行时是否存活。
     */
    private void setupTacZ() {
        RegisteredServiceProvider<TacZSpigotApi> rsp =
                Bukkit.getServicesManager().getRegistration(TacZSpigotApi.class);
        if (rsp != null) {
            tacZApi = rsp.getProvider();
            tacZAvailable = tacZApi.isAvailable();
        }

        if (tacZAvailable) {
            getLogger().info("已接入 TacZSpigotBridge，枪械击杀归属功能可用。");
        } else {
            getLogger().info("未检测到可用的 TacZ 运行时，枪械相关功能将降级处理。");
        }
    }

    // ------------------------------------------------------------------
    // 访问器
    // ------------------------------------------------------------------

    /** 供各模块及其他插件获取主类实例（预留的扩展入口）。 */
    @SuppressWarnings("unused")
    public static WBDAddon getInstance() {
        return instance;
    }

    /**
     * 获取 WarZBombDefuse 插件实例。
     * 各模块通过这个方法拿 WBD 的 ArenaManager、事件 API 等。
     */
    public WarZBombDefusePlugin getWbd() {
        return wbd;
    }

    public ModuleManager getModuleManager() {
        return moduleManager;
    }

    /**
     * 获取模式注册表（以对外接口 {@link WbdAddonApi} 的形式）。
     *
     * <p>本插件内部模块用它做模式无关的判定。
     *
     * <p>第三方插件请不要拿这个引用，而是通过 Bukkit 的 ServicesManager 取
     * {@link WbdAddonApi} —— 那样才不必把自己的类加载器钉死在 WBDAddon 上。
     */
    public WbdAddonApi getApi() {
        return modeRegistry;
    }

    public boolean isTacZAvailable() {
        return tacZAvailable;
    }

    /** TacZSpigotBridge API（预留的扩展入口，供后续模块使用）。 */
    @SuppressWarnings("unused")
    public TacZSpigotApi getTacZApi() {
        return tacZApi;
    }
}