package alphahu.wbdaddon.util;

import com.warz.bombdefuse.arena.ArenaSession;
import com.warz.bombdefuse.arena.PlayerRecord;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
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

    /** 立刻给玩家加钱（受 WBD 金钱上限约束）。 */
    public static boolean addMoney(ArenaSession session, Player player, int amount, Logger logger) {
        if (session == null || player == null) return false;
        try {
            session.addMoney(player, amount);
            return true;
        } catch (Throwable t) {
            logger.warning("管理面板：加钱失败：" + t);
            return false;
        }
    }

    /**
     * 强行复活一名阵亡玩家。
     *
     * <p>WBD 的复活流程只在回合开始跑，中途死了就只能等下一回合。这里手动把他
     * 标成存活、切回冒险模式、传回本方出生点、补满血 —— 用于「测试卡住了」或
     * 「有人误判死亡」的救场。
     */
    public static boolean revive(ArenaSession session, Player player, Logger logger) {
        if (session == null || player == null) return false;
        try {
            PlayerRecord record = session.getRecord(player);
            if (record == null) return false;
            record.setAlive(true);

            Team team = record.getTeam();
            player.setGameMode(GameMode.ADVENTURE);
            player.setHealth(player.getMaxHealth());
            player.setFoodLevel(20);
            player.setFireTicks(0);

            java.util.List<org.bukkit.Location> spawns =
                    session.getConfig().getSpawns(team);
            if (spawns != null && !spawns.isEmpty()) {
                player.teleport(spawns.get(0));
            }
            return true;
        } catch (Throwable t) {
            logger.warning("管理面板：强行复活失败：" + t);
            return false;
        }
    }

    /**
     * 跳过回合结束的等待：当前处于 {@code ROUND_END} 时把倒计时清零，
     * 下一 tick 就会进入下一回合的倒计时。
     */
    public static boolean skipRoundEnd(ArenaSession session, Logger logger) {
        if (session == null) return false;
        resolve(logger);
        if (stateTicksField == null) return false;
        if (session.getState() != GameState.ROUND_END) return false;
        try {
            stateTicksField.setInt(session, 0);
            return true;
        } catch (Throwable t) {
            logger.warning("管理面板：跳过回合间隔失败：" + t);
            return false;
        }
    }

    /**
     * 临时塞一个「幽灵玩家」记录，骗过 WBD 的开局人数检查。
     *
     * <p>WBD 的 {@code ensurePlayableTeams()} 要求
     * {@code 玩家数 >= max(2, min-players)} 且两边都有人，否则
     * {@code tickCountdown} / {@code startRound} 都会直接 {@code resetToWaiting()} ——
     * 所以单人维护模式怎么点强制开局都开不起来。
     *
     * <p>这里往 {@code players} 表里放一条假记录占住空的那一边，让检查通过；
     * 回合真正开始后由调用方立刻删掉，避免它继续参与存活人数与结算。
     *
     * @return 假记录的 UUID；失败返回 null
     */
    public static java.util.UUID addPhantom(ArenaSession session, Team team, Logger logger) {
        if (session == null || team == null) return null;
        try {
            java.lang.reflect.Field field = ArenaSession.class.getDeclaredField("players");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<Object, Object> players =
                    (java.util.Map<Object, Object>) field.get(session);
            if (players == null) return null;

            java.util.UUID uuid = java.util.UUID.nameUUIDFromBytes(
                    ("wbdaddon-phantom-" + session.getConfig().getId() + "-" + team.name())
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Object record = new com.warz.bombdefuse.arena.PlayerRecord(uuid, "维护模式", team, 0);
            players.put(uuid, record);
            return uuid;
        } catch (Throwable t) {
            logger.warning("管理面板：插入测试占位记录失败：" + t);
            return null;
        }
    }

    /** 移除 {@link #addPhantom} 放进去的那条记录。 */
    public static void removePhantom(ArenaSession session, java.util.UUID uuid, Logger logger) {
        if (session == null || uuid == null) return;
        try {
            java.lang.reflect.Field field = ArenaSession.class.getDeclaredField("players");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<Object, Object> players =
                    (java.util.Map<Object, Object>) field.get(session);
            if (players != null) {
                players.remove(uuid);
            }
        } catch (Throwable t) {
            logger.warning("管理面板：移除测试占位记录失败：" + t);
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
