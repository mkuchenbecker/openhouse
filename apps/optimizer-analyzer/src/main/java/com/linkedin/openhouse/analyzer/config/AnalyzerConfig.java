package com.linkedin.openhouse.analyzer.config;

import com.linkedin.openhouse.analyzer.client.OptimizerServiceClient;
import com.linkedin.openhouse.analyzer.client.TablesServiceClient;
import com.linkedin.openhouse.tables.client.api.DatabaseApi;
import com.linkedin.openhouse.tables.client.api.TableApi;
import com.linkedin.openhouse.tables.client.invoker.ApiClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.reactive.function.client.WebClient;

/** Spring configuration for the Analyzer's service clients and retry logic. */
@Configuration
public class AnalyzerConfig {

  @Value("${tables.base-uri}")
  private String tablesBaseUri;

  @Value("${optimizer.base-uri}")
  private String optimizerBaseUri;

  @Value("${tables.auth-token:}")
  private String tablesAuthToken;

  @Bean
  public TablesServiceClient tablesServiceClient() {
    ApiClient apiClient = new ApiClient();
    apiClient.setBasePath(tablesBaseUri);
    if (tablesAuthToken != null && !tablesAuthToken.isEmpty()) {
      apiClient.addDefaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + tablesAuthToken);
    }
    return new TablesServiceClient(
        new TableApi(apiClient), new DatabaseApi(apiClient), retryTemplate());
  }

  @Bean
  public OptimizerServiceClient optimizerServiceClient() {
    WebClient webClient = WebClient.builder().baseUrl(optimizerBaseUri).build();
    return new OptimizerServiceClient(webClient);
  }

  @Bean
  public RetryTemplate retryTemplate() {
    RetryTemplate template = new RetryTemplate();
    SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy();
    retryPolicy.setMaxAttempts(3);
    template.setRetryPolicy(retryPolicy);
    FixedBackOffPolicy backOffPolicy = new FixedBackOffPolicy();
    backOffPolicy.setBackOffPeriod(1000L);
    template.setBackOffPolicy(backOffPolicy);
    return template;
  }
}
