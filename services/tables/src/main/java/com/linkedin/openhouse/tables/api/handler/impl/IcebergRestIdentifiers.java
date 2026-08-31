package com.linkedin.openhouse.tables.api.handler.impl;

import com.linkedin.openhouse.common.api.validator.ValidatorConstants;
import com.linkedin.openhouse.common.utils.NamespaceUtil;
import java.util.regex.Pattern;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.exceptions.ValidationException;

/**
 * The one place the Iceberg REST facade decides what an identifier on a route means.
 *
 * <p>An identifier OpenHouse's charset cannot express names a resource that cannot exist. On a
 * route that addresses a resource, that is a 404 -- the same answer, with the same type and
 * wording, an absent resource gets -- and not the 400 the service layer's own validator raises,
 * which tells a client its request was malformed when all it did was look for something that is not
 * there. For {@code tableExists} and {@code namespaceExists} the distinction is the difference
 * between "no" and an error.
 *
 * <p>The rule is applied here rather than by catching the service layer's validation failure,
 * because that failure also carries things a client genuinely did wrong -- a reserved property key,
 * an oversized property bag -- and a catch wide enough to cover the identifier would report those
 * as "not found" too.
 */
final class IcebergRestIdentifiers {

  private static final Pattern TABLE_NAME_PATTERN =
      Pattern.compile(ValidatorConstants.ALPHA_NUM_UNDERSCORE_REGEX);

  private IcebergRestIdentifiers() {}

  /**
   * Decode the wire form; nothing here judges whether the namespace is one OpenHouse can hold.
   *
   * <p>Two encodings meet at this seam and must not be confused. On the wire the Iceberg REST spec
   * joins a multi-level namespace with the {@code 0x1F} unit separator, which {@link
   * IcebergRestNamespaceWireForm} decodes; in storage OpenHouse joins the same levels with {@code
   * .}, which {@link NamespaceUtil#encode} writes. Every {@code /v1} route -- namespace routes and
   * table routes alike -- reads its namespace through here, so the wire form is understood in one
   * place rather than once per route family.
   */
  static Namespace decode(String encodedNamespace) {
    return IcebergRestNamespaceWireForm.decode(encodedNamespace);
  }

  /**
   * The namespace a route names, or {@link NoSuchNamespaceException} when OpenHouse could not hold
   * one by that name.
   */
  static Namespace readNamespace(String encodedNamespace, int maxDepth) {
    Namespace namespace = decode(encodedNamespace);
    requireHoldable(namespace, maxDepth);
    return namespace;
  }

  /**
   * As {@link #readNamespace}, for a namespace already decoded by the caller.
   *
   * <p>A namespace deeper than the cluster allows is answered in the words the single-level routes
   * have always used, because widening the bound to a configured depth must not change what a
   * client sees while the configuration has not moved. Every other unusable name -- a level outside
   * the identifier charset, an encoded form too long for the {@code database_id} column -- is the
   * 404 an absent namespace gets, worded the same way.
   */
  static void requireHoldable(Namespace namespace, int maxDepth) {
    if (namespace != null && namespace.levels().length > maxDepth) {
      throw new NoSuchNamespaceException(
          maxDepth == 1
              ? "Only single-level namespaces are supported"
              : String.format("Only namespaces up to %s levels deep are supported", maxDepth));
    }
    try {
      NamespaceUtil.validate(namespace, maxDepth);
    } catch (ValidationException e) {
      throw new NoSuchNamespaceException("Namespace does not exist: %s", namespace);
    }
  }

  /**
   * The table a route names, or {@link NoSuchTableException} when OpenHouse could not hold one by
   * that name -- whether the unusable part is the namespace or the table name, because on a table
   * route both name the same absent table.
   */
  static TableIdentifier readTableIdentifier(String encodedNamespace, String table, int maxDepth) {
    return readTableIdentifier(decode(encodedNamespace), table, maxDepth);
  }

  /**
   * As {@link #readTableIdentifier(String, String, int)}, for an identifier a request body carried
   * already decoded -- the rename route names its two tables in JSON rather than in the path.
   */
  static TableIdentifier readTableIdentifier(TableIdentifier identifier, int maxDepth) {
    return readTableIdentifier(identifier.namespace(), identifier.name(), maxDepth);
  }

  private static TableIdentifier readTableIdentifier(
      Namespace namespace, String table, int maxDepth) {
    try {
      NamespaceUtil.validate(namespace, maxDepth);
      if (table == null || !TABLE_NAME_PATTERN.matcher(table).matches()) {
        throw new ValidationException(
            "Table name %s must match %s", table, TABLE_NAME_PATTERN.pattern());
      }
    } catch (ValidationException e) {
      throw new NoSuchTableException(
          "Table does not exist: %s.%s", NamespaceUtil.encode(namespace), table);
    }
    return TableIdentifier.of(namespace, table);
  }
}
