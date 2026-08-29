package com.linkedin.openhouse.internal.catalog.mapper;

import com.linkedin.openhouse.internal.catalog.model.HouseTable;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.iceberg.relocated.com.google.common.annotations.VisibleForTesting;

/**
 * Utility class used for mapping and persisting {@link
 * com.linkedin.openhouse.internal.catalog.model.HouseTable} as a map internally at OpenHouse.
 */
@Slf4j
public final class HouseTableSerdeUtils {

  @VisibleForTesting public static final String OPENHOUSE_NAMESPACE = "openhouse.";

  @VisibleForTesting
  public static final Predicate<String> IS_OH_PREFIXED = s -> s.startsWith(OPENHOUSE_NAMESPACE);

  /**
   * Fields that exist on {@link HouseTable} but are deliberately not carried in table properties.
   *
   * <p>{@code entityType} is the only one. It says whether a row is a table or a view, and that is
   * decided by which operations class is doing the write, never by the metadata being written. If
   * it were treated as a property-derived field, {@link #HTS_FIELD_NAMES} would accept an {@code
   * openhouse.entityType} key off the wire, and a caller who could get one into a table's
   * properties would be choosing the discriminator for their own row — writing a table that House
   * Tables stores as a view, invisible to a typed table read. The server's reserved-property
   * population never emits this key, so excluding it costs nothing and removes the question of
   * whether some other path could.
   */
  @VisibleForTesting
  public static final Set<String> NON_PROPERTY_FIELD_NAMES = Collections.singleton("entityType");

  public static final Set<String> HTS_FIELD_NAMES =
      Arrays.stream(HouseTable.class.getDeclaredFields())
          .filter(
              field ->
                  Modifier.isPrivate(field.getModifiers())
                      && !Modifier.isStatic(field.getModifiers()))
          .map(Field::getName)
          .filter(name -> !NON_PROPERTY_FIELD_NAMES.contains(name))
          .collect(Collectors.toSet());

  private HouseTableSerdeUtils() {
    // no-op for util class constructor
  }

  @VisibleForTesting
  public static String getCanonicalFieldName(String htsField) {
    return OPENHOUSE_NAMESPACE + htsField;
  }
}
