package com.linkedin.openhouse.tables.repository.impl;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.linkedin.openhouse.common.exception.AlreadyExistsException;
import com.linkedin.openhouse.common.exception.EntityConcurrentModificationException;
import com.linkedin.openhouse.common.exception.NoSuchEntityException;
import com.linkedin.openhouse.common.exception.OpenHouseCommitStateUnknownException;
import com.linkedin.openhouse.common.exception.RequestValidationFailureException;
import com.linkedin.openhouse.common.exception.UnprocessableEntityException;
import com.linkedin.openhouse.housetables.client.api.DatabaseApi;
import com.linkedin.openhouse.housetables.client.model.CreateUpdateEntityRequestBodyDatabase;
import com.linkedin.openhouse.housetables.client.model.Database;
import com.linkedin.openhouse.housetables.client.model.EntityResponseBodyDatabase;
import com.linkedin.openhouse.tables.model.DatabaseDto;
import com.linkedin.openhouse.tables.model.DatabaseDtoPrimaryKey;
import com.linkedin.openhouse.tables.model.DatabasePropertiesUpdateDto;
import com.linkedin.openhouse.tables.repository.DatabaseEntityRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/**
 * {@link DatabaseEntityRepository} over the HouseTables Service, using the {@code DatabaseApi}
 * client generated from the HouseTables {@code /hts/databases} controller.
 *
 * <p>Error translation mirrors {@link
 * com.linkedin.openhouse.internal.catalog.repository.HouseTableRepositoryImpl}: HouseTables status
 * codes become the typed exceptions that the shared {@code OpenHouseExceptionHandler} renders with
 * the same codes (404, 409, 422, 400), and an unreachable or failing HouseTables Service becomes a
 * 503 through {@link OpenHouseCommitStateUnknownException}.
 */
@Repository
@Slf4j
public class DatabaseEntityRepositoryImpl implements DatabaseEntityRepository {

  private static final int READ_REQUEST_TIMEOUT_SECONDS = 30;

  private static final int WRITE_REQUEST_TIMEOUT_SECONDS = 60;

  private static final String ENTITY_TYPE = "Database";

  private static final Gson GSON = new Gson();

  @Autowired private DatabaseApi databaseApi;

  @Override
  public Optional<DatabaseDto> findByDatabaseIdIgnoreCase(String databaseId) {
    return databaseApi
        .getDatabase(databaseId)
        .map(EntityResponseBodyDatabase::getEntity)
        .map(DatabaseEntityRepositoryImpl::toDatabaseDto)
        .onErrorResume(WebClientResponseException.NotFound.class, e -> Mono.empty())
        .onErrorResume(e -> Mono.error(translateHtsError(e, databaseId, false)))
        .blockOptional(Duration.ofSeconds(READ_REQUEST_TIMEOUT_SECONDS));
  }

  @Override
  public boolean existsByDatabaseIdIgnoreCase(String databaseId) {
    return findByDatabaseIdIgnoreCase(databaseId).isPresent();
  }

  @Override
  public DatabaseDto create(DatabaseDto databaseDto) {
    Database entity = new Database();
    entity.setDatabaseId(databaseDto.getDatabaseId());
    entity.setProperties(
        databaseDto.getProperties() == null
            ? new HashMap<>()
            : new HashMap<>(databaseDto.getProperties()));
    CreateUpdateEntityRequestBodyDatabase requestBody = new CreateUpdateEntityRequestBodyDatabase();
    requestBody.setEntity(entity);
    DatabaseDto created =
        databaseApi
            .createDatabase(requestBody)
            .map(EntityResponseBodyDatabase::getEntity)
            .map(DatabaseEntityRepositoryImpl::toDatabaseDto)
            .onErrorResume(e -> Mono.error(translateHtsError(e, databaseDto.getDatabaseId(), true)))
            .block(Duration.ofSeconds(WRITE_REQUEST_TIMEOUT_SECONDS));
    return created == null
        ? null
        : created.toBuilder().clusterId(databaseDto.getClusterId()).build();
  }

  @Override
  public void delete(DatabaseDto databaseDto) {
    databaseApi
        .deleteDatabase(databaseDto.getDatabaseId())
        .onErrorResume(
            e -> Mono.error(translateHtsError(e, databaseDto.getDatabaseId(), false)).then())
        .block(Duration.ofSeconds(WRITE_REQUEST_TIMEOUT_SECONDS));
  }

  @Override
  public DatabasePropertiesUpdateDto updateProperties(
      String databaseId, Map<String, String> updates, List<String> removals) {
    com.linkedin.openhouse.housetables.client.model.UpdateDatabasePropertiesRequestBody
        requestBody =
            new com.linkedin.openhouse.housetables.client.model
                .UpdateDatabasePropertiesRequestBody();
    requestBody.setUpdates(updates == null ? new HashMap<>() : new HashMap<>(updates));
    requestBody.setRemovals(removals == null ? new ArrayList<>() : new ArrayList<>(removals));
    return databaseApi
        .updateDatabaseProperties(databaseId, requestBody)
        .map(DatabaseEntityRepositoryImpl::toUpdateDto)
        .onErrorResume(e -> Mono.error(translateHtsError(e, databaseId, false)))
        .block(Duration.ofSeconds(WRITE_REQUEST_TIMEOUT_SECONDS));
  }

  /**
   * Translate a HouseTables client failure into the typed exception the shared exception handler
   * maps to the equivalent status code.
   *
   * @param isCreate whether a 409 means "already exists" (create) or "modified concurrently"
   *     (update).
   */
  static RuntimeException translateHtsError(Throwable e, String databaseId, boolean isCreate) {
    if (e instanceof WebClientResponseException.NotFound) {
      return new NoSuchEntityException(ENTITY_TYPE, databaseId, e);
    } else if (e instanceof WebClientResponseException.Conflict) {
      if (isCreate) {
        return new AlreadyExistsException(ENTITY_TYPE, databaseId, e);
      }
      return new EntityConcurrentModificationException(
          databaseId,
          String.format(
              "databaseId : %s, The requested database has been modified by other processes.",
              databaseId),
          e);
    } else if (e instanceof WebClientResponseException.UnprocessableEntity) {
      return new UnprocessableEntityException(extractHtsMessage((WebClientResponseException) e), e);
    } else if (e instanceof WebClientResponseException.BadRequest) {
      return new RequestValidationFailureException(
          extractHtsMessage((WebClientResponseException) e));
    } else if (e instanceof WebClientResponseException
        && ((WebClientResponseException) e).getStatusCode().is5xxServerError()) {
      return new OpenHouseCommitStateUnknownException(
          databaseId,
          String.format(
              "HouseTables Service failed while processing database [%s]: %s",
              databaseId, ((WebClientResponseException) e).getStatusCode()),
          e);
    } else if (e instanceof WebClientRequestException) {
      return new OpenHouseCommitStateUnknownException(
          databaseId,
          String.format(
              "HouseTables Service could not be reached while processing database [%s]",
              databaseId),
          e);
    } else if (e instanceof WebClientResponseException) {
      return new RuntimeException(
          String.format(
              "Unexpected status code %s from HouseTables Service for database [%s]",
              ((WebClientResponseException) e).getStatusCode(), databaseId),
          e);
    } else if (e instanceof RuntimeException) {
      return (RuntimeException) e;
    }
    return new RuntimeException("Unhandled failure from HouseTables Service", e);
  }

  /** Pull the {@code message} out of a HouseTables {@code ErrorResponseBody}, if there is one. */
  static String extractHtsMessage(WebClientResponseException e) {
    String body = e.getResponseBodyAsString();
    if (StringUtils.isNotBlank(body)) {
      try {
        JsonObject json = GSON.fromJson(body, JsonObject.class);
        if (json != null && json.has("message") && !json.get("message").isJsonNull()) {
          return json.get("message").getAsString();
        }
      } catch (JsonSyntaxException | IllegalStateException ignored) {
        // fall through to the raw text
      }
      return body;
    }
    return e.getMessage();
  }

  static DatabaseDto toDatabaseDto(Database database) {
    return DatabaseDto.builder()
        .databaseId(database.getDatabaseId())
        .databaseUUID(database.getDatabaseUuid())
        .properties(
            database.getProperties() == null
                ? new HashMap<>()
                : new HashMap<>(database.getProperties()))
        .build();
  }

  static DatabasePropertiesUpdateDto toUpdateDto(
      com.linkedin.openhouse.housetables.client.model.UpdateDatabasePropertiesResponseBody body) {
    return DatabasePropertiesUpdateDto.builder()
        .updated(body.getUpdated() == null ? Collections.emptyList() : body.getUpdated())
        .removed(body.getRemoved() == null ? Collections.emptyList() : body.getRemoved())
        .missing(body.getMissing() == null ? Collections.emptyList() : body.getMissing())
        .build();
  }

  /*
   * The remaining CrudRepository methods either delegate to the name-based lookups above or are
   * not supported over the HouseTables API, matching ToggleStatusesRepositoryImpl.
   */

  @Override
  public Optional<DatabaseDto> findById(DatabaseDtoPrimaryKey key) {
    return findByDatabaseIdIgnoreCase(key.getDatabaseId());
  }

  @Override
  public boolean existsById(DatabaseDtoPrimaryKey key) {
    return existsByDatabaseIdIgnoreCase(key.getDatabaseId());
  }

  @Override
  public void deleteById(DatabaseDtoPrimaryKey key) {
    delete(DatabaseDto.builder().databaseId(key.getDatabaseId()).build());
  }

  @Override
  public <S extends DatabaseDto> S save(S entity) {
    throw new UnsupportedOperationException(
        "Use create() or updateProperties(); upsert is not supported for database entities");
  }

  @Override
  public <S extends DatabaseDto> Iterable<S> saveAll(Iterable<S> entities) {
    throw new UnsupportedOperationException("saveAll is not supported for database entities");
  }

  @Override
  public Iterable<DatabaseDto> findAll() {
    throw new UnsupportedOperationException("findAll is not supported for database entities");
  }

  @Override
  public Iterable<DatabaseDto> findAllById(Iterable<DatabaseDtoPrimaryKey> keys) {
    throw new UnsupportedOperationException("findAllById is not supported for database entities");
  }

  @Override
  public long count() {
    throw new UnsupportedOperationException("count is not supported for database entities");
  }

  @Override
  public void deleteAllById(Iterable<? extends DatabaseDtoPrimaryKey> keys) {
    throw new UnsupportedOperationException("deleteAllById is not supported for database entities");
  }

  @Override
  public void deleteAll(Iterable<? extends DatabaseDto> entities) {
    throw new UnsupportedOperationException("deleteAll is not supported for database entities");
  }

  @Override
  public void deleteAll() {
    throw new UnsupportedOperationException("deleteAll is not supported for database entities");
  }
}
