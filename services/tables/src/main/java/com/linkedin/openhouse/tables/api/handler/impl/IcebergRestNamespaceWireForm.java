package com.linkedin.openhouse.tables.api.handler.impl;

import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.rest.RESTUtil;

/**
 * Reads the Iceberg REST wire form of a multi-level namespace out of a request.
 *
 * <p>The Iceberg REST spec joins a namespace's levels with the {@code 0x1F} unit separator and
 * percent-encodes it in the URL, so a client sends {@code parent%1Fchild}. Two things then happen
 * before that string reaches a handler, and they do not compose:
 *
 * <ul>
 *   <li>Spring hands over path variables and query parameters already percent-decoded, so the
 *       separator arrives as a literal {@code 0x1F} character.
 *   <li>{@link RESTUtil#decodeNamespace} on the Iceberg version this service is pinned to splits on
 *       the three-character text {@code "%1F"} rather than on the character. That text is gone by
 *       the time it is called, so it finds no separator and answers with a single level whose name
 *       contains a control character — which then fails validation as a namespace that does not
 *       exist.
 * </ul>
 *
 * <p>Restoring the escaped separator before delegating is what closes that gap: {@link RESTUtil}
 * splits where the client asked it to, and each level's own percent-encoding is still decoded by
 * {@link RESTUtil}, which is the only thing that knows how it was written. Decoding the whole
 * string here instead would decode every level twice.
 *
 * <p>A single-level namespace carries no separator, so this is a no-op on one and the shipped depth
 * of 1 is unaffected.
 */
final class IcebergRestNamespaceWireForm {

  private static final String UNIT_SEPARATOR = "\u001F";

  private static final String ESCAPED_UNIT_SEPARATOR = "%1F";

  private IcebergRestNamespaceWireForm() {}

  /** Decode {@code wireNamespace} as it arrives from a path variable or a query parameter. */
  static Namespace decode(String wireNamespace) {
    return RESTUtil.decodeNamespace(
        wireNamespace == null
            ? null
            : wireNamespace.replace(UNIT_SEPARATOR, ESCAPED_UNIT_SEPARATOR));
  }
}
