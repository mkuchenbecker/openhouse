package com.linkedin.openhouse.housetables.api.handler;

import com.linkedin.openhouse.common.api.spec.ApiResponse;
import com.linkedin.openhouse.common.exception.RequestValidationFailureException;
import com.linkedin.openhouse.housetables.api.spec.model.Database;
import com.linkedin.openhouse.housetables.api.spec.model.DatabaseKey;
import com.linkedin.openhouse.housetables.api.spec.response.EntityResponseBody;
import com.linkedin.openhouse.housetables.api.spec.response.GetAllEntityResponseBody;
import com.linkedin.openhouse.housetables.services.DatabasesService;
import java.util.Collections;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.util.Pair;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Default handler for /hts/databases.
 *
 * <p>Validation is repeated here rather than trusted from the caller: House Tables is reachable
 * independently of the Tables Service.
 */
@Component
public class OpenHouseDatabaseHtsApiHandler implements DatabaseHtsApiHandler {

  /**
   * The persisted namespace charset. It is the House Table column's own precondition, not a
   * restated copy of the Tables Service namespace rule; the owning validator there stays the source
   * of truth for what a namespace may be.
   */
  private static final Pattern DATABASE_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_]+$");

  private static final int MAX_DATABASE_ID_LENGTH = 128;

  @Autowired private DatabasesService databasesService;

  @Override
  public ApiResponse<EntityResponseBody<Database>> getEntity(DatabaseKey key) {
    validateDatabaseId(key == null ? null : key.getDatabaseId());
    return ApiResponse.<EntityResponseBody<Database>>builder()
        .httpStatus(HttpStatus.OK)
        .responseBody(
            EntityResponseBody.<Database>builder()
                .entity(databasesService.getDatabase(key.getDatabaseId()))
                .build())
        .build();
  }

  @Override
  public ApiResponse<GetAllEntityResponseBody<Database>> getEntities() {
    return ApiResponse.<GetAllEntityResponseBody<Database>>builder()
        .httpStatus(HttpStatus.OK)
        .responseBody(
            GetAllEntityResponseBody.<Database>builder()
                .results(databasesService.getAllDatabases())
                .build())
        .build();
  }

  @Override
  public ApiResponse<EntityResponseBody<Database>> putEntity(Database database) {
    validateDatabaseId(database == null ? null : database.getDatabaseId());
    Pair<Database, Boolean> result = databasesService.putDatabase(database);
    return ApiResponse.<EntityResponseBody<Database>>builder()
        .httpStatus(result.getSecond() ? HttpStatus.OK : HttpStatus.CREATED)
        .responseBody(EntityResponseBody.<Database>builder().entity(result.getFirst()).build())
        .build();
  }

  @Override
  public ApiResponse<Void> deleteEntity(DatabaseKey key) {
    validateDatabaseId(key == null ? null : key.getDatabaseId());
    databasesService.deleteDatabase(key.getDatabaseId());
    return ApiResponse.<Void>builder().httpStatus(HttpStatus.NO_CONTENT).build();
  }

  private static void validateDatabaseId(String databaseId) {
    if (databaseId == null
        || !DATABASE_ID_PATTERN.matcher(databaseId).matches()
        || databaseId.length() > MAX_DATABASE_ID_LENGTH) {
      throw new RequestValidationFailureException(
          Collections.singletonList(
              String.format(
                  "databaseId [%s] must match %s and be at most %s characters",
                  databaseId, DATABASE_ID_PATTERN.pattern(), MAX_DATABASE_ID_LENGTH)));
    }
  }
}
