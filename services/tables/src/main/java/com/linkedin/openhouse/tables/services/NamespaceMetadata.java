package com.linkedin.openhouse.tables.services;

import java.util.Map;
import lombok.Value;
import org.apache.iceberg.catalog.Namespace;

/**
 * A namespace as the store holds it: the stored spelling plus its properties.
 *
 * <p>The stored {@link Namespace} is returned rather than the one the caller asked with because
 * namespace lookup is case-insensitive while listing is not. Echoing the request would let {@code
 * GET /namespaces/mydb} answer for the stored {@code MyDb} under a name that appears in no listing
 * and that {@code DELETE} would then destroy.
 */
@Value
public class NamespaceMetadata {
  Namespace namespace;
  Map<String, String> properties;
}
