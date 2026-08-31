package com.linkedin.openhouse.common.utils;

import com.linkedin.openhouse.common.api.validator.ValidatorConstants;
import java.util.regex.Pattern;
import org.apache.iceberg.MetadataTableType;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
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
   * Returns whether {@code namespace} can host an OpenHouse base table under a catalog configured
   * with {@code maxDepth} namespace levels.
   *
   * <p>Used by {@code isValidIdentifier(...)} to gate which {@link
   * org.apache.iceberg.catalog.TableIdentifier}s are treated as base tables (vs. metadata-table
   * fallbacks, longer-namespace lookups, etc.). The empty namespace is not a table namespace
   * because there is no database under which to place the table.
   *
   * <p>A table namespace is exactly {@code maxDepth} levels rather than "at most": a table lives at
   * the deepest level the catalog admits, so a shallower namespace is a container, not a table's
   * parent. At the shipped {@code maxDepth} of 1 that is the same predicate as before — exactly one
   * level — which is the whole compatibility claim of wiring this to configuration.
   *
   * @param maxDepth the value of the {@code cluster.tables.namespace.max-depth} cluster property
   */
  public static boolean isTableNamespace(Namespace namespace, int maxDepth) {
    return namespace != null && namespace.levels().length == maxDepth;
  }

  /**
   * Validate that {@code namespace} is a legal argument to a database-scoped catalog operation
   * (e.g. {@code listTables}, {@code searchSoftDeletedTables}) under a catalog configured with
   * {@code maxDepth} namespace levels.
   *
   * <p>The empty namespace is permitted because callers use it as a sentinel for "across all
   * databases" (e.g. {@code listTables(Namespace.empty())}).
   *
   * @param maxDepth the value of the {@code cluster.tables.namespace.max-depth} cluster property
   * @throws ValidationException if {@code namespace} cannot be used as an operation argument
   */
  public static void validateOperationNamespace(Namespace namespace, int maxDepth) {
    if (namespace.levels().length > maxDepth) {
      // "one" rather than "1" at the shipped depth. This is a live rejection message, worded
      // identically by the client-side OpenHouseCatalog, and wiring the bound to configuration is
      // not supposed to be observable at depth 1 — not even here.
      String depth = maxDepth == 1 ? "one" : Integer.toString(maxDepth);
      throw new ValidationException(
          "Input namespace has more than "
              + depth
              + " levels "
              + String.join(".", namespace.levels()));
    }
  }

  /**
   * Default value of the {@code cluster.tables.namespace.max-depth} cluster property, as a
   * compile-time String constant so a {@code @Value} placeholder default can name it rather than
   * spell the number a second time.
   */
  public static final String DEFAULT_MAX_NAMESPACE_DEPTH_LITERAL = "1";

  /**
   * The same default as an int. At this default OpenHouse is a mono-namespace catalog and every
   * persisted namespace is a database name, byte for byte identical to what it is today.
   */
  public static final int DEFAULT_MAX_NAMESPACE_DEPTH =
      Integer.parseInt(DEFAULT_MAX_NAMESPACE_DEPTH_LITERAL);

  /** The width of {@code house_table.database_id}, and therefore of an encoded namespace. */
  public static final int MAX_ENCODED_NAMESPACE_LENGTH = 128;

  /**
   * Per-level identifier charset. {@code .} is structurally reserved as the encoding separator.
   * Taken from {@link ValidatorConstants} rather than restated, so a level cannot be legal here and
   * illegal at the seam that carries it across the wire.
   */
  private static final Pattern LEVEL_PATTERN =
      Pattern.compile(ValidatorConstants.ALPHA_NUM_UNDERSCORE_REGEX);

  private static final String SEPARATOR = ".";

  private static final char SEPARATOR_CHAR = '.';

  /**
   * The character immediately after {@link #SEPARATOR} in code point order, used as the exclusive
   * upper bound of a subtree range.
   */
  private static final String SEPARATOR_SUCCESSOR = "/";

  /** The charset of an encoded namespace: {@link #LEVEL_PATTERN} levels joined by the separator. */
  private static final Pattern NAMESPACE_ID_PATTERN =
      Pattern.compile(ValidatorConstants.NAMESPACE_ID_REGEX);

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

  /**
   * Whether {@code encodedNamespace} is within the charset every seam that carries an encoded
   * namespace enforces: one or more identifier levels, separated by the {@code .} the encoder
   * writes.
   *
   * <p>This is deliberately NOT the table-id charset. A table id must stay free of {@code .} so
   * that {@code db.table.history} keeps exactly one reading, and the two charsets are separate
   * constants for that reason. Every namespace call site moved onto this predicate together: a
   * charset one service widens and another does not is an identifier one accepts and the other
   * rejects.
   *
   * <p>At depth 1 an encoded namespace has no separator, so this accepts and rejects exactly what
   * {@code ALPHA_NUM_UNDERSCORE_REGEX} did — the {@code .} is the only addition.
   */
  public static boolean isValidNamespaceIdentifier(String encodedNamespace) {
    return encodedNamespace != null && NAMESPACE_ID_PATTERN.matcher(encodedNamespace).matches();
  }

  /**
   * Inclusive lower bound of the range of encoded namespaces in the subtree under {@code
   * encodedParent}: every descendant is encoded as the parent, the separator, and at least one more
   * character.
   *
   * <p>{@code encodedParent} itself sorts strictly below this bound, so the parent is never its own
   * child.
   */
  public static String subtreeLowerBound(String encodedParent) {
    return encodedParent + SEPARATOR;
  }

  /**
   * Exclusive upper bound of that range. {@code .} is {@code 0x2E} and the character after it is
   * {@code /} ({@code 0x2F}), which no level may contain, so {@code [parent + ".", parent + "/")}
   * is exactly the set of strings with {@code parent + "."} as a prefix — the subtree, no more and
   * no less. Expressing the subtree as a bounded range rather than a {@code LIKE} prefix matters:
   * the identifier charset admits {@code _}, which SQL {@code LIKE} reads as a single-character
   * wildcard, so {@code LIKE 'my_db.%'} would also match {@code myXdb.a}.
   */
  public static String subtreeUpperBound(String encodedParent) {
    return encodedParent + SEPARATOR_SUCCESSOR;
  }

  /**
   * Whether {@code encodedCandidate} is a direct child of {@code encodedParent} — in the subtree,
   * and exactly one level deeper. Grandchildren are in the range but not children.
   */
  public static boolean isDirectChild(String encodedParent, String encodedCandidate) {
    String prefix = subtreeLowerBound(encodedParent);
    return encodedCandidate != null
        && encodedCandidate.startsWith(prefix)
        && encodedCandidate.indexOf(SEPARATOR_CHAR, prefix.length()) < 0
        && encodedCandidate.length() > prefix.length();
  }

  /**
   * The shallowest namespace at which an identifier can be read as a metadata table.
   *
   * <p>Iceberg addresses a table's metadata tables by appending the type to the table's own
   * identifier, so {@code db.tbl.history} is the {@code history} metadata table of {@code db.tbl}
   * and its namespace is {@code db.tbl} — two levels. One level below that, {@code db.history}, is
   * a table called {@code history} in the database {@code db}, and always has been.
   */
  private static final int METADATA_TABLE_MIN_NAMESPACE_DEPTH = 2;

  /**
   * Whether {@code name} is one of Iceberg's metadata table types.
   *
   * <p>The set is read from {@link MetadataTableType} rather than restated here, so a metadata
   * table Iceberg adds in a later release cannot quietly become a creatable table name. The lookup
   * is case-insensitive because {@link MetadataTableType#from} is, and because that is the reading
   * Iceberg itself will apply to the same string.
   */
  public static boolean isMetadataTableName(String name) {
    return name != null && MetadataTableType.from(name) != null;
  }

  /**
   * Whether {@code identifier} names a metadata table rather than a base table.
   *
   * <p>Once namespaces can nest, {@code db.tbl.history} has two possible readings: the {@code
   * history} metadata table of {@code db.tbl}, or a base table named {@code history} in the
   * namespace {@code db.tbl}. This predicate picks the first, and {@link
   * #collidesWithMetadataTable} is what makes that choice safe rather than arbitrary — a base table
   * that would occupy such an identifier is refused at creation, so the second reading names
   * nothing that can exist.
   *
   * <p>The depth floor is what keeps this inert at the shipped depth of 1: there, every base table
   * identifier has a one-level namespace, so {@code db.history} is a table named {@code history}
   * and stays one. Only {@code db.tbl.history}, which was already a metadata-table identifier
   * before nesting, clears the floor.
   */
  public static boolean isMetadataTableIdentifier(TableIdentifier identifier) {
    return identifier != null
        && identifier.namespace().levels().length >= METADATA_TABLE_MIN_NAMESPACE_DEPTH
        && isMetadataTableName(identifier.name());
  }

  /**
   * Whether creating a table {@code tableId} under {@code encodedNamespace} would occupy an
   * identifier {@link #isMetadataTableIdentifier} reads as a metadata table.
   *
   * <p>Deliberately the same predicate, applied to the identifier the create would produce, rather
   * than a second rule stated in parallel: the admission rule and the reading it protects cannot
   * drift apart if there is only one of them.
   */
  public static boolean collidesWithMetadataTable(String encodedNamespace, String tableId) {
    if (encodedNamespace == null
        || encodedNamespace.isEmpty()
        || tableId == null
        || tableId.isEmpty()) {
      return false;
    }
    return isMetadataTableIdentifier(TableIdentifier.of(decode(encodedNamespace), tableId));
  }

  /** Inverse of {@link #encode(Namespace)} over a persisted (or {@code /v1}) namespace string. */
  public static Namespace decode(String encodedNamespace) {
    return Namespace.of(encodedNamespace.split("\\.", -1));
  }

  /**
   * The identifier of the table {@code tableId} inside the namespace {@code encodedNamespace}.
   *
   * <p>{@code TableIdentifier.of(encodedNamespace, tableId)} would be wrong once namespaces nest:
   * it reads the whole encoded namespace as a single level, so {@code ("db.sub", "t")} would name a
   * table in a one-level namespace literally called {@code db.sub} — an identifier the catalog's
   * depth predicate then rejects. Decoding first is what makes the levels come back.
   *
   * <p>At depth 1 an encoded namespace has no separator, so this produces exactly the identifier
   * the two-string overload does, level for level.
   */
  public static TableIdentifier tableIdentifier(String encodedNamespace, String tableId) {
    return TableIdentifier.of(decode(encodedNamespace), tableId);
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
