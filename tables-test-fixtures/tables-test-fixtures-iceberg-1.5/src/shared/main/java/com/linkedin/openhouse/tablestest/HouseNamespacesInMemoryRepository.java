package com.linkedin.openhouse.tablestest;

import com.linkedin.openhouse.common.exception.EntityConcurrentModificationException;
import com.linkedin.openhouse.common.exception.checked.DuplicateCreationException;
import com.linkedin.openhouse.common.exception.checked.NotFoundException;
import com.linkedin.openhouse.internal.catalog.model.NamespaceMetadata;
import com.linkedin.openhouse.internal.catalog.repository.HouseNamespaceRepository;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Primary;

/**
 * The {@link HouseNamespaceRepository} of the embedded tables service ({@link
 * SpringH2TestApplication}), which runs without a house tables service: without it, the namespace
 * routes would call a house tables service that is not there. With {@link Primary}, it replaces the
 * HTTP client the service's configuration provides. Imported, not scanned, so it is a bean of each
 * application context and two services in one JVM do not share records.
 *
 * <p>Faithful to the house tables service's namespace store (its {@code NamespacesServiceImpl} over
 * the {@code catalog_namespaces} table) as the tables service sees it through {@code
 * HouseNamespaceRepositoryImpl}:
 *
 * <ul>
 *   <li>The name is unique case-insensitively (the column's collation) and keeps the spelling it
 *       was created with. A create of any case variant of an existing name is {@link
 *       DuplicateCreationException}; reads, replaces and deletes find the record by any spelling.
 *   <li>A record gets a random uuid and revision "0" at creation; every replace moves the revision
 *       by one, a replace with an unchanged document included (the store's forced increment).
 *   <li>A replace is conditional on the uuid and revision the document carries: anything else is
 *       {@link EntityConcurrentModificationException}, as the store's 409 is. A document with
 *       another spelling of the name, or no properties, is refused with {@link
 *       IllegalArgumentException}, standing for the store's 400, which the client does not map.
 *   <li>A missing record is {@link NotFoundException} for read, replace and delete. A delete
 *       removes the record only; a create after it is a new record (new uuid, revision "0").
 *   <li>Properties are one document, copied in and answered as an unmodifiable copy.
 * </ul>
 *
 * Every operation runs under one lock, as each is one transaction in the store.
 */
@Primary
public class HouseNamespacesInMemoryRepository implements HouseNamespaceRepository {

  private static final class Row {
    final String uuid;
    final String name;
    final Map<String, String> properties;
    final long version;

    Row(String uuid, String name, Map<String, String> properties, long version) {
      this.uuid = uuid;
      this.name = name;
      this.properties = Map.copyOf(properties);
      this.version = version;
    }

    NamespaceMetadata metadata() {
      return new NamespaceMetadata(uuid, name, properties, Long.toString(version));
    }
  }

  private final Map<String, Row> rows = new HashMap<>();

  private static String key(String namespaceName) {
    return namespaceName.toLowerCase(Locale.ROOT);
  }

  @Override
  public synchronized NamespaceMetadata create(String namespaceName, Map<String, String> properties)
      throws DuplicateCreationException {
    Objects.requireNonNull(properties, "properties");
    if (rows.containsKey(key(namespaceName))) {
      throw new DuplicateCreationException("Namespace already exists");
    }
    Row row = new Row(UUID.randomUUID().toString(), namespaceName, properties, 0L);
    rows.put(key(namespaceName), row);
    return row.metadata();
  }

  @Override
  public synchronized NamespaceMetadata getByName(String namespaceName) throws NotFoundException {
    return find(namespaceName).metadata();
  }

  @Override
  public synchronized void delete(String namespaceName) throws NotFoundException {
    find(namespaceName);
    rows.remove(key(namespaceName));
  }

  @Override
  public synchronized NamespaceMetadata update(String namespaceName, NamespaceMetadata document)
      throws NotFoundException {
    Row current = find(namespaceName);
    if (!current.uuid.equals(document.getNamespaceUuid())
        || !Long.toString(current.version).equals(document.getRevision())) {
      throw new EntityConcurrentModificationException(namespaceName, null);
    }
    if (!current.name.equals(document.getNamespaceName()) || document.getProperties() == null) {
      throw new IllegalArgumentException(
          "The namespace document must carry the stored name and a property map");
    }
    Row replaced =
        new Row(current.uuid, current.name, document.getProperties(), current.version + 1);
    rows.put(key(namespaceName), replaced);
    return replaced.metadata();
  }

  private Row find(String namespaceName) throws NotFoundException {
    Row row = rows.get(key(namespaceName));
    if (row == null) {
      throw new NotFoundException("Namespace does not exist");
    }
    return row;
  }
}
