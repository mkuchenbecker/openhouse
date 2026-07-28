package com.linkedin.openhouse.optimizer.analyzer;

import java.util.List;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** Entry point for the Optimizer Analyzer application. */
@SpringBootApplication
@EntityScan(basePackages = "com.linkedin.openhouse.optimizer.db")
@EnableJpaRepositories(basePackages = "com.linkedin.openhouse.optimizer.repository")
public class AnalyzerApplication {

  public static void main(String[] args) {
    SpringApplication.run(AnalyzerApplication.class, args);
  }

  /**
   * Runs the analyzer once per registered {@link OperationAnalyzer} per process invocation, then
   * once per registered {@link DirectoryOperationAnalyzer}. Per-table analyzers iterate {@code
   * table_stats}; directory (database-scoped) analyzers enumerate databases. Each call is scoped to
   * one operation type. Directory analyzers gated off by their per-op opt-in are no-ops.
   */
  @Bean
  public CommandLineRunner run(
      AnalyzerRunner runner,
      List<OperationAnalyzer> analyzers,
      DirectoryDeletionAnalyzerRunner directoryRunner,
      List<DirectoryOperationAnalyzer> directoryAnalyzers) {
    return args -> {
      analyzers.forEach(a -> runner.analyze(a.getOperationType()));
      directoryAnalyzers.forEach(a -> directoryRunner.analyze(a.getOperationType()));
    };
  }
}
