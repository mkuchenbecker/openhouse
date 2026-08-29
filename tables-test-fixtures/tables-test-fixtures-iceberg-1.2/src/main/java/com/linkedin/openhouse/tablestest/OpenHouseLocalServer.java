package com.linkedin.openhouse.tablestest;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Standalone embedded OH server that can be started and stopped from any Java code (to be used for
 * testing). Users have an option of providing a custom portNo, otherwise it will automatically be
 * determined. Once the server has started with {@link OpenHouseLocalServer#start()}, portNo can be
 * queried using {@link OpenHouseLocalServer#getPort()}. The server can be stopped with {@link
 * OpenHouseLocalServer#stop()}.
 *
 * <p>A test that needs the server configured differently from the default — a feature switched on,
 * say — passes those properties to {@link OpenHouseLocalServer#OpenHouseLocalServer(Map)} and runs
 * its own instance rather than the shared one. Two instances do not collide: each takes an
 * OS-assigned port, and Spring Boot gives every context its own uniquely-named in-memory database,
 * so neither one's schema creation touches the other's data.
 */
public class OpenHouseLocalServer {

  private int port;
  private ConfigurableApplicationContext appContext;
  private final Map<String, String> properties;

  /** Create server with OS-assigned port (determined at startup time). */
  public OpenHouseLocalServer() {
    this(0, Collections.emptyMap());
  }

  public OpenHouseLocalServer(int port) {
    this(port, Collections.emptyMap());
  }

  /**
   * Create server with OS-assigned port and additional Spring properties.
   *
   * @param properties applied as Spring default properties, so anything the application's own
   *     configuration sets explicitly still wins; use this for switches the application leaves
   *     unset, such as {@code cluster.tables.views.enabled}
   */
  public OpenHouseLocalServer(Map<String, String> properties) {
    this(0, properties);
  }

  public OpenHouseLocalServer(int port, Map<String, String> properties) {
    this.port = port;
    this.appContext = null;
    this.properties = new HashMap<>(properties);
  }

  /** Start the embedded OH server with tomcat fix */
  public void start() {
    start(true);
  }

  /** Start the embedded OH server */
  public synchronized void start(boolean applyTomcatFix) {
    if (appContext == null || !appContext.isActive()) {
      SpringApplication application = new SpringApplication(SpringH2TestApplication.class);
      Map<String, Object> defaults = new HashMap<>(properties);
      defaults.put("server.port", String.valueOf(port));
      application.setDefaultProperties(defaults);
      if (applyTomcatFix) {
        fixTomcatInstantiation();
      }
      appContext = application.run();
      this.port = ((WebServerApplicationContext) appContext).getWebServer().getPort();
    } else {
      throw new IllegalArgumentException(
          "OpenHouse test server has already been started, please stop the application first with OpenHouseJavaItestService#Start");
    }
  }

  /** Stop the embedded OH server */
  public synchronized void stop() {
    if (appContext != null && appContext.isActive()) {
      SpringApplication.exit(appContext);
    } else {
      throw new IllegalArgumentException(
          "OpenHouse test server has not been started yet, please start the application first with OpenHouseJavaItestService#Stop");
    }
  }

  /**
   * URLStreamHandlerFactory can be set by Spark or any other libraries. This method ensures that
   * the URLStreamHandlerFactory is set to Tomcat's implementation before starting the embedded
   * Tomcat, otherwise it will instantiate the implementation without setting default
   * URLStreamHandlerFactory.
   *
   * <p>This is springboot's recommended fix: please see {@link
   * https://github.com/spring-projects/spring-boot/issues/21535}
   */
  private void fixTomcatInstantiation() {
    try {
      org.apache.catalina.webresources.TomcatURLStreamHandlerFactory.register();
    } catch (Error e) {
      org.apache.catalina.webresources.TomcatURLStreamHandlerFactory.disable();
    }
  }

  /**
   * get port number in localhost where OH server is started
   *
   * @return int port number
   */
  public int getPort() {
    return port;
  }
}
