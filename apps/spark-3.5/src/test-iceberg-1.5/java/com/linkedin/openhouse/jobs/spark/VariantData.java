package com.linkedin.openhouse.jobs.spark;

import java.util.Collection;
import java.util.List;
import org.apache.iceberg.Table;

/**
 * The reference line's copy of the 1.12 line's {@code VariantData}: LinkedIn's Iceberg 1.5.2 has no
 * variant type and no deletion vectors, so the tests that use this skip themselves first.
 */
final class VariantData {
  private VariantData() {}

  static boolean supported() {
    return false;
  }

  static String append(Table table, long[] ids, long tsMicros, String datepartition) {
    throw new UnsupportedOperationException("Iceberg 1.5.2 has no variant type");
  }

  static void delete(Table table, Collection<Long> ids) {
    throw new UnsupportedOperationException("Iceberg 1.5.2 has no deletion vectors");
  }

  static List<String> read(Table table, Long snapshotId) {
    throw new UnsupportedOperationException("Iceberg 1.5.2 has no variant type");
  }
}
