package com.linkedin.openhouse.tables.e2e.h2;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.linkedin.openhouse.common.test.cluster.PropertyOverrideContextInitializer;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * What a client sees on a cluster whose namespace store has never been verified complete.
 *
 * <p>The failure this is guarding against is not an exception anywhere: it is a 200 with an empty
 * list, on a cluster with a thousand databases in it. So the assertions are about the response a
 * caller actually gets — its status, and whether the body tells whoever reads it what to do.
 *
 * <p>The context is its own: {@code test.namespace-store.verified=false} is part of the context
 * cache key, and the gate latches the first time it is told the store is complete.
 */
@SpringBootTest(
    classes = SpringH2Application.class,
    properties = {
      "cluster.tables.iceberg-rest.enabled=true",
      "test.namespace-store.verified=false"
    })
@AutoConfigureMockMvc
@ContextConfiguration(initializers = PropertyOverrideContextInitializer.class)
@WithMockUser(username = "testUser")
public class UnbackfilledNamespaceStoreTest {

  @Autowired MockMvc mvc;

  @Test
  public void theDatabaseListingRefusesRatherThanReportingAnEmptyCluster() throws Exception {
    mvc.perform(MockMvcRequestBuilders.get("/v1/databases").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.message", Matchers.containsString("/hts/databases/backfill")))
        .andExpect(jsonPath("$.message", Matchers.containsString("verify")));
  }

  @Test
  public void theNamespaceListingRefusesTheSameWay() throws Exception {
    // The Iceberg REST surface has its own error envelope, and the remedy has to survive it: the
    // catch-all it would otherwise fall through to replaces the message with "Internal server
    // error".
    mvc.perform(
            MockMvcRequestBuilders.get("/v1/iceberg/namespaces").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error.message", Matchers.containsString("/hts/databases/backfill")))
        .andExpect(jsonPath("$.error.message", Matchers.containsString("verify")));
  }

  @Test
  public void anExistenceCheckRefusesRatherThanAnsweringNo() throws Exception {
    mvc.perform(MockMvcRequestBuilders.head("/v1/iceberg/namespaces/anydb"))
        .andExpect(status().isServiceUnavailable());
  }
}
