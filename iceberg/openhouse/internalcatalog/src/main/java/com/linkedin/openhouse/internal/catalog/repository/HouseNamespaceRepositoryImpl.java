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

  @Autowired private DatabaseApi databaseApi;

  @Override
  public <S extends HouseNamespace> S save(S namespace) {
    CreateUpdateEntityRequestBodyDatabase requestBody =
        new CreateUpdateEntityRequestBodyDatabase().entity(toDatabase(namespace));
    EntityResponseBodyDatabase response =
        databaseApi
            .putDatabase(requestBody)
            .onErrorResume(HouseNamespaceRepositoryImpl::handleHtsHttpError)
            .block(Duration.ofSeconds(WRITE_REQUEST_TIMEOUT_SECONDS));
    @SuppressWarnings("unchecked")
    S saved = (S) toHouseNamespace(response == null ? null : response.getEntity());
    return saved;
  }

  @Override
  public Optional<HouseNamespace> findById(String namespaceId) {
    try {
      EntityResponseBodyDatabase response =
          databaseApi
              .getDatabase(namespaceId)
              .onErrorResume(HouseNamespaceRepositoryImpl::handleHtsHttpError)
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
        databaseApi.getDatabases().block(Duration.ofSeconds(READ_REQUEST_TIMEOUT_SECONDS));
    List<HouseNamespace> namespaces = new ArrayList<>();
    if (response != null && response.getResults() != null) {
      for (Database database : response.getResults()) {
        namespaces.add(toHouseNamespace(database));
      }
    }
    return namespaces;
  }

  @Override
  public void deleteById(String namespaceId) {
    databaseApi
        .deleteDatabase(namespaceId)
        .onErrorResume(e -> handleHtsHttpError(e).then())
        .block(Duration.ofSeconds(WRITE_REQUEST_TIMEOUT_SECONDS));
  }

  @Override
  public void delete(HouseNamespace namespace) {
    deleteById(namespace.getNamespaceId());
  }

  private static Database toDatabase(HouseNamespace namespace) {
    return new Database()
        .databaseId(namespace.getNamespaceId())
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
        .properties(properties)
        .creationTime(database.getCreationTime())
        .lastModifiedTime(database.getLastModifiedTime())
        .build();
  }

  /** Same translation contract as {@link HouseTableRepositoryImpl#save}: absence is not failure. */
  private static <T> Mono<T> handleHtsHttpError(Throwable e) {
    if (e instanceof WebClientResponseException.NotFound) {
      return Mono.error(new HouseTableNotFoundException("", e));
    } else if (e instanceof WebClientResponseException.Conflict) {
      return Mono.error(new HouseTableConcurrentUpdateException("", e));
    } else if (e instanceof WebClientResponseException.BadRequest
        || e instanceof WebClientResponseException.Forbidden
        || e instanceof WebClientResponseException.Unauthorized
        || e instanceof WebClientResponseException.TooManyRequests) {
      return Mono.error(
          new HouseTableCallerException(
              "[Client side failure]Error status code for HTS:"
                  + ((WebClientResponseException) e).getStatusCode(),
              e));
    } else if (e instanceof WebClientResponseException
        && ((WebClientResponseException) e).getStatusCode().is5xxServerError()) {
      return Mono.error(
          new HouseTableRepositoryStateUnknownException(
              "Cannot determine if HTS has persisted the proposed change", e));
    }
    return Mono.error(new RuntimeException("UNKNOWN and unhandled failure from HTS:", e));
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
