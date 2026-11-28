package com.linkedin.openhouse.tables;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;

/**
 * Takes a test application context's meter registries out of Micrometer's static global registry
 * when the context closes. Spring Boot adds every context's registry to that global composite and
 * never takes it out, and the service records some metrics on the global registry, so in a test JVM
 * that starts and closes many contexts every closed context's registry, and every meter recorded on
 * it since, would stay reachable. A service runs one context, so it has no such growth; this is for
 * the tests' application classes only.
 */
public class ReleaseMeterRegistriesOnClose implements ApplicationListener<ContextClosedEvent> {

  @Override
  public void onApplicationEvent(ContextClosedEvent event) {
    event.getApplicationContext().getBeansOfType(MeterRegistry.class).values().stream()
        .filter(registry -> registry != Metrics.globalRegistry)
        .forEach(Metrics::removeRegistry);
  }
}
