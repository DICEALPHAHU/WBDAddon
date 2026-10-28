package alphahu.wbdaddon.util;

import com.warz.bombdefuse.arena.ArenaSession;
import com.warz.bombdefuse.model.GameState;
import com.warz.bombdefuse.model.RoundEndReason;
import com.warz.bombdefuse.model.Team;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * 管理员强制操作（强制开局 / 强制结算本回合）。
 *
 * <p>WBD 没有开放这两个入口，只能反射它自己的内部方法：
 * <ul>
 *   <li>{@code startCountdown()}：把状态切到 COUNTDOWN 并开始倒计时；</li>
 *   <li>{@code endRound(Team, RoundEndReason)}：正常结算一回合，
 *       记账、广播、发钱、切到 ROUND_END 都是它做的。</li>
 * </ul>
 *
 * <p>这两个方法都带 WBD 自己的前置判断（{@code endRound} 在回合已结束或
 * 等待中会直接返回），所以重复调用是安全的。反射失败时只返回 false，
 * 不抛异常到命令层。
 *
 * @author AlphaHu
 */
public final class ArenaAdmin {

    private static Method startCountdownMethod;
    private static Method endRoundMethod;
    private static Field stateTicksField;
    private static Field roundLockField;
    private static boolean resolved;

    private ArenaAdmin() {
    }

    private static synchronized void resolve(Logger logger) {
        if (resolved) return;
        resolved = true;
        try {
            startCountdownMethod = ArenaSession.class.getDeclaredMethod("startCountdown");
            startCountdownMethod.setAccessible(true);
        } catch (Throwable t) {
            startCountdownMethod = null;
            logger.warning("管理面板：无法定位 ArenaSession.startCountdown，强制开局不可用。");
        }
        try {
            endRoundMethod = ArenaSession.class.getDeclaredMethod(
                    "endRound", Team.class, RoundEndReason.class);
            endRoundMethod.setAccessible(true);
        } catch (Throwable t) {
            endRoundMethod = null;
            logger.warning("管理面板：无法定位 ArenaSession.endRound，强制结算不可用。");
        }
        try {
            stateTicksField = ArenaSession.class.getDeclaredField("stateTicksRemaining");
            stateTicksField.setAccessible(true);
        } catch (Throwable t) {
            stateTicksField = null;
        }
        try {
            roundLockField = ArenaSession.class.getDeclaredField("roundEndingLocked");
            roundLockField.setAccessible(true);
        } catch (Throwable t) {
            roundLockField = null;
        }
    }

    /**
     * 跳过大厅等待，立刻进入倒计时。
     *
     * @param remainingTicks 倒计时压缩到多少 tick（20 = 1 秒）
     * @return 是否成功发起
     */
    public static boolean forceStart(ArenaSession session, int remainingTicks, Logger logger) {
        if (session == null) return false;
        resolve(logger);
        if (startCountdownMethod == null) return false;

        GameState state = session.getState();
        if (state != GameState.WAITING && state != GameState.COUNTDOWN) {
            return false;
        }
        try {
            session.startTasks();
            startCountdownMethod.invoke(session);
            if (stateTicksField != null) {
                stateTicksField.setInt(session, Math.max(1, remainingTicks));
            }
            return true;
        } catch (Throwable t) {
            logger.warning("管理面板：强制开局失败：" + t);
            return false;
        }
    }

    /**
     * 强制结算当前回合。
     *
     * @return 是否成功结算
     */
    public static boolean forceEndRound(ArenaSession session, Team winner, Logger logger) {
        if (session == null || winner == null) return false;
        resolve(logger);
        if (endRoundMethod == null) return false;

        GameState state = session.getState();
        if (state != GameState.LIVE && state != GameState.FREEZE) {
            return false;
        }
        // 单人测试时回合被锁住，结算前临时松开
        boolean wasLocked = isRoundLocked(session);
        if (wasLocked) {
            setRoundLocked(session, false, logger);
        }
        try {
            endRoundMethod.invoke(session, winner, RoundEndReason.ADMIN);
            return true;
        } catch (Throwable t) {
            logger.warning("管理面板：强制结算失败：" + t);
            return false;
        }
    }

    /**
     * 锁 / 解锁「回合自动结算」。
     *
     * <p>WBD 在 {@code startRound} 里会把 {@code roundEndingLocked} 置回 false，
     * {@code endRound} 开头见到它为 true 就直接返回。单人测试时锁上它，
     * 回合就不会因为「某队人数为 0」瞬间判负，也不会因为计时结束自动结算。
     *
     * @return 是否设置成功
     */
    public static boolean setRoundLocked(ArenaSession session, boolean locked, Logger logger) {
        if (session == null) return false;
        resolve(logger);
        if (roundLockField == null) return false;
        try {
            roundLockField.setBoolean(session, locked);
            return true;
        } catch (Throwable t) {
            logger.warning("管理面板：设置回合锁失败：" + t);
            return false;
        }
    }

    public static boolean isRoundLocked(ArenaSession session) {
        if (session == null || roundLockField == null) return false;
        try {
            return roundLockField.getBoolean(session);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 当前状态的中文名，给面板显示用。 */
    public static String stateName(GameState state) {
        if (state == null) return "未知";
        return switch (state) {
            case WAITING -> "等待中";
            case COUNTDOWN -> "倒计时";
            case FREEZE -> "冻结";
            case LIVE -> "进行中";
            case ROUND_END -> "回合结束";
            case MATCH_END -> "比赛结束";
        };
    }
}
