package com.linkedin.openhouse.internal.catalog.view;

import static com.linkedin.openhouse.internal.catalog.InternalCatalogMetricsConstant.METRICS_PREFIX;

import com.linkedin.openhouse.cluster.metrics.micrometer.MetricsReporter;
import com.linkedin.openhouse.cluster.storage.StorageType;
import com.linkedin.openhouse.internal.catalog.HouseTableRowLimits;
import com.linkedin.openhouse.internal.catalog.fileio.FileIOManager;
import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Collections;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the view commit engine and codec. */
@Configuration
public class ViewCommitEngineConfiguration {

  @Bean
  public ViewMetadataCodec viewMetadataCodec() {
    return new IcebergViewMetadataCodec();
  }

  @Bean
  public ViewCommitEngine viewCommitEngine(
      HouseTableRepository houseTableRepository,
      FileIOManager fileIOManager,
      ViewMetadataCodec viewMetadataCodec,
      StorageType storageType,
      MeterRegistry meterRegistry,
      ObjectProvider<HouseTableRowLimits> houseTableRowLimits) {
    return new ViewCommitEngineImpl(
        houseTableRepository,
        fileIOManager,
        viewMetadataCodec,
        storageType,
        new MetricsReporter(meterRegistry, METRICS_PREFIX, Collections.emptyList()),
        houseTableRowLimits.getIfAvailable(() -> HouseTableRowLimits.DEFAULT));
  }
}
