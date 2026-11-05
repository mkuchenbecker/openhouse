package com.linkedin.openhouse.javaclient;

import com.linkedin.openhouse.javaclient.exception.WebClientRequestWithMessageException;
import com.linkedin.openhouse.javaclient.exception.WebClientResponseWithMessageException;
import com.linkedin.openhouse.tables.client.api.DatabaseApi;
import com.linkedin.openhouse.tables.client.model.GetDatabaseResponseBody;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/**
 * Resolves the effective properties of a table: the properties of its database entity overlaid by
 * the table's own properties. Explicit table properties always win over inherited database
 * properties; a database without an entity contributes nothing.
 *
 * <p>The merge is a pure function so precedence can be tested without I/O; {@link #resolve} adds
 * the single database lookup. Shared by the Iceberg 1.2 and 1.5 copies of {@code OpenHouseCatalog}.
 */
final class EffectiveTablePropertiesResolver {

  /** Server-stamped table property carrying the database name (see CatalogConstants). */
  static final String DATABASE_ID_PROPERTY = "openhouse.databaseId";

  private EffectiveTablePropertiesResolver() {}

  /**
   * One database lookup, then the merge. A 404 for the database yields no inherited properties;
   * every other failure is wrapped the way table loads wrap it.
   */
  static Map<String, String> resolve(Table table, DatabaseApi databaseApi) {
    String databaseId = resolveDatabaseId(table);
    Map<String, String> databaseProperties =
        databaseApi
            .getDatabaseV1(databaseId)
            .mapNotNull(GetDatabaseResponseBody::getProperties)
            .onErrorResume(WebClientResponseException.NotFound.class, e -> Mono.empty())
            .onErrorResume(
                WebClientResponseException.class,
                e -> Mono.error(new WebClientResponseWithMessageException(e)))
            .onErrorResume(
                WebClientRequestException.class,
                e -> Mono.error(new WebClientRequestWithMessageException(e)))
            .blockOptional()
            .orElse(Collections.emptyMap());
    return merge(databaseProperties, table.properties());
  }

  /**
   * The database a table belongs to: the server-stamped {@code openhouse.databaseId} property when
   * present, otherwise the last namespace level of {@link Table#name()} (which Iceberg renders as
   * {@code <catalog>.<database>.<table>}).
   */
  static String resolveDatabaseId(Table table) {
    Map<String, String> properties = table.properties();
    if (properties != null) {
      String fromProperties = properties.get(DATABASE_ID_PROPERTY);
      if (fromProperties != null && !fromProperties.isEmpty()) {
        return fromProperties;
      }
    }
    String name = table.name();
    if (name == null || name.isEmpty()) {
      throw new IllegalArgumentException(
          "Cannot determine the database of a table with no name and no "
              + DATABASE_ID_PROPERTY
              + " property");
    }
    Namespace namespace = TableIdentifier.parse(name).namespace();
    if (namespace.isEmpty()) {
      throw new IllegalArgumentException(
          "Cannot determine the database of table "
              + name
              + ": no namespace and no "
              + DATABASE_ID_PROPERTY
              + " property");
    }
    return namespace.level(namespace.levels().length - 1);
  }

  /**
   * Database properties overlaid by table properties. Pure: inputs are not mutated and the result
   * is unmodifiable.
   */
  static Map<String, String> merge(
      Map<String, String> databaseProperties, Map<String, String> tableProperties) {
    Map<String, String> merged = new HashMap<>();
    if (databaseProperties != null) {
      merged.putAll(databaseProperties);
    }
    if (tableProperties != null) {
      merged.putAll(tableProperties);
    }
    return Collections.unmodifiableMap(merged);
  }
}
