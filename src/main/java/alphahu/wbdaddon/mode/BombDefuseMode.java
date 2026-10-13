package alphahu.wbdaddon.mode;

import alphahu.wbdaddon.api.AddonMode;
import com.warz.bombdefuse.WarZBombDefusePlugin;
import com.warz.bombdefuse.arena.ArenaSession;
import com.warz.bombdefuse.arena.PlayerRecord;
import com.warz.bombdefuse.model.Team;
import org.bukkit.entity.Player;

/**
 * 把 WarZBombDefuse 内置的爆破模式包装成 {@link AddonMode}。
 *
 * <p>这层包装的意义不在于「多此一举」，而在于让附加组件彻底不再认识
 * {@code ArenaSession}：改造后它们只跟 {@code AddonMode} 打交道，
 * 爆破模式与第三方模式在它们眼里从此没有区别。
 *
 * <p>所有查询都直连 WBD 的公开 API，不复制它任何内部状态，
 * 因此 WBD 的回合推进、玩家进出都会立刻反映到这里。
 *
 * @author AlphaHu
 */
public final class BombDefuseMode implements AddonMode {

    /** 模式标识，第三方插件可以用它判断内置爆破模式是否存在。 */
    public static final String MODE_ID = "bomb-defuse";

    private final WarZBombDefusePlugin wbd;

    public BombDefuseMode(WarZBombDefusePlugin wbd) {
        this.wbd = wbd;
    }

    @Override
    public String getModeId() {
        return MODE_ID;
    }

    @Override
    public String getDisplayName() {
        return "爆破模式（WarZBombDefuse）";
    }

    @Override
    public boolean isParticipating(Player player) {
        return session(player) != null;
    }

    @Override
    public boolean isPlaying(Player player) {
        Team team = team(player);
        return team != null && team.isPlaying();
    }

    @Override
    public boolean isAlive(Player player) {
        ArenaSession session = session(player);
        if (session == null) {
            return false;
        }
        PlayerRecord record = session.getRecord(player);
        return record != null && record.isAlive();
    }

    @Override
    public String getTeamId(Player player) {
        Team team = team(player);
        // 只有 T/CT 才算是「阵营」；SPECTATOR 返回 null，调用方据此把玩家当作无队
        return team != null && team.isPlaying() ? team.name() : null;
    }

    @Override
    public String getArenaId(Player player) {
        ArenaSession session = session(player);
        return session == null ? null : session.getConfig().getId();
    }

    @Override
    public String getArenaName(Player player) {
        ArenaSession session = session(player);
        return session == null ? null : session.getConfig().getName();
    }

    /**
     * 爆破模式的存活人数顶栏仍由 WBDAddon 的 match-status 模块渲染
     * （它还要顺带画炸弹倒计时条），所以这里不提供自定义行。
     */
    @Override
    public String hudLine(Player player) {
        return null;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private ArenaSession session(Player player) {
        if (player == null || wbd == null || wbd.getArenaManager() == null) {
            return null;
        }
        return wbd.getArenaManager().getSession(player);
    }

    private Team team(Player player) {
        ArenaSession session = session(player);
        return session == null ? null : session.getTeam(player);
    }
}
