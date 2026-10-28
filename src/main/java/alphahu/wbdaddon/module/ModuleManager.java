package alphahu.wbdaddon.module;

import alphahu.wbdaddon.WBDAddon;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 模块管理器。
 *
 * <p>负责按 config.yml 的配置启停各模块，并维护已启用模块列表。
 * 主类只跟这个管理器打交道，不直接操作单个模块。
 *
 * @author AlphaHu
 */
public class ModuleManager {

    private final WBDAddon plugin;

    /** 全部已注册的模块（不管是否启用）。 */
    private final List<AddonModule> modules = new ArrayList<>();

    /** 当前已启用的模块。 */
    private final List<AddonModule> enabledModules = new ArrayList<>();

    public ModuleManager(WBDAddon plugin) {
        this.plugin = plugin;
    }

    /**
     * 注册模块（只注册，不启用）。
     * 由主类 onEnable() 调用，注册顺序不影响启停逻辑。
     */
    public void register(AddonModule module) {
        modules.add(module);
    }

    /**
     * 按配置启用所有模块。
     * 每个模块读自己的 modules.<id>.enabled 值，缺省时用 isEnabledByDefault()。
     */
    public void enableAll() {
        enabledModules.clear();
        for (AddonModule module : modules) {
            boolean enabled = plugin.getConfig()
                    .getBoolean("modules." + module.getId() + ".enabled", module.isEnabledByDefault());
            if (enabled) {
                try {
                    module.onEnable();
                    if (!module.isAvailable()) {
                        plugin.getLogger().warning("模块未就绪，已跳过：" + module.getDisplayName());
                        continue;
                    }
                    enabledModules.add(module);
                    plugin.getLogger().info("已启用模块：" + module.getDisplayName());
                } catch (Throwable e) {
                    // 用 Throwable 而不是 Exception：模块里引用了服务端没有的 API 时
                    // 抛的是 NoSuchMethodError / NoClassDefFoundError，属于 Error，
                    // 漏掉它会让一次 /wbdaddon reload 直接崩在半路。
                    plugin.getLogger().severe("模块 " + module.getDisplayName() + " 启用失败：" + e);
                    e.printStackTrace();
                }
            } else {
                plugin.getLogger().info("模块已禁用：" + module.getDisplayName());
            }
        }
    }

    /**
     * 禁用所有已启用模块。
     * 按启用顺序的反序禁用，避免模块间可能存在的依赖问题。
     */
    public void disableAll() {
        for (int i = enabledModules.size() - 1; i >= 0; i--) {
            AddonModule module = enabledModules.get(i);
            try {
                module.onDisable();
            } catch (Throwable e) {
                // 同上：一个模块挂掉不能把整轮卸载（以及接下来的重新启用）带走
                plugin.getLogger().severe("模块 " + module.getDisplayName() + " 卸载失败：" + e);
                e.printStackTrace();
            }
        }
        enabledModules.clear();
    }

    /** 判断某个模块当前是否处于启用状态。 */
    public boolean isEnabled(AddonModule module) {
        return enabledModules.contains(module);
    }

    /**
     * 按 id 查找已注册的模块（不管是否启用），找不到返回 null。
     * 供命令等需要访问具体模块能力的地方使用。
     */
    public AddonModule getModule(String id) {
        for (AddonModule module : modules) {
            if (module.getId().equals(id)) {
                return module;
            }
        }
        return null;
    }

    /** 获取全部已注册模块（不可修改）。 */
    public List<AddonModule> getModules() {
        return Collections.unmodifiableList(modules);
    }

    /** 获取当前已启用的模块（不可修改）。 */
    public List<AddonModule> getEnabledModules() {
        return Collections.unmodifiableList(enabledModules);
    }
}