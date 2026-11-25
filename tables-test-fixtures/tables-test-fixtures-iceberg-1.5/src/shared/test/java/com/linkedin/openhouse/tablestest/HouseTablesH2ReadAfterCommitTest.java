package com.linkedin.openhouse.tablestest;

import static com.linkedin.openhouse.common.api.validator.ValidatorConstants.INITIAL_TABLE_VERSION;

import com.linkedin.openhouse.internal.catalog.model.HouseTable;
import com.linkedin.openhouse.internal.catalog.model.HouseTablePrimaryKey;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * A read of the stand-in's House Tables row that starts after a commit returned must see that
 * commit, as a read of the house tables service does.
 *
 * <p>H2 2.1 reuses the last result of a query on the same session when no table it reads was
 * modified since that result was computed ({@code OPTIMIZE_REUSE_RESULTS}, on by default). Its
 * commit marks the table modified ({@code SessionLocal.commit}: {@code markUsedTablesAsUpdated})
 * before it makes the new row visible ({@code Transaction.commit}), so a read on another session in
 * between computes the old row and records it as current: every later run of the same query on that
 * session, with the same parameters, returns the old row until the next change to the table.
 * HikariCP hands a thread back the connection it used last, so in the tables service the old row
 * came back to every request on that thread: a compaction's file-group commit was refused (409)
 * through all of Iceberg's retries, the service comparing against a version two seconds old. Seen
 * once in a full apps suite; these cases open the window by repetition and, with result reuse on,
 * fail on every run (602 old reads through findById, 761334 through JDBC, in the run that pinned
 * it).
 */
public class HouseTablesH2ReadAfterCommitTest {

  private static final String DB = "readdb";
  private static final String TABLE = "t";
  private static final int COMMITS = 3000;
  private static final int READERS = 3;

  private ConfigurableApplicationContext context;
  private HouseTablesH2Repository repository;
  private DataSource dataSource;

  @BeforeEach
  public void setUp() {
    try {
      org.apache.catalina.webresources.TomcatURLStreamHandlerFactory.register();
    } catch (Error e) {
      org.apache.catalina.webresources.TomcatURLStreamHandlerFactory.disable();
    }
    SpringApplication application = new SpringApplication(SpringH2TestApplication.class);
    application.setDefaultProperties(Collections.singletonMap("server.port", "0"));
    context = application.run();
    repository = context.getBean(HouseTablesH2Repository.class);
    dataSource = context.getBean(DataSource.class);
    repository.deleteAll();
    repository.save(row(INITIAL_TABLE_VERSION, location(0)));
  }

  @AfterEach
  public void tearDown() {
    if (context != null) {
      context.close();
    }
  }

  private static String location(long version) {
    return String.format("/m/%08d.metadata.json", version);
  }

  private static long version(String location) {
    return Long.parseLong(location.substring(3, 11));
  }

  private static HouseTable row(String version, String location) {
    return HouseTable.builder()
        .databaseId(DB)
        .tableId(TABLE)
        .tableLocation(location)
        .tableVersion(version)
        .storageType("local")
        .build();
  }

  /** Readers on their own connections, each running the same query over and over. */
  @Test
  public void aReadOnAConnectionSeesEveryCommitThatReturnedBeforeIt() throws Exception {
    Assertions.assertEquals(
        0, raceReaders(true), "reads that missed a commit which returned before them");
  }

  /** The tables service's own read: the stand-in's findById, one transaction per call. */
  @Test
  public void findByIdSeesEveryCommitThatReturnedBeforeIt() throws Exception {
    Assertions.assertEquals(
        0, raceReaders(false), "reads that missed a commit which returned before them");
  }

  /**
   * Commits {@link #COMMITS} versions through the stand-in's save (its compare-and-swap) while
   * {@link #READERS} threads read; returns how many reads saw a version older than one whose commit
   * had returned before the read started.
   */
  private long raceReaders(boolean jdbc) throws Exception {
    HouseTablePrimaryKey key = HouseTablePrimaryKey.builder().databaseId(DB).tableId(TABLE).build();
    AtomicLong committed = new AtomicLong(0);
    AtomicLong stale = new AtomicLong();
    AtomicLong reads = new AtomicLong();
    AtomicBoolean done = new AtomicBoolean();
    ExecutorService pool = Executors.newFixedThreadPool(READERS);
    List<Future<?>> readers = new ArrayList<>();
    try {
      for (int r = 0; r < READERS; r++) {
        readers.add(
            pool.submit(
                () -> {
                  if (jdbc) {
                    try (Connection connection = dataSource.getConnection()) {
                      readLoop(connection, committed, stale, reads, done);
                    }
                  } else {
                    while (!done.get()) {
                      long before = committed.get();
                      if (version(repository.findById(key).get().getTableLocation()) < before) {
                        stale.incrementAndGet();
                      }
                      reads.incrementAndGet();
                    }
                  }
                  return null;
                }));
      }
      for (long v = 1; v <= COMMITS; v++) {
        repository.save(row(location(v - 1), location(v)));
        committed.set(v);
      }
      done.set(true);
      for (Future<?> reader : readers) {
        reader.get();
      }
    } finally {
      done.set(true);
      pool.shutdownNow();
    }
    Assertions.assertTrue(reads.get() > 0, "the readers ran");
    return stale.get();
  }

  /** The JDBC reader: one connection for the whole loop, as a pooled connection a thread keeps. */
  private static void readLoop(
      Connection connection,
      AtomicLong committed,
      AtomicLong stale,
      AtomicLong reads,
      AtomicBoolean done)
      throws Exception {
    String sql = "SELECT TABLE_LOCATION FROM HOUSE_TABLE WHERE DATABASE_ID = ? AND TABLE_ID = ?";
    while (!done.get()) {
      long before = committed.get();
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, DB);
        statement.setString(2, TABLE);
        try (ResultSet result = statement.executeQuery()) {
          result.next();
          if (version(result.getString(1)) < before) {
            stale.incrementAndGet();
          }
        }
      }
      reads.incrementAndGet();
    }
  }
}
