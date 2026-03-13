package com.linkedin.openhouse.analyzer.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

/** Spring configuration for the Analyzer's retry logic. */
@Configuration
public class AnalyzerConfig {

  @Value("${retry.max-attempts:3}")
  private int retryMaxAttempts;

  @Value("${retry.backoff-period-ms:1000}")
  private long retryBackoffPeriodMs;

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
