package com.linkedin.openhouse.irc.rck;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.linkedin.openhouse.common.security.DummyTokenInterceptor;
import com.linkedin.openhouse.housetables.client.model.ToggleStatus;
import com.linkedin.openhouse.tables.authorization.AuthorizationHandler;
import com.linkedin.openhouse.tables.authorization.OpaAuthorizationHandler;
import com.linkedin.openhouse.tables.authorization.Privileges;
import com.linkedin.openhouse.tables.model.DatabaseDto;
import com.linkedin.openhouse.tables.model.TableDto;
import com.linkedin.openhouse.tables.services.ViewsFeatureGate;
import com.linkedin.openhouse.tables.toggle.model.TableToggleStatus;
import com.linkedin.openhouse.tables.toggle.repository.ToggleStatusesRepository;
import com.linkedin.openhouse.tablestest.SpringH2TestApplication;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The tables service for Apache Iceberg's REST compatibility kit, in its own JVM: the service as
 * the Spark 3.5 tests embed it ({@link SpringH2TestApplication}: in-memory H2 house tables, local
 * file system storage in a {@link ServiceWarehouse}), with the dummy token interceptor and an
 * authorization handler that allows every principal everything.
 *
 * <p>Views are off by default per database, natively; the views feature gate is opened for every
 * namespace the kit's table and view suites use, as a {@code views} toggle rule does. Nothing is
 * seeded: the kit creates and drops what it needs.
 *
 * <p>It writes {@code ready.json} (port, warehouse and token) and serves until its standard input
 * closes, which happens when the test JVM stops it or dies. Usage: {@code RestCompatKitService
 * <work directory>}.
 */
public final class RestCompatKitService {

  static final String PRINCIPAL = "rck_user";

  /**
   * The namespaces Iceberg's {@code CatalogTests}, {@code ViewCatalogTests} and the kit's {@code
   * RCKUtils} name. OpenHouse database ids are lower case letters, digits and underscores, so the
   * kit's {@code new.db}, {@code new/db} and {@code non-existing} are not database ids at all.
   */
  static final List<String> KIT_NAMESPACES =
      Arrays.asList(
          "ns", "ns1", "ns2", "ns_1", "ns_2", "newdb", "newdb_1", "newdb_2", "other_ns", "parent");

  private RestCompatKitService() {}

  public static void main(String[] args) throws Exception {
    Path work = Paths.get(args[0]).toAbsolutePath();
    // Not under the work directory: a metadata location has to fit House Tables' 255 characters.
    Path warehouse = ServiceWarehouse.create();
    ConfigurableApplicationContext context =
        new SpringApplication(SpringH2TestApplication.class, AllowAllConfig.class)
            .run(
                "--server.port=0",
                "--server.shutdown=immediate",
                "--server.tomcat.basedir=" + work.resolve("tomcat"),
                "--cluster.security.token.interceptor.classname="
                    + DummyTokenInterceptor.class.getName(),
                "--cluster.storages.default-type=local",
                "--cluster.storages.types.local.endpoint=file://",
                "--cluster.storages.types.local.rootpath=" + warehouse,
                // Every request and its status, unbuffered, to read what the kit sent.
                "--server.tomcat.accesslog.enabled=true",
                "--server.tomcat.accesslog.directory=" + work.resolve("access"),
                "--server.tomcat.accesslog.prefix=access",
                "--server.tomcat.accesslog.suffix=.log",
                "--server.tomcat.accesslog.rotate=false",
                "--server.tomcat.accesslog.buffered=false",
                "--server.tomcat.accesslog.pattern=%m %U%q %s");
    ToggleStatusesRepository toggles = context.getBean(ToggleStatusesRepository.class);
    for (String namespace : KIT_NAMESPACES) {
      toggles.save(
          TableToggleStatus.builder()
              .databaseId(namespace)
              .tableId(ViewsFeatureGate.GATE_PROBE_TABLE_ID)
              .featureId("views")
              .toggleStatusEnum(ToggleStatus.StatusEnum.ACTIVE)
              .build());
    }
    int port = ((WebServerApplicationContext) context).getWebServer().getPort();

    Map<String, Object> ready = new LinkedHashMap<>();
    ready.put("port", port);
    ready.put("warehouse", warehouse.toString());
    ready.put("token", new DummyTokenInterceptor.DummySecurityJWT(PRINCIPAL).buildNoopJWT());
    Path tmp = work.resolve("ready.json.tmp");
    new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(tmp.toFile(), ready);
    Files.move(tmp, work.resolve("ready.json"), StandardCopyOption.ATOMIC_MOVE);
    System.out.println("REST compatibility kit service ready on port " + port);

    // Serve until the parent closes our standard input (it stopped us, or it died).
    InputStream in = System.in;
    while (in.read() != -1) {
      // ignore
    }
    SpringApplication.exit(context);
    System.exit(0);
  }

  /** Allows every principal everything, whatever the OPA settings: the kit tests the protocol. */
  @org.springframework.context.annotation.Configuration
  static class AllowAllConfig {
    @Bean
    @Primary
    AuthorizationHandler allowAllAuthorizationHandler() {
      return new OpaAuthorizationHandler() {
        @Override
        public boolean checkAccessDecision(String principal, TableDto table, Privileges privilege) {
          return true;
        }

        @Override
        public boolean checkAccessDecision(
            String principal, DatabaseDto database, Privileges privilege) {
          return true;
        }
      };
    }
  }
}
