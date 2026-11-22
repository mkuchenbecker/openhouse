package com.linkedin.openhouse.spark.capabilities;

import java.util.HashMap;
import java.util.Map;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.rest.RESTClient;
import org.apache.iceberg.rest.responses.ConfigResponse;
import org.mockito.Mockito;

/**
 * Iceberg's own {@link RESTCatalog}, unmodified, over a client that answers only the config call.
 * The config response's overrides carry {@code openhouse.capabilities} with the value of the
 * catalog property {@value #SERVER_ADVERTISES} when it is set, as a server that advertises them
 * would.
 */
public class FakeRestCatalog extends RESTCatalog {
  public static final String SERVER_ADVERTISES = "test.server-advertises";

  public FakeRestCatalog() {
    super(FakeRestCatalog::client);
  }

  private static RESTClient client(Map<String, String> properties) {
    Map<String, String> overrides = new HashMap<>();
    if (properties.containsKey(SERVER_ADVERTISES)) {
      overrides.put("openhouse.capabilities", properties.get(SERVER_ADVERTISES));
    }
    ConfigResponse config = ConfigResponse.builder().withOverrides(overrides).build();
    return Mockito.mock(
        RESTClient.class,
        invocation -> {
          for (Object argument : invocation.getArguments()) {
            if (argument == ConfigResponse.class) {
              return config;
            }
          }
          if (invocation.getMethod().getReturnType() == void.class) {
            return null;
          }
          throw new UnsupportedOperationException(
              "FakeRestCatalog answers only the config call: " + invocation.getMethod());
        });
  }
}
