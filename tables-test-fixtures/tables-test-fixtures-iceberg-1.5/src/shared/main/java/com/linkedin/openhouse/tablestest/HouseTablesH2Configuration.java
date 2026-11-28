package com.linkedin.openhouse.tablestest;

import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The database of the House Tables stand-in ({@link HouseTablesH2Repository}), one per application
 * context, for every application that embeds the stand-in.
 *
 * <p>A bean, not a {@code spring.datasource.url}: a property source can be outranked by any
 * consumer's {@code application.properties} or {@code -D} flag, which would silently drop the one
 * setting the stand-in needs. With {@code OPTIMIZE_REUSE_RESULTS} on, H2 2.1 can serve a session
 * the row as it was before a commit that returned already, until the table changes again ({@code
 * HouseTablesH2ReadAfterCommitTest}), which the house tables service never does.
 */
@Configuration(proxyBeanMethods = false)
public class HouseTablesH2Configuration {

  /** The settings of every stand-in database; {@code HouseTablesH2DatabaseTest} pins the reuse. */
  private static final String DATABASE_SETTINGS =
      ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;OPTIMIZE_REUSE_RESULTS=FALSE";

  /** A name of its own per application context, so two services in one JVM share no rows. */
  @Bean
  public DataSource houseTablesH2DataSource() {
    return DataSourceBuilder.create()
        .driverClassName("org.h2.Driver")
        .url("jdbc:h2:mem:house-tables-" + UUID.randomUUID() + DATABASE_SETTINGS)
        .username("sa")
        .password("")
        .build();
  }
}
