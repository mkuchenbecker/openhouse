package com.linkedin.openhouse.jobs42;

import com.linkedin.openhouse.tablestest.SpringH2TestApplication;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The tables service the Spark 4.2 jobs tests run against, in its own JVM: the service the Spark
 * 3.5 apps tests embed ({@link SpringH2TestApplication} with its defaults), on a free port. It
 * cannot share the Spark 4.2 JVM (Spring Boot 2.7's Jackson 2.13 against Spark 4.2's 2.21).
 *
 * <p>Usage: {@code JobsTestService <work directory> [--spring.arg=value ...]}. The extra arguments
 * go to the application (for example {@code --cluster.iceberg.format-v3.enabled=true}). It writes
 * {@code ready.json} ({@code {"port": N}}) into the work directory and serves until its standard
 * input closes.
 */
public final class JobsTestService {

  private JobsTestService() {}

  public static void main(String[] args) throws Exception {
    Path work = Paths.get(args[0]).toAbsolutePath();
    List<String> appArgs = new ArrayList<>();
    appArgs.add("--server.port=0");
    appArgs.add("--server.shutdown=immediate");
    appArgs.add("--server.tomcat.basedir=" + work.resolve("tomcat"));
    appArgs.addAll(Arrays.asList(args).subList(1, args.length));
    ConfigurableApplicationContext context =
        new SpringApplication(SpringH2TestApplication.class).run(appArgs.toArray(new String[0]));
    int port = ((WebServerApplicationContext) context).getWebServer().getPort();
    Path tmp = work.resolve("ready.json.tmp");
    Files.write(tmp, ("{\"port\": " + port + "}").getBytes(StandardCharsets.UTF_8));
    Files.move(tmp, work.resolve("ready.json"), StandardCopyOption.ATOMIC_MOVE);
    System.out.println("Jobs test service ready on port " + port);
    InputStream in = System.in;
    while (in.read() != -1) {
      // serve until the parent closes our standard input
    }
    SpringApplication.exit(context);
    System.exit(0);
  }
}
