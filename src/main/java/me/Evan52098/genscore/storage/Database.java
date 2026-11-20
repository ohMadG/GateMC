package me.Evan52098.genscore.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Connection pool plus a small set of worker threads for database work.
 *
 * <p>Every query runs through {@link #async} (or {@link #sync} during startup and shutdown only), so no
 * region or entity thread ever waits on the database.
 *
 * <p>SQL written for this plugin must work on both PostgreSQL and SQLite: use {@code TEXT}, {@code BIGINT},
 * {@code INTEGER}, {@code DOUBLE PRECISION}; store UUIDs as {@code TEXT}; store times as epoch millis in {@code BIGINT};
 * upsert with {@code INSERT ... ON CONFLICT (key) DO UPDATE SET col = excluded.col}.
 */
public final class Database {

    @FunctionalInterface
    public interface SqlFunction<T> {
        T apply(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    public interface SqlConsumer {
        void accept(Connection connection) throws SQLException;
    }

    private final StorageConfig config;
    private final Logger logger;
    private final HikariDataSource dataSource;
    private final ExecutorService workers;

    public Database(StorageConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;

        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("GensCore-DB");
        int threads;
        if (config.type() == StorageConfig.Type.POSTGRES) {
            hikari.setDriverClassName("org.postgresql.Driver");
            hikari.setJdbcUrl("jdbc:postgresql://" + config.host() + ":" + config.port() + "/" + config.database());
            hikari.setUsername(config.username());
            hikari.setPassword(config.password());
            hikari.setMaximumPoolSize(config.poolSize());
            hikari.addDataSourceProperty("tcpKeepAlive", "true");
            hikari.addDataSourceProperty("socketTimeout", "30");
            threads = config.poolSize();
        } else {
            // SQLite allows one writer at a time, so one connection and one worker keep writes in order
            // and avoid "database is locked" errors.
            File parent = config.sqliteFile().getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            hikari.setDriverClassName("org.sqlite.JDBC");
            hikari.setJdbcUrl("jdbc:sqlite:" + config.sqliteFile().getAbsolutePath());
            hikari.setMaximumPoolSize(1);
            hikari.addDataSourceProperty("journal_mode", "WAL");
            hikari.addDataSourceProperty("busy_timeout", "10000");
            threads = 1;
        }
        hikari.setConnectionTimeout(TimeUnit.SECONDS.toMillis(10));
        hikari.setLeakDetectionThreshold(TimeUnit.SECONDS.toMillis(30));

        this.dataSource = new HikariDataSource(hikari);

        AtomicInteger counter = new AtomicInteger();
        this.workers = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "GensCore-DB-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        logger.info("Database ready (" + config.type().name().toLowerCase() + ", " + threads + " worker thread(s))");
    }

    public StorageConfig config() {
        return config;
    }

    public boolean isPostgres() {
        return config.type() == StorageConfig.Type.POSTGRES;
    }

    /** Column definition for an auto-numbered primary key, which PostgreSQL and SQLite spell differently. */
    public String autoIdColumn(String name) {
        return isPostgres() ? name + " BIGSERIAL PRIMARY KEY" : name + " INTEGER PRIMARY KEY AUTOINCREMENT";
    }

    /** Runs a query on a database worker. Never block a region or entity thread on the result. */
    public <T> CompletableFuture<T> async(SqlFunction<T> work) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection c = dataSource.getConnection()) {
                return work.apply(c);
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        }, workers);
    }

    /** Runs an update on a database worker. */
    public CompletableFuture<Void> asyncRun(SqlConsumer work) {
        return async(c -> {
            work.accept(c);
            return null;
        });
    }

    /** Runs on the calling thread. Only for startup, shutdown and threads that are already async. */
    public <T> T sync(SqlFunction<T> work) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            return work.apply(c);
        }
    }

    /** Runs the work inside a transaction, rolling back on any error. */
    public static <T> T transaction(Connection c, SqlFunction<T> work) throws SQLException {
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            T result = work.apply(c);
            c.commit();
            return result;
        } catch (SQLException | RuntimeException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(auto);
        }
    }

    /** Waits for queued work (up to the timeout), then closes the pool. */
    public void shutdown(long timeout, TimeUnit unit) {
        workers.shutdown();
        try {
            if (!workers.awaitTermination(timeout, unit)) {
                logger.warning("Database work still running at shutdown; it was abandoned and may not have been written.");
                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            workers.shutdownNow();
        }
        dataSource.close();
    }
}
