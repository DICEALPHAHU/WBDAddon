package alphahu.wbdaddon.storage;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * 生涯数据与比赛历史的 SQLite 存储。
 *
 * <p>和 WBDTeamDeathmatch 的 {@code RankedStore} 用的是同一套思路：
 * <ul>
 *   <li><b>驱动不打包进 jar</b>：Paper / Spigot / Arclight 的 {@code libraries/}
 *       里自带 {@code org.xerial:sqlite-jdbc}，显式 {@code Class.forName} 加载一次，
 *       失败就整体降级回 YAML，而不是让 {@code DriverManager} 抛
 *       "No suitable driver" 这种看不出原因的错。</li>
 *   <li><b>WAL + synchronous=NORMAL</b>：写不阻塞读，崩服最多丢最后一笔事务，
 *       比 YAML 全量重写安全得多。</li>
 *   <li><b>所有方法 synchronized</b>：SQLite 单连接不是线程安全的。</li>
 * </ul>
 *
 * <p>表设计刻意保持扁平：{@code wbd_last_results} / {@code wbd_matches} /
 * {@code wbd_match_players} / {@code wbd_career}。写入走
 * {@link #replaceAll(Collection, Collection, Collection)}：一场比赛结束时
 * 整体重写（几十到几百行的小事务），保证内存与库永远一致，也就不需要
 * 维护一堆增量更新的状态。
 *
 * @author AlphaHu
 */
public final class StatsStore {

    private static final String CREATE_RESULTS = """
            CREATE TABLE IF NOT EXISTS wbd_last_results (
              arena    TEXT PRIMARY KEY,
              winner   TEXT    NOT NULL DEFAULT '',
              t_score  INTEGER NOT NULL DEFAULT 0,
              ct_score INTEGER NOT NULL DEFAULT 0
            )""";

    private static final String CREATE_MATCHES = """
            CREATE TABLE IF NOT EXISTS wbd_matches (
              id        INTEGER PRIMARY KEY AUTOINCREMENT,
              arena     TEXT    NOT NULL DEFAULT '',
              winner    TEXT    NOT NULL DEFAULT '',
              t_score   INTEGER NOT NULL DEFAULT 0,
              ct_score  INTEGER NOT NULL DEFAULT 0,
              played_at INTEGER NOT NULL DEFAULT 0
            )""";

    private static final String CREATE_MATCH_PLAYERS = """
            CREATE TABLE IF NOT EXISTS wbd_match_players (
              match_id INTEGER NOT NULL,
              name     TEXT    NOT NULL DEFAULT '',
              team     TEXT    NOT NULL DEFAULT '',
              kills    INTEGER NOT NULL DEFAULT 0,
              deaths   INTEGER NOT NULL DEFAULT 0,
              plants   INTEGER NOT NULL DEFAULT 0,
              defuses  INTEGER NOT NULL DEFAULT 0
            )""";

    private static final String CREATE_CAREER = """
            CREATE TABLE IF NOT EXISTS wbd_career (
              uuid    TEXT PRIMARY KEY,
              name    TEXT    NOT NULL DEFAULT '',
              kills   INTEGER NOT NULL DEFAULT 0,
              deaths  INTEGER NOT NULL DEFAULT 0,
              plants  INTEGER NOT NULL DEFAULT 0,
              defuses INTEGER NOT NULL DEFAULT 0,
              matches INTEGER NOT NULL DEFAULT 0,
              wins    INTEGER NOT NULL DEFAULT 0
            )""";

    private final Logger logger;
    private Connection connection;

    public StatsStore(Logger logger) {
        this.logger = logger;
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /**
     * 打开数据库并建表。
     *
     * @return 是否可用。false 表示驱动缺失或文件打不开，调用方应退回 YAML
     */
    public synchronized boolean open(File file) {
        close();

        try {
            // 显式加载，失败时能给出「驱动缺失」这个准确结论
            Class.forName("org.sqlite.JDBC");
        } catch (Throwable t) {
            logger.info("未找到 SQLite 驱动（org.sqlite.JDBC），生涯与比赛数据继续用 YAML。");
            return false;
        }

        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                logger.warning("无法创建数据库目录：" + parent.getPath());
                return false;
            }
            connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                st.executeUpdate(CREATE_RESULTS);
                st.executeUpdate(CREATE_MATCHES);
                st.executeUpdate(CREATE_MATCH_PLAYERS);
                st.executeUpdate(CREATE_CAREER);
            }
            logger.info("生涯与比赛数据已使用 SQLite：" + file.getPath());
            return true;
        } catch (Throwable t) {
            logger.warning("打开数据库失败，已退回 YAML：" + t);
            close();
            return false;
        }
    }

    public synchronized boolean isAvailable() {
        try {
            return connection != null && !connection.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }

    public synchronized void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // 关闭失败没有补救手段
            }
            connection = null;
        }
    }

    /** 库里是否已经有数据（用于决定要不要从 YAML 导入）。 */
    public synchronized boolean isEmpty() {
        if (!isAvailable()) return true;
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT (SELECT COUNT(*) FROM wbd_career) + (SELECT COUNT(*) FROM wbd_matches)")) {
            return !rs.next() || rs.getInt(1) == 0;
        } catch (SQLException e) {
            return true;
        }
    }

    // ------------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------------

    public synchronized List<ResultRow> loadResults() {
        List<ResultRow> out = new ArrayList<>();
        if (!isAvailable()) return out;
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT arena, winner, t_score, ct_score FROM wbd_last_results")) {
            while (rs.next()) {
                out.add(new ResultRow(rs.getString(1), rs.getString(2),
                        rs.getInt(3), rs.getInt(4)));
            }
        } catch (SQLException e) {
            logger.warning("读取最近比赛结果失败：" + e.getMessage());
        }
        return out;
    }

    /** 最近的比赛历史，按时间倒序。 */
    public synchronized List<MatchRow> loadHistory(int limit) {
        List<MatchRow> matches = new ArrayList<>();
        if (!isAvailable()) return matches;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id, arena, winner, t_score, ct_score, played_at "
                        + "FROM wbd_matches ORDER BY played_at DESC, id DESC LIMIT ?")) {
            ps.setInt(1, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    matches.add(new MatchRow(rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getInt(4), rs.getInt(5), rs.getLong(6)));
                }
            }
        } catch (SQLException e) {
            logger.warning("读取比赛历史失败：" + e.getMessage());
            return matches;
        }

        for (MatchRow match : matches) {
            match.lines().addAll(loadLines(match.id()));
        }
        return matches;
    }

    private List<PlayerLine> loadLines(long matchId) {
        List<PlayerLine> lines = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT name, team, kills, deaths, plants, defuses "
                        + "FROM wbd_match_players WHERE match_id = ? ORDER BY kills DESC")) {
            ps.setLong(1, matchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lines.add(new PlayerLine(rs.getString(1), rs.getString(2), rs.getInt(3),
                            rs.getInt(4), rs.getInt(5), rs.getInt(6)));
                }
            }
        } catch (SQLException e) {
            logger.warning("读取比赛成员数据失败：" + e.getMessage());
        }
        return lines;
    }

    public synchronized List<CareerRow> loadCareer() {
        List<CareerRow> out = new ArrayList<>();
        if (!isAvailable()) return out;
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT uuid, name, kills, deaths, plants, defuses, matches, wins "
                             + "FROM wbd_career")) {
            while (rs.next()) {
                try {
                    out.add(new CareerRow(UUID.fromString(rs.getString(1)), rs.getString(2),
                            rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getInt(6),
                            rs.getInt(7), rs.getInt(8)));
                } catch (IllegalArgumentException ignored) {
                    // 坏 UUID 跳过
                }
            }
        } catch (SQLException e) {
            logger.warning("读取生涯数据失败：" + e.getMessage());
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 写入
    // ------------------------------------------------------------------

    /**
     * 整体重写三张表（一个事务内）。
     *
     * <p>数据量很小（最近结果几张图 + 最多几十场历史 + 若干生涯行），
     * 全量重写比维护增量更新简单得多，也保证内存与库永远一致。
     */
    public synchronized boolean replaceAll(Collection<ResultRow> results,
                                           Collection<MatchRow> matches,
                                           Collection<CareerRow> career) {
        if (!isAvailable()) return false;
        try {
            connection.setAutoCommit(false);
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("DELETE FROM wbd_match_players");
                st.executeUpdate("DELETE FROM wbd_matches");
                st.executeUpdate("DELETE FROM wbd_last_results");
                st.executeUpdate("DELETE FROM wbd_career");
            }

            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO wbd_last_results (arena, winner, t_score, ct_score) VALUES (?,?,?,?)")) {
                for (ResultRow row : results) {
                    ps.setString(1, row.arena());
                    ps.setString(2, row.winner());
                    ps.setInt(3, row.tScore());
                    ps.setInt(4, row.ctScore());
                    ps.addBatch();
                }
                ps.executeBatch();
            }

            // 逐条插入：批量插入拿不到每行的自增 id（SQLite JDBC 只回最后一个），
            // 后面的成员数据就会全挂到同一个 match_id 上
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO wbd_matches (arena, winner, t_score, ct_score, played_at) "
                            + "VALUES (?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                for (MatchRow match : matches) {
                    ps.setString(1, match.arena());
                    ps.setString(2, match.winner());
                    ps.setInt(3, match.tScore());
                    ps.setInt(4, match.ctScore());
                    ps.setLong(5, match.playedAt());
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        if (keys.next()) {
                            match.id(keys.getLong(1));
                        }
                    }
                }
            }

            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO wbd_match_players "
                            + "(match_id, name, team, kills, deaths, plants, defuses) "
                            + "VALUES (?,?,?,?,?,?,?)")) {
                for (MatchRow match : matches) {
                    for (PlayerLine line : match.lines()) {
                        ps.setLong(1, match.id());
                        ps.setString(2, line.name());
                        ps.setString(3, line.team());
                        ps.setInt(4, line.kills());
                        ps.setInt(5, line.deaths());
                        ps.setInt(6, line.plants());
                        ps.setInt(7, line.defuses());
                        ps.addBatch();
                    }
                }
                ps.executeBatch();
            }

            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO wbd_career "
                            + "(uuid, name, kills, deaths, plants, defuses, matches, wins) "
                            + "VALUES (?,?,?,?,?,?,?,?)")) {
                for (CareerRow row : career) {
                    ps.setString(1, row.uuid().toString());
                    ps.setString(2, row.name());
                    ps.setInt(3, row.kills());
                    ps.setInt(4, row.deaths());
                    ps.setInt(5, row.plants());
                    ps.setInt(6, row.defuses());
                    ps.setInt(7, row.matches());
                    ps.setInt(8, row.wins());
                    ps.addBatch();
                }
                ps.executeBatch();
            }

            connection.commit();
            return true;
        } catch (SQLException e) {
            logger.warning("写入生涯/比赛数据失败：" + e.getMessage());
            try {
                connection.rollback();
            } catch (SQLException ignored) {
                // 回滚失败只能放弃这次写入
            }
            return false;
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
                // 恢复自动提交失败不影响下次写入
            }
        }
    }

    // ------------------------------------------------------------------
    // 行类型
    // ------------------------------------------------------------------

    /** 每张图最近一场的比分。 */
    public record ResultRow(String arena, String winner, int tScore, int ctScore) {
    }

    /** 一场比赛（附带每名玩家的数据）。 */
    public static final class MatchRow {
        private long id;
        private final String arena;
        private final String winner;
        private final int tScore;
        private final int ctScore;
        private final long playedAt;
        private final List<PlayerLine> lines = new ArrayList<>();

        public MatchRow(long id, String arena, String winner, int tScore, int ctScore,
                        long playedAt) {
            this.id = id;
            this.arena = arena;
            this.winner = winner;
            this.tScore = tScore;
            this.ctScore = ctScore;
            this.playedAt = playedAt;
        }

        public long id() {
            return id;
        }

        public void id(long value) {
            this.id = value;
        }

        public String arena() {
            return arena;
        }

        public String winner() {
            return winner;
        }

        public int tScore() {
            return tScore;
        }

        public int ctScore() {
            return ctScore;
        }

        public long playedAt() {
            return playedAt;
        }

        public List<PlayerLine> lines() {
            return lines;
        }
    }

    /** 一场比赛里某名玩家的 K/D。 */
    public record PlayerLine(String name, String team, int kills, int deaths,
                             int plants, int defuses) {
    }

    /** 生涯数据。 */
    public record CareerRow(UUID uuid, String name, int kills, int deaths,
                            int plants, int defuses, int matches, int wins) {
    }
}
