package com.linkedin.openhouse.tables.services;

import java.util.List;
import lombok.Value;

/**
 * The outcome of an update to a namespace's properties, partitioned exactly the way Iceberg's own
 * {@code CatalogHandlers.updateNamespaceProperties} partitions it: every requested removal that was
 * present is {@code removed}, every requested removal that was absent is {@code missing}, and every
 * requested update key is {@code updated}.
 */
@Value
public class NamespacePropertiesUpdateResult {
  List<String> updated;
  List<String> removed;
  List<String> missing;
}
