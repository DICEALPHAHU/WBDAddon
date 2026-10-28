package alphahu.wbdaddon.module;

import alphahu.wbdaddon.WBDAddon;
import alphahu.wbdaddon.api.AddonMode;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.WrappedDataValue;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 队友轮廓发光（只对队友可见）。
 *
 * <h2>为什么不能只用原版效果</h2>
 *
 * <p>原版的「发光」是<b>全局</b>的：给某个人挂上 GLOWING，所有玩家都能隔墙看到他的轮廓。
 * 那样敌人也能看见你，等于把位置送出去。要让「只有队友看得到队友」，
 * 只能逐人发包——对每一名观察者，单独把队友的实体标志位打上发光。
 *
 * <h2>实现</h2>
 *
 * <p>用 ProtocolLib 构造 {@code ENTITY_METADATA} 包，只改实体标志字节的第 6 位
 * （{@code 0x40} = glowing），发给「观察者」而不是广播。包里带的是该玩家当前的完整
 * 标志位（着火 / 潜行 / 冲刺 / 游泳 / 隐身 / 滑翔），所以不会把别的手感弄没。
 *
 * <p>每 {@code interval-ticks} 同步一次；只对状态变化的人发包，
 * 队形不变时一个包都不发，避免客户端反复刷新。
 *
 * @author AlphaHu
 */
public class TeammateGlowModule implements AddonModule {

    /** 实体标志位里的「发光」位。 */
    private static final byte GLOWING_FLAG = 0x40;

    /** 实体标志位所在的 DataWatcher 索引。 */
    private static final int FLAGS_INDEX = 0;

    private final WBDAddon plugin;

    /** 观察者 -> 当前对他发过「发光」的队友。 */
    private final Map<UUID, Set<UUID>> glowing = new ConcurrentHashMap<>();

    private ProtocolManager manager;
    private BukkitTask task;
    private boolean supported;
    private long intervalTicks;

    public TeammateGlowModule(WBDAddon plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getId() {
        return "teammate-glow";
    }

    @Override
    public String getDisplayName() {
        return "队友轮廓发光";
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }

    @Override
    public void onEnable() {
        this.intervalTicks = Math.max(10L, plugin.getConfig()
                .getLong("modules.teammate-glow.interval-ticks", 20L));

        try {
            this.manager = ProtocolLibrary.getProtocolManager();
            this.supported = manager != null;
        } catch (Throwable t) {
            this.manager = null;
            this.supported = false;
        }
        if (!supported) {
            plugin.getLogger().warning("队友轮廓发光：ProtocolLib 不可用，已跳过。"
                    + "（本功能需要逐人发包，不能只用原版效果）");
            return;
        }

        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, intervalTicks);
        plugin.getLogger().info("队友轮廓发光已启动：每 " + intervalTicks
                + " tick 同步一次，只对同阵营可见。");
    }

    @Override
    public void onDisable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        clearAll();
        plugin.getLogger().info("队友轮廓发光已卸载。");
    }

    // ------------------------------------------------------------------
    // 同步
    // ------------------------------------------------------------------

    private void tick() {
        if (!supported) return;
        if (plugin.getApi() == null) return;

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            AddonMode mode = plugin.getApi().getModeFor(viewer);
            if (mode == null) {
                clearViewer(viewer);
                continue;
            }
            String arenaId = mode.getArenaId(viewer);
            String teamId = mode.getTeamId(viewer);
            if (arenaId == null || teamId == null) {
                clearViewer(viewer);
                continue;
            }

            Set<UUID> desired = new HashSet<>();
            for (Player target : Bukkit.getOnlinePlayers()) {
                if (target.equals(viewer)) continue;
                AddonMode targetMode = plugin.getApi().getModeFor(target);
                if (targetMode != mode) continue;
                if (!arenaId.equals(targetMode.getArenaId(target))) continue;
                if (!teamId.equals(targetMode.getTeamId(target))) continue;
                if (!targetMode.isAlive(target)) continue;
                desired.add(target.getUniqueId());
            }

            Set<UUID> current = glowing.computeIfAbsent(viewer.getUniqueId(),
                    k -> ConcurrentHashMap.newKeySet());

            for (UUID id : new HashSet<>(current)) {
                if (desired.contains(id)) continue;
                Player target = Bukkit.getPlayer(id);
                if (target != null) {
                    sendGlow(viewer, target, false);
                }
                current.remove(id);
            }
            for (UUID id : desired) {
                if (current.contains(id)) continue;
                Player target = Bukkit.getPlayer(id);
                if (target == null) continue;
                if (sendGlow(viewer, target, true)) {
                    current.add(id);
                }
            }
        }
    }

    private void clearViewer(Player viewer) {
        Set<UUID> current = glowing.remove(viewer.getUniqueId());
        if (current == null || current.isEmpty()) return;
        for (UUID id : current) {
            Player target = Bukkit.getPlayer(id);
            if (target != null) {
                sendGlow(viewer, target, false);
            }
        }
    }

    private void clearAll() {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            clearViewer(viewer);
        }
        glowing.clear();
    }

    /**
     * 给观察者单独发一个实体元数据包，开关目标玩家的发光位。
     *
     * @return 是否发送成功（失败会停用整个模块，避免刷屏）
     */
    private boolean sendGlow(Player viewer, Player target, boolean glow) {
        if (!supported || viewer == null || target == null) return false;
        try {
            PacketContainer packet = manager.createPacket(PacketType.Play.Server.ENTITY_METADATA);
            packet.getIntegers().write(0, target.getEntityId());

            byte flags = flagsOf(target, glow);
            // ProtocolLib 5.x 对 ENTITY_METADATA 有新旧两种结构，
            // 先试 DataWatcher，不行再退回 1.19.3+ 的 DataValue 列表
            if (!writeWatcher(packet, target, flags) && !writeDataValue(packet, flags)) {
                throw new IllegalStateException("两种元数据结构都无法写入");
            }

            manager.sendServerPacket(viewer, packet, false);
            return true;
        } catch (Throwable t) {
            supported = false;
            plugin.getLogger().warning("队友轮廓发光：发包失败，已停用（不影响其他功能）：" + t);
            return false;
        }
    }

    /** 读当前实体标志位（含着火 / 潜行 / 冲刺等），只改动发光位。 */
    private byte flagsOf(Player target, boolean glow) {
        byte flags = 0;
        boolean read = false;
        try {
            Byte raw = new WrappedDataWatcher(target).getByte(FLAGS_INDEX);
            if (raw != null) {
                flags = raw;
                read = true;
            }
        } catch (Throwable ignored) {
            // 读不到就按 Bukkit 状态拼一份
        }
        if (!read) {
            if (target.getFireTicks() > 0) flags |= 0x01;
            if (target.isSneaking()) flags |= 0x02;
            if (target.isSprinting()) flags |= 0x08;
            if (target.isSwimming()) flags |= 0x10;
            if (target.isInvisible()) flags |= 0x20;
            if (target.isGliding()) flags |= 0x80;
        }
        return (byte) (glow ? (flags | GLOWING_FLAG) : (flags & ~GLOWING_FLAG));
    }

    private boolean writeWatcher(PacketContainer packet, Player target, byte flags) {
        try {
            WrappedDataWatcher watcher = new WrappedDataWatcher(target);
            watcher.setByte(FLAGS_INDEX, flags, false);
            packet.getDataWatcherModifier().write(0, watcher);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean writeDataValue(PacketContainer packet, byte flags) {
        try {
            Class<?> serializers = Class.forName("net.minecraft.network.syncher.EntityDataSerializers");
            Object byteCodec = serializers.getField("BYTE").get(null);
            WrappedDataWatcher.Serializer serializer =
                    new WrappedDataWatcher.Serializer(byte.class, byteCodec, false);
            packet.getDataValueCollectionModifier().write(0,
                    java.util.List.of(new WrappedDataValue(FLAGS_INDEX, serializer, flags)));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
