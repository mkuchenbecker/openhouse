package com.linkedin.openhouse.housetables.api.handler;

import com.linkedin.openhouse.common.api.spec.ApiResponse;
import com.linkedin.openhouse.housetables.api.spec.model.Database;
import com.linkedin.openhouse.housetables.api.spec.model.DatabaseKey;
import com.linkedin.openhouse.housetables.api.spec.request.UpdateDatabasePropertiesRequestBody;
import com.linkedin.openhouse.housetables.api.spec.response.EntityResponseBody;
import com.linkedin.openhouse.housetables.api.spec.response.UpdateDatabasePropertiesResponseBody;
import com.linkedin.openhouse.housetables.api.validator.DatabasesHtsApiValidator;
import com.linkedin.openhouse.housetables.dto.mapper.DatabasesMapper;
import com.linkedin.openhouse.housetables.services.DatabasesService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class OpenHouseDatabasesApiHandler implements DatabasesApiHandler {

  @Autowired private DatabasesHtsApiValidator databasesHtsApiValidator;

  @Autowired private DatabasesService databasesService;

  @Autowired private DatabasesMapper databasesMapper;

  @Override
  public ApiResponse<EntityResponseBody<Database>> createEntity(Database database) {
    databasesHtsApiValidator.validatePutEntity(database);
    return ApiResponse.<EntityResponseBody<Database>>builder()
        .httpStatus(HttpStatus.CREATED)
        .responseBody(
            EntityResponseBody.<Database>builder()
                .entity(databasesMapper.toDatabase(databasesService.createDatabase(database)))
                .build())
        .build();
  }

  @Override
  public ApiResponse<EntityResponseBody<Database>> getEntity(DatabaseKey databaseKey) {
    databasesHtsApiValidator.validateGetEntity(databaseKey);
    return ApiResponse.<EntityResponseBody<Database>>builder()
        .httpStatus(HttpStatus.OK)
        .responseBody(
            EntityResponseBody.<Database>builder()
                .entity(
                    databasesMapper.toDatabase(
                        databasesService.getDatabase(databaseKey.getDatabaseId())))
                .build())
        .build();
  }

  @Override
  public ApiResponse<Void> deleteEntity(DatabaseKey databaseKey) {
    databasesHtsApiValidator.validateDeleteEntity(databaseKey);
    databasesService.deleteDatabase(databaseKey.getDatabaseId());
    return ApiResponse.<Void>builder().httpStatus(HttpStatus.NO_CONTENT).build();
  }

  @Override
  public ApiResponse<UpdateDatabasePropertiesResponseBody> updateProperties(
      DatabaseKey databaseKey,
      UpdateDatabasePropertiesRequestBody updateDatabasePropertiesRequestBody) {
    databasesHtsApiValidator.validateUpdateProperties(
        databaseKey, updateDatabasePropertiesRequestBody);
    return ApiResponse.<UpdateDatabasePropertiesResponseBody>builder()
        .httpStatus(HttpStatus.OK)
        .responseBody(
            databasesMapper.toUpdateDatabasePropertiesResponseBody(
                databasesService.updateDatabaseProperties(
                    databaseKey.getDatabaseId(),
                    updateDatabasePropertiesRequestBody.getUpdates(),
                    updateDatabasePropertiesRequestBody.getRemovals())))
        .build();
  }
}
