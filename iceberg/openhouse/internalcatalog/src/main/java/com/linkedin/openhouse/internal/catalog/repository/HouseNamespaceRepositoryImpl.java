package com.linkedin.openhouse.internal.catalog.repository;

import com.linkedin.openhouse.housetables.client.api.DatabaseApi;
import com.linkedin.openhouse.housetables.client.model.CreateUpdateEntityRequestBodyDatabase;
import com.linkedin.openhouse.housetables.client.model.Database;
import com.linkedin.openhouse.housetables.client.model.EntityResponseBodyDatabase;
import com.linkedin.openhouse.housetables.client.model.GetAllEntityResponseBodyDatabase;
import com.linkedin.openhouse.internal.catalog.model.HouseNamespace;
import com.linkedin.openhouse.internal.catalog.repository.exception.HouseTableCallerException;
import com.linkedin.openhouse.internal.catalog.repository.exception.HouseTableConcurrentUpdateException;
import com.linkedin.openhouse.internal.catalog.repository.exception.HouseTableNotFoundException;
import com.linkedin.openhouse.internal.catalog.repository.exception.HouseTableRepositoryStateUnknownException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/**
 * Implementation of {@link HouseNamespaceRepository} talking to the House Tables Service through
 * the client generated at build time. HTS owns the record; this repository owns nothing but the
 * translation.
 */
@Repository
@Slf4j
public class HouseNamespaceRepositoryImpl implements HouseNamespaceRepository {

  private static final int READ_REQUEST_TIMEOUT_SECONDS = 30;
  private static final int WRITE_REQUEST_TIMEOUT_SECONDS = 60;

  /** The subject of a call that is not about one namespace, for error messages. */
  private static final String ALL_NAMESPACES = "<all namespaces>";

  @Autowired private DatabaseApi databaseApi;

  @Override
  public <S extends HouseNamespace> S save(S namespace) {
    String namespaceId = namespace.getNamespaceId();
    CreateUpdateEntityRequestBodyDatabase requestBody =
        new CreateUpdateEntityRequestBodyDatabase().entity(toDatabase(namespace));
    EntityResponseBodyDatabase response =
        databaseApi
            .putDatabase(requestBody)
            .onErrorResume(e -> handleHtsHttpError(e, namespaceId))
            .block(Duration.ofSeconds(WRITE_REQUEST_TIMEOUT_SECONDS));
    if (response == null || response.getEntity() == null) {
      // HTS answered without a body, so what it persisted cannot be read back from this response.
      // Returning null would hand the caller a NullPointerException in place of that fact.
      throw new HouseTableRepositoryStateUnknownException(
          String.format(
              "HTS acknowledged the save of namespace %s without returning the saved entity",
              namespaceId),
          null);
    }
    @SuppressWarnings("unchecked")
    S saved = (S) toHouseNamespace(response.getEntity());
    return saved;
  }

  @Override
  public Optional<HouseNamespace> findById(String namespaceId) {
    try {
      EntityResponseBodyDatabase response =
          databaseApi
              .getDatabase(namespaceId)
              .onErrorResume(e -> handleHtsHttpError(e, namespaceId))
              .block(Duration.ofSeconds(READ_REQUEST_TIMEOUT_SECONDS));
      return Optional.ofNullable(response == null ? null : toHouseNamespace(response.getEntity()));
    } catch (HouseTableNotFoundException e) {
      return Optional.empty();
    }
  }

  @Override
  public boolean existsById(String namespaceId) {
    return findById(namespaceId).isPresent();
  }

  @Override
  public Iterable<HouseNamespace> findAll() {
    GetAllEntityResponseBodyDatabase response =
        databaseApi
            .getDatabases()
            .onErrorResume(e -> handleHtsHttpError(e, ALL_NAMESPACES))
            .block(Duration.ofSeconds(READ_REQUEST_TIMEOUT_SECONDS));
    List<HouseNamespace> namespaces = new ArrayList<>();
    if (response != null && response.getResults() != null) {
      for (Database database : response.getResults()) {
        namespaces.add(toHouseNamespace(database));
      }
    }
    return namespaces;
  }

  /**
   * The store's listing primitive, delegated to HTS rather than derived here: {@code findAll()}
   * filtered in this process would make listing one namespace's children cost the whole catalog.
   * HTS answers it as a bounded range over the encoded id and returns only the direct children.
   */
  @Override
  public List<HouseNamespace> childrenOf(String encodedParent) {
    GetAllEntityResponseBodyDatabase response =
        databaseApi
            .getDatabaseChildren(encodedParent)
            .onErrorResume(e -> handleHtsHttpError(e, encodedParent))
            .block(Duration.ofSeconds(READ_REQUEST_TIMEOUT_SECONDS));
    List<HouseNamespace> children = new ArrayList<>();
    if (response != null && response.getResults() != null) {
      for (Database database : response.getResults()) {
        children.add(toHouseNamespace(database));
      }
    }
    return children;
  }

  @Override
  public void deleteById(String namespaceId) {
    databaseApi
        .deleteDatabase(namespaceId, null)
        .onErrorResume(e -> handleHtsHttpError(e, namespaceId).then())
        .block(Duration.ofSeconds(WRITE_REQUEST_TIMEOUT_SECONDS));
  }

  @Override
  public void delete(HouseNamespace namespace) {
    deleteById(namespace.getNamespaceId());
  }

  private static Database toDatabase(HouseNamespace namespace) {
    return new Database()
        .databaseId(namespace.getNamespaceId())
        // The version the caller read, carried to HTS so the compare-and-set happens there against
        // the row rather than against a value HTS re-reads for itself a line before saving.
        .version(namespace.getVersion())
        .properties(
            namespace.getProperties() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(namespace.getProperties()));
  }

  private static HouseNamespace toHouseNamespace(Database database) {
    if (database == null) {
      return null;
    }
    Map<String, String> properties =
        database.getProperties() == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(database.getProperties());
    return HouseNamespace.builder()
        .namespaceId(database.getDatabaseId())
        .version(database.getVersion())
        .properties(properties)
        .creationTime(database.getCreationTime())
        .lastModifiedTime(database.getLastModifiedTime())
        .build();
  }

  /** Same translation contract as {@link HouseTableRepositoryImpl#save}: absence is not failure. */
  private static <T> Mono<T> handleHtsHttpError(Throwable e, String namespaceId) {
    if (e instanceof WebClientResponseException.NotFound) {
      return Mono.error(
          new HouseTableNotFoundException(
              String.format("Namespace %s does not exist in HTS", namespaceId), e));
    } else if (e instanceof WebClientResponseException.Conflict) {
      return Mono.error(
          new HouseTableConcurrentUpdateException(
              String.format("Namespace %s was updated concurrently in HTS", namespaceId), e));
    } else if (e instanceof WebClientResponseException.BadRequest
        || e instanceof WebClientResponseException.Forbidden
        || e instanceof WebClientResponseException.Unauthorized
        || e instanceof WebClientResponseException.TooManyRequests) {
      return Mono.error(
          new HouseTableCallerException(
              String.format(
                  "[Client side failure]Error status code for HTS:%s for namespace %s",
                  ((WebClientResponseException) e).getStatusCode(), namespaceId),
              e));
    } else if (e instanceof WebClientResponseException
        && ((WebClientResponseException) e).getStatusCode().is5xxServerError()) {
      return Mono.error(
          new HouseTableRepositoryStateUnknownException(
              String.format(
                  "Cannot determine if HTS has persisted the proposed change to namespace %s",
                  namespaceId),
              e));
    }
    return Mono.error(
        new HouseTableRepositoryStateUnknownException(
            String.format("UNKNOWN and unhandled failure from HTS for namespace %s", namespaceId),
            e));
  }

  /* ----  Implement the following as needed. ---- */

  @Override
  public <S extends HouseNamespace> Iterable<S> saveAll(Iterable<S> entities) {
    throw new UnsupportedOperationException("saveAll is not supported.");
  }

  @Override
  public Iterable<HouseNamespace> findAllById(Iterable<String> namespaceIds) {
    throw new UnsupportedOperationException("findAllById is not supported.");
  }

  @Override
  public long count() {
    throw new UnsupportedOperationException("count is not supported.");
  }

  @Override
  public void deleteAllById(Iterable<? extends String> namespaceIds) {
    throw new UnsupportedOperationException("deleteAllById is not supported.");
  }

  @Override
  public void deleteAll(Iterable<? extends HouseNamespace> entities) {
    throw new UnsupportedOperationException("deleteAll is not supported.");
  }

  @Override
  public void deleteAll() {
    throw new UnsupportedOperationException("deleteAll is not supported.");
  }
}
