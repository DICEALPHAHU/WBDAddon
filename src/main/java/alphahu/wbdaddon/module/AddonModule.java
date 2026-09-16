package alphahu.wbdaddon.module;

/**
 * WBDAddon 功能模块接口。
 *
 * <p>每个模块实现这个接口，由 ModuleManager 统一管理生命周期。
 * 模块之间应尽量解耦，不要互相直接引用。
 *
 * @author AlphaHu
 */
public interface AddonModule {

    /**
     * 模块 ID，对应 config.yml 里的 modules.<id>.enabled。
     * 例如返回 "kill-reports"，配置里就是 modules.kill-reports.enabled。
     */
    String getId();

    /** 模块显示名称，用于日志和 /wbdaddon modules。 */
    String getDisplayName();

    /** 模块是否默认启用（config 里没写该模块的 enabled 时的默认值）。 */
    boolean isEnabledByDefault();

    /** 模块初始化，注册监听器、启动任务等。 */
    void onEnable();

    /** 模块卸载，注销监听器、取消任务、清理数据等。 */
    void onDisable();
}