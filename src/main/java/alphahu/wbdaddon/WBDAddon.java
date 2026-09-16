package alphahu.wbdaddon;

import alphahu.wbdaddon.command.WBDAddonCommand;
import alphahu.wbdaddon.module.JourneyMapModule;
import alphahu.wbdaddon.module.KillReportsModule;
import alphahu.wbdaddon.module.ArenaHungerModule;
import alphahu.wbdaddon.module.MatchStatusModule;
import alphahu.wbdaddon.module.ModuleManager;
import com.warz.bombdefuse.WarZBombDefusePlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import com.tacz.bridge.spigot.api.TacZSpigotApi;

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
                "&b  作者：&dAlphaHu"));
        Bukkit.getConsoleSender().sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&b  对接 WarZBombDefuse，整合击杀报告与 JourneyMap 桥接"));
        Bukkit.getConsoleSender().sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&b  未来或许会有更好的模块，可以在仓库中提交Pull Request"));
        Bukkit.getConsoleSender().sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&b  意见反馈QQ：&d2387629002"));
        Bukkit.getConsoleSender().sendMessage(ChatColor.translateAlternateColorCodes('&',
                "&b  开源仓库：&egithub.com/DICEALPAHU/WBDaddon"));
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
        moduleManager.register(new ArenaHungerModule(this));
        moduleManager.register(new JourneyMapModule(this));

        // 5. 启用所有模块
        moduleManager.enableAll();

        // 6. 注册命令
        WBDAddonCommand command = new WBDAddonCommand(this);
        var cmd = getCommand("wbdaddon");
        if (cmd != null) {
            cmd.setExecutor(command);
            cmd.setTabCompleter(command);
        }

        getLogger().info("WBDAddon 已加载，共启用 " + moduleManager.getEnabledModules().size() + " 个模块。");
    }

    @Override
    public void onDisable() {
        // 先禁用所有模块（取消任务、清理队伍、注销监听器）
        if (moduleManager != null) {
            moduleManager.disableAll();
        }

        getLogger().info("WBDAddon 已卸载。");
    }

    // ------------------------------------------------------------------
    // 重载
    // ------------------------------------------------------------------

    /**
     * 重载配置并重新启停所有模块。
     * 由 /wbdaddon reload 命令调用。
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

    public boolean isTacZAvailable() {
        return tacZAvailable;
    }

    /** TacZSpigotBridge API（预留的扩展入口，供后续模块使用）。 */
    @SuppressWarnings("unused")
    public TacZSpigotApi getTacZApi() {
        return tacZApi;
    }
}