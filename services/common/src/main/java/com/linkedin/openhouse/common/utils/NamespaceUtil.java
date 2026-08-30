package com.linkedin.openhouse.common.utils;

import java.util.regex.Pattern;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.exceptions.ValidationException;

/**
 * Helpers that encode OpenHouse's namespace contract for {@link Namespace} arguments.
 *
 * <p>OpenHouse currently identifies base tables with {@code database.table}, so a "table namespace"
 * is exactly one level (the database) and an "operation namespace" — the {@code Namespace} argument
 * to a database-scoped catalog method — is at most one level (with the empty namespace acting as a
 * sentinel for "across all databases").
 *
 * <p>The two predicates are intentionally separate concepts, not stricter/looser flavors of the
 * same rule. If OpenHouse ever changes its namespace shape (e.g. to support {@code
 * catalog.database.table}), the rule bodies update here and the call-site names continue to read
 * correctly.
 */
public final class NamespaceUtil {
  private NamespaceUtil() {}

  /**
   * Maximum number of namespace levels OpenHouse accepts. A "table namespace" is exactly this deep;
   * an "operation namespace" is at most this deep. Adjust here if OpenHouse ever extends its
   * namespace contract (e.g. to support {@code catalog.database.table}).
   */
  private static final int MAX_NAMESPACE_DEPTH = 1;

  /**
   * Returns whether {@code namespace} can host an OpenHouse base table.
   *
   * <p>Used by {@code isValidIdentifier(...)} to gate which {@link
   * org.apache.iceberg.catalog.TableIdentifier}s are treated as base tables (vs. metadata-table
   * fallbacks, longer-namespace lookups, etc.). The empty namespace is not a table namespace
   * because there is no database under which to place the table.
   */
  public static boolean isTableNamespace(Namespace namespace) {
    return namespace != null && namespace.levels().length == MAX_NAMESPACE_DEPTH;
  }

  /**
   * Validate that {@code namespace} is a legal argument to a database-scoped catalog operation
   * (e.g. {@code listTables}, {@code searchSoftDeletedTables}).
   *
   * <p>The empty namespace is permitted because callers use it as a sentinel for "across all
   * databases" (e.g. {@code listTables(Namespace.empty())}).
   *
   * @throws ValidationException if {@code namespace} cannot be used as an operation argument
   */
  public static void validateOperationNamespace(Namespace namespace) {
    if (namespace.levels().length > MAX_NAMESPACE_DEPTH) {
      throw new ValidationException(
          "Input namespace has more than one levels " + String.join(".", namespace.levels()));
    }
  }

  /**
   * Default value of the {@code cluster.tables.namespace.max-depth} cluster property. At this
   * default OpenHouse is a mono-namespace catalog and every persisted namespace is a database name,
   * byte for byte identical to what it is today.
   */
  public static final int DEFAULT_MAX_NAMESPACE_DEPTH = MAX_NAMESPACE_DEPTH;

  /** The width of {@code house_table.database_id}, and therefore of an encoded namespace. */
  public static final int MAX_ENCODED_NAMESPACE_LENGTH = 128;

  /** Per-level identifier charset. {@code .} is structurally reserved as the encoding separator. */
  private static final Pattern LEVEL_PATTERN = Pattern.compile("^[a-zA-Z0-9_]+$");

  private static final String SEPARATOR = ".";

  /**
   * Encode a namespace for persistence by dot-joining its levels. {@code
   * encode(Namespace.of("db"))} is {@code "db"}, byte for byte, which is what every seam that
   * persists a namespace today already stores, so this is an identity for every existing database.
   *
   * <p>This is the ONLY persistence encoder. The wire encoding (the {@code 0x1F} unit separator) is
   * a separate, independent concern owned by {@code RESTUtil} at the REST boundary.
   */
  public static String encode(Namespace namespace) {
    return String.join(SEPARATOR, namespace.levels());
  }

  /** Inverse of {@link #encode(Namespace)} over a persisted (or {@code /v1}) namespace string. */
  public static Namespace decode(String encodedNamespace) {
    return Namespace.of(encodedNamespace.split("\\.", -1));
  }

  /**
   * Validate that {@code namespace} is a legal OpenHouse namespace: non-empty, no deeper than
   * {@code maxDepth}, every level within the identifier charset, and an encoded form that fits the
   * {@code database_id} column.
   *
   * @throws ValidationException if any of those does not hold
   */
  public static void validate(Namespace namespace, int maxDepth) {
    if (namespace == null || namespace.isEmpty()) {
      throw new ValidationException("Namespace cannot be empty");
    }
    if (namespace.levels().length > maxDepth) {
      throw new ValidationException(
          "Namespace %s is deeper than the configured maximum depth of %s",
          encode(namespace), maxDepth);
    }
    for (String level : namespace.levels()) {
      if (!LEVEL_PATTERN.matcher(level).matches()) {
        throw new ValidationException(
            "Namespace level %s must match %s", level, LEVEL_PATTERN.pattern());
      }
    }
    if (encode(namespace).length() > MAX_ENCODED_NAMESPACE_LENGTH) {
      throw new ValidationException(
          "Namespace %s exceeds the maximum encoded length of %s",
          encode(namespace), MAX_ENCODED_NAMESPACE_LENGTH);
    }
  }
}
