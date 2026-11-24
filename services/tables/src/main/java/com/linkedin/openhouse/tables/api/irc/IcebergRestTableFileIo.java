package com.linkedin.openhouse.tables.api.irc;

import com.linkedin.openhouse.cluster.storage.StorageManager;
import com.linkedin.openhouse.cluster.storage.StorageType;
import com.linkedin.openhouse.internal.catalog.fileio.FileIOManager;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.Table;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The per-table {@code io-impl} of an Iceberg REST table response, for a table that the storage
 * selector placed on another storage type than the cluster's default (work item V20).
 *
 * <p>The config route advertises the default storage's FileIO class as the catalog's {@code
 * io-impl} ({@link IcebergRestApiHandler#config}). An Iceberg client lays a table response's {@code
 * config} over its catalog properties when it builds that table's FileIO (on 1.12 and on LinkedIn's
 * 1.5.2 alike), so a table whose storage needs another class is given that class here. Nothing else
 * is sent: as on the config route, only the class name, never the storage's endpoint, bucket,
 * account or credential.
 *
 * <p>A table whose storage has the default's class gets no entry, so its responses are as they
 * were. A storage type with no known client class ({@link IcebergRestApiHandler#clientFileIo}) gets
 * no entry either, rather than a class that cannot read it.
 */
@Component
public class IcebergRestTableFileIo {

  @Autowired private StorageManager storageManager;

  @Autowired private FileIOManager fileIOManager;

  /** The entry for {@code table}, by the storage of the FileIO the service reads it with. */
  public Map<String, String> config(Table table) {
    return config(fileIOManager.getStorage(table.io()).getType());
  }

  /** The entry for a table on storage of type {@code tableStorageType}. */
  public Map<String, String> config(StorageType.Type tableStorageType) {
    return config(tableStorageType, storageManager.getDefaultStorage().getType());
  }

  /**
   * {@code base} with the entry for {@code table} added; {@code base} itself when there is none.
   */
  public Map<String, String> withConfig(Map<String, String> base, Table table) {
    Map<String, String> entry = config(table);
    if (entry.isEmpty()) {
      return base;
    }
    Map<String, String> merged = new HashMap<>(base);
    merged.putAll(entry);
    return merged;
  }

  /**
   * {@code io-impl} set to the class of {@code tableStorageType} when it has one and the catalog's
   * default, the class of {@code defaultStorageType}, is not that class; empty otherwise.
   */
  static Map<String, String> config(
      StorageType.Type tableStorageType, StorageType.Type defaultStorageType) {
    Optional<String> tableClass = IcebergRestApiHandler.clientFileIo(tableStorageType);
    if (!tableClass.isPresent()
        || tableClass.equals(IcebergRestApiHandler.clientFileIo(defaultStorageType))) {
      return Collections.emptyMap();
    }
    return Collections.singletonMap(CatalogProperties.FILE_IO_IMPL, tableClass.get());
  }
}
