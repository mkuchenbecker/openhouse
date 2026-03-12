package com.linkedin.openhouse.analyzer.config;

import com.linkedin.openhouse.analyzer.client.HtsClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.reactive.function.client.WebClient;

/** Spring configuration for the Analyzer's service clients and retry logic. */
@Configuration
public class AnalyzerConfig {

  @Value("${hts.base-uri}")
  private String htsBaseUri;

  @Value("${retry.max-attempts:3}")
  private int retryMaxAttempts;

  @Value("${retry.backoff-period-ms:1000}")
  private long retryBackoffPeriodMs;

  @Bean
  public HtsClient htsClient() {
    WebClient webClient = WebClient.builder().baseUrl(htsBaseUri).build();
    return new HtsClient(webClient);
  }

  @Bean
  public RetryTemplate retryTemplate() {
    RetryTemplate template = new RetryTemplate();
    SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy();
    retryPolicy.setMaxAttempts(retryMaxAttempts);
    template.setRetryPolicy(retryPolicy);
    FixedBackOffPolicy backOffPolicy = new FixedBackOffPolicy();
    backOffPolicy.setBackOffPeriod(retryBackoffPeriodMs);
    template.setBackOffPolicy(backOffPolicy);
    return template;
  }
}
