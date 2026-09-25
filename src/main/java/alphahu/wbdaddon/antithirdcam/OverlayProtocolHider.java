package alphahu.wbdaddon.antithirdcam;

import alphahu.wbdaddon.WBDAddon;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 遮挡面的协议层隐藏器。
 *
 * <p><b>为什么非得走协议层：</b>遮挡面必须「只对本人可见」，而这在 Arclight 上
 * 用 Bukkit API 是做不到的——实测 {@code Player#hideEntity} 虽然<b>方法存在、
 * 调用也不报错</b>，却完全不生效（{@code Entity#setVisibleByDefault} 同理），
 * 于是遮挡面对所有人可见，表现为「黑块挂在别人身上」。这是 Arclight 对
 * Paper 扩展 API 兼容残缺所致，同一个病根也导致 WBD 的
 * {@code Player#teleportAsync} 直接不存在。
 *
 * <p>因此这里改为拦截数据包：遮挡面生成（SPAWN_ENTITY）、位置更新
 * （ENTITY_TELEPORT）与元数据（ENTITY_METADATA）这三个包，
 * <b>只放行给本人</b>，其余玩家一律取消。客户端从未收到过这个实体，
 * 自然也就不会渲染它。
 *
 * <p>没装 ProtocolLib 时 {@link #isActive()} 返回 false，调用方会退回到
 * 原有的 Bukkit API 方案（在 Paper 系服务端上是有效的）。
 *
 * @author AlphaHu
 */
public class OverlayProtocolHider {

    private final WBDAddon plugin;

    /** 遮挡面实体 ID -> 该遮挡面的主人。用实体 ID 做键是为了在包回调里 O(1) 判断。 */
    private final Map<Integer, UUID> ownerByEntityId = new ConcurrentHashMap<>();

    /** 包监听器，未启用时为 null。 */
    private PacketAdapter adapter;

    public OverlayProtocolHider(WBDAddon plugin) {
        this.plugin = plugin;
    }

    /** ProtocolLib 是否已安装。 */
    public static boolean isProtocolLibPresent() {
        return Bukkit.getPluginManager().getPlugin("ProtocolLib") != null;
    }

    /** 协议层隐藏是否已经真正启用。 */
    public boolean isActive() {
        return adapter != null;
    }

    /**
     * 注册包监听。
     *
     * @return 是否成功启用
     */
    public boolean start() {
        if (!isProtocolLibPresent()) {
            plugin.getLogger().warning("未检测到 ProtocolLib：遮挡面只能退回 Bukkit API 隐藏。"
                    + "在 Arclight 这类服务端上该 API 不生效，遮挡面会对所有人可见。");
            return false;
        }

        try {
            adapter = new PacketAdapter(plugin, ListenerPriority.NORMAL,
                    PacketType.Play.Server.SPAWN_ENTITY,
                    PacketType.Play.Server.ENTITY_TELEPORT,
                    PacketType.Play.Server.ENTITY_METADATA) {
                @Override
                public void onPacketSending(PacketEvent event) {
                    handlePacket(event);
                }
            };
            ProtocolLibrary.getProtocolManager().addPacketListener(adapter);
            plugin.getLogger().info("遮挡面已改用 ProtocolLib 在数据包层隐藏，只对本人可见。");
            return true;
        } catch (Throwable t) {
            adapter = null;
            plugin.getLogger().severe("注册 ProtocolLib 包监听失败：" + t);
            return false;
        }
    }

    public void stop() {
        if (adapter != null) {
            try {
                ProtocolLibrary.getProtocolManager().removePacketListener(adapter);
            } catch (Throwable ignored) {
                // 卸载阶段的清理失败无需惊动服主
            }
            adapter = null;
        }
        ownerByEntityId.clear();
    }

    /** 把某个遮挡面登记为「只给 owner 看」。 */
    public void register(Entity overlay, UUID owner) {
        ownerByEntityId.put(overlay.getEntityId(), owner);
    }

    /** 遮挡面被删除时注销，避免实体 ID 被复用后误伤。 */
    public void unregister(Entity overlay) {
        if (overlay != null) {
            ownerByEntityId.remove(overlay.getEntityId());
        }
    }

    /**
     * 包回调：这三个包的第一个整数都是实体 ID（1.20.1 的包结构如此）。
     * 只有「属于某个遮挡面、且收件人不是它的主人」时才取消发送。
     */
    private void handlePacket(PacketEvent event) {
        if (ownerByEntityId.isEmpty()) return;

        int entityId;
        try {
            PacketContainer packet = event.getPacket();
            entityId = packet.getIntegers().read(0);
        } catch (Throwable t) {
            // 包结构与预期不符时静默跳过，不能因为一个包把监听器搞崩
            return;
        }

        UUID owner = ownerByEntityId.get(entityId);
        if (owner == null) return;
        if (owner.equals(event.getPlayer().getUniqueId())) return;

        event.setCancelled(true);
    }
}
