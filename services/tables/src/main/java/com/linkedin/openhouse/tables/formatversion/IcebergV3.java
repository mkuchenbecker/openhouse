package com.linkedin.openhouse.tables.formatversion;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.SortOrder;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.types.Types;

/**
 * What the Iceberg on the classpath knows of format version 3, read without linking to anything
 * that exists only on the Iceberg 1.12 line, so this service compiles and runs on LinkedIn's
 * Iceberg 1.5.2 reference line too (which cannot read or write format v3).
 *
 * <p>Everything here is a probe or a reflective read, as the Java client's {@code IcebergLine} is:
 * the 1.12-only accessors ({@code TableMetadata.nextRowId()}, {@code
 * TableMetadata.encryptionKeys()}, {@code Snapshot.keyId()}) are looked up once by name. The
 * v3-only types are {@link FormatV3Types}'.
 */
public final class IcebergV3 {

  /** The format version this class is about. */
  public static final int VERSION = 3;

  private static final boolean SUPPORTED = probe();

  private static final Method NEXT_ROW_ID = method(TableMetadata.class, "nextRowId");

  private static final Method ENCRYPTION_KEYS = method(TableMetadata.class, "encryptionKeys");

  private static final Method SNAPSHOT_KEY_ID = method(Snapshot.class, "keyId");

  private IcebergV3() {}

  /** Whether the Iceberg on the classpath builds format v3 table metadata. */
  public static boolean supported() {
    return SUPPORTED;
  }

  /** The table's {@code next-row-id}; 0 where this Iceberg has no row lineage. */
  public static long nextRowId(TableMetadata metadata) {
    Object value = invoke(NEXT_ROW_ID, metadata);
    return value == null ? 0L : (Long) value;
  }

  /** Whether {@code metadata} holds encryption keys ({@code encryption-keys}). */
  public static boolean hasEncryptionKeys(TableMetadata metadata) {
    return !encryptionKeys(metadata).isEmpty();
  }

  /** The table's encryption keys; empty where this Iceberg has none. */
  public static Collection<?> encryptionKeys(TableMetadata metadata) {
    Object value = invoke(ENCRYPTION_KEYS, metadata);
    return value == null ? Collections.emptyList() : (Collection<?>) value;
  }

  /** The id of the key the snapshot's manifest list is encrypted with, or {@code null}. */
  public static String keyId(Snapshot snapshot) {
    Object value = invoke(SNAPSHOT_KEY_ID, snapshot);
    return value == null ? null : value.toString();
  }

  private static boolean probe() {
    try {
      TableMetadata.newTableMetadata(
          new Schema(Types.NestedField.optional(1, "probe", Types.IntegerType.get())),
          PartitionSpec.unpartitioned(),
          SortOrder.unsorted(),
          "openhouse:/format-v3-probe",
          Collections.singletonMap(TableProperties.FORMAT_VERSION, Integer.toString(VERSION)));
      return true;
    } catch (IllegalArgumentException e) {
      // Preconditions.checkArgument: "Unsupported format version: v3".
      return false;
    }
  }

  private static Method method(Class<?> type, String name) {
    try {
      return type.getMethod(name);
    } catch (NoSuchMethodException e) {
      return null;
    }
  }

  private static Object invoke(Method method, Object target) {
    if (method == null) {
      return null;
    }
    try {
      return method.invoke(target);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Cannot read " + method.getName() + " of " + target, e);
    }
  }
}
