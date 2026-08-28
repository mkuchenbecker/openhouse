package com.linkedin.openhouse.housetables.api.handler;

import com.linkedin.openhouse.common.api.spec.ApiResponse;
import com.linkedin.openhouse.housetables.api.spec.model.UserTable;
import com.linkedin.openhouse.housetables.api.spec.model.UserTableKey;
import com.linkedin.openhouse.housetables.api.spec.response.EntityResponseBody;
import com.linkedin.openhouse.housetables.api.spec.response.GetAllEntityResponseBody;
import com.linkedin.openhouse.housetables.api.validator.HouseTablesApiValidator;
import com.linkedin.openhouse.housetables.dto.mapper.UserTablesMapper;
import com.linkedin.openhouse.housetables.dto.model.UserTableDto;
import com.linkedin.openhouse.housetables.services.PutResult;
import com.linkedin.openhouse.housetables.services.UserTablesService;
import com.linkedin.openhouse.housetables.services.UserViewQuery;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class OpenHouseUserTableHtsApiHandler implements UserTableHtsApiHandler {

  @Autowired private HouseTablesApiValidator<UserTableKey, UserTable> userTablesHtsApiValidator;

  @Autowired private UserTablesService userTableService;

  @Autowired private UserTablesMapper userTablesMapper;

  @Override
  public ApiResponse<EntityResponseBody<UserTable>> getEntity(UserTableKey userTableKey) {
    userTablesHtsApiValidator.validateGetEntity(userTableKey);
    return ApiResponse.<EntityResponseBody<UserTable>>builder()
        .httpStatus(HttpStatus.OK)
        .responseBody(
            EntityResponseBody.<UserTable>builder()
                .entity(
                    userTablesMapper.toUserTable(
                        userTableService.getUserTable(
                            userTableKey.getDatabaseId(), userTableKey.getTableId())))
                .build())
        .build();
  }

  @Override
  public ApiResponse<EntityResponseBody<UserTable>> getNeutralEntity(UserTableKey userTableKey) {
    userTablesHtsApiValidator.validateGetEntity(userTableKey);
    return ApiResponse.<EntityResponseBody<UserTable>>builder()
        .httpStatus(HttpStatus.OK)
        .responseBody(
            EntityResponseBody.<UserTable>builder()
                .entity(
                    userTablesMapper.toUserTable(
                        userTableService.getNeutralEntity(
                            userTableKey.getDatabaseId(), userTableKey.getTableId())))
                .build())
        .build();
  }

  @Override
  public ApiResponse<EntityResponseBody<UserTable>> getViewEntity(UserTableKey key) {
    userTablesHtsApiValidator.validateGetEntity(key);
    return ApiResponse.<EntityResponseBody<UserTable>>builder()
        .httpStatus(HttpStatus.OK)
        .responseBody(
            EntityResponseBody.<UserTable>builder()
                .entity(
                    userTablesMapper.toUserTable(
                        userTableService.getUserView(key.getDatabaseId(), key.getTableId())))
                .build())
        .build();
  }

  @Override
  public ApiResponse<GetAllEntityResponseBody<UserTable>> getViewEntities(UserTable userView) {
    userTablesHtsApiValidator.validateGetEntities(userView);
    return ApiResponse.<GetAllEntityResponseBody<UserTable>>builder()
        .httpStatus(HttpStatus.OK)
        .responseBody(
            GetAllEntityResponseBody.<UserTable>builder()
                .results(
                    userTableService.getAllUserViews(toUserViewQuery(userView)).stream()
                        .map(userTableDto -> userTablesMapper.toUserTable(userTableDto))
                        .collect(Collectors.toList()))
                .build())
        .build();
  }

  @Override
  public ApiResponse<GetAllEntityResponseBody<UserTable>> getViewEntities(
      UserTable userView, int page, int size, String sortBy) {
    userTablesHtsApiValidator.validateGetEntities(userView, page, size, sortBy);
    return ApiResponse.<GetAllEntityResponseBody<UserTable>>builder()
        .httpStatus(HttpStatus.OK)
        .responseBody(
            GetAllEntityResponseBody.<UserTable>builder()
                .pageResults(
                    userTableService
                        .getAllUserViews(toUserViewQuery(userView), page, size, sortBy)
                        .map(userTableDto -> userTablesMapper.toUserTable(userTableDto)))
                .build())
        .build();
  }

  /**
   * The one place a validated view-query request becomes the service-owned {@link UserViewQuery}:
   * only the accepted database id and optional table pattern cross the boundary, so wire
   * nullability, ignored transport fields, and an inert {@code entityType} stay in the transport
   * layer.
   */
  private static UserViewQuery toUserViewQuery(UserTable userView) {
    // The validator has already rejected a tableId filter without a databaseId, so the
    // pattern factory's requirement is satisfied by construction here.
    if (userView.getTableId() == null) {
      return UserViewQuery.allViews(userView.getDatabaseId());
    }
    return UserViewQuery.matching(userView.getDatabaseId(), userView.getTableId());
  }

  @Override
  public ApiResponse<Void> deleteView(UserTableKey key) {
    userTablesHtsApiValidator.validateDeleteEntity(key);
    userTableService.deleteUserView(key.getDatabaseId(), key.getTableId());
    return ApiResponse.<Void>builder().httpStatus(HttpStatus.NO_CONTENT).build();
  }

  @Override
  public ApiResponse<GetAllEntityResponseBody<UserTable>> getEntities(UserTable userTable) {
    userTablesHtsApiValidator.validateGetEntities(userTable);
    return ApiResponse.<GetAllEntityResponseBody<UserTable>>builder()
        .httpStatus(HttpStatus.OK)
        .responseBody(
            GetAllEntityResponseBody.<UserTable>builder()
                .results(
                    userTableService.getAllUserTables(userTable).stream()
                        .map(userTableDto -> userTablesMapper.toUserTable(userTableDto))
                        .collect(Collectors.toList()))
                .build())
        .build();
  }

  @Override
  public ApiResponse<GetAllEntityResponseBody<UserTable>> getEntities(
      UserTable userTable, int page, int size, String sortBy) {
    userTablesHtsApiValidator.validateGetEntities(userTable, page, size, sortBy);
    return ApiResponse.<GetAllEntityResponseBody<UserTable>>builder()
        .httpStatus(HttpStatus.OK)
        .responseBody(
            GetAllEntityResponseBody.<UserTable>builder()
                .pageResults(
                    userTableService
                        .getAllUserTables(userTable, page, size, sortBy)
                        .map(userTableDto -> userTablesMapper.toUserTable(userTableDto)))
                .build())
        .build();
  }

  @Override
  public ApiResponse<Void> deleteEntity(UserTableKey userTableKey) {
    userTablesHtsApiValidator.validateDeleteEntity(userTableKey);
    userTableService.deleteUserTable(
        userTableKey.getDatabaseId(), userTableKey.getTableId(), false);
    return ApiResponse.<Void>builder().httpStatus(HttpStatus.NO_CONTENT).build();
  }

  @Override
  public ApiResponse<Void> deleteEntity(UserTableKey userTableKey, boolean isSoftDelete) {
    userTablesHtsApiValidator.validateDeleteEntity(userTableKey);
    userTableService.deleteUserTable(
        userTableKey.getDatabaseId(), userTableKey.getTableId(), isSoftDelete);
    return ApiResponse.<Void>builder().httpStatus(HttpStatus.NO_CONTENT).build();
  }

  @Override
  public ApiResponse<EntityResponseBody<UserTable>> putEntity(UserTable userTable) {
    userTablesHtsApiValidator.validatePutEntity(userTable);
    PutResult putResult = userTableService.putUserTable(userTable);
    return putResponse(putResult.getEntity(), putResult.isReplacedExisting());
  }

  /**
   * Forwards to the view-typed service entry point, which supplies its own {@code VIEW} type: the
   * invariant this method names no longer depends on the controller having stamped the payload. The
   * controller's wire mismatch check stays, answering the contradiction as a 400 at ingress.
   */
  @Override
  public ApiResponse<EntityResponseBody<UserTable>> putView(UserTable userView) {
    userTablesHtsApiValidator.validatePutEntity(userView);
    PutResult putResult = userTableService.putUserView(userView);
    return putResponse(putResult.getEntity(), putResult.isReplacedExisting());
  }

  private ApiResponse<EntityResponseBody<UserTable>> putResponse(
      UserTableDto entity, boolean replacedExisting) {
    HttpStatus statusCode = replacedExisting ? HttpStatus.OK : HttpStatus.CREATED;
    return ApiResponse.<EntityResponseBody<UserTable>>builder()
        .httpStatus(statusCode)
        .responseBody(
            EntityResponseBody.<UserTable>builder()
                .entity(userTablesMapper.toUserTable(entity))
                .build())
        .build();
  }

  @Override
  public ApiResponse<Void> renameEntity(UserTable fromUserTable, UserTable toUserTable) {
    UserTableKey fromUserTableKey =
        UserTableKey.builder()
            .databaseId(fromUserTable.getDatabaseId())
            .tableId(fromUserTable.getTableId())
            .build();
    UserTableKey toUserTableKey =
        UserTableKey.builder()
            .databaseId(toUserTable.getDatabaseId())
            .tableId(toUserTable.getTableId())
            .build();
    userTablesHtsApiValidator.validateRenameEntity(
        fromUserTableKey, toUserTableKey, toUserTable.getMetadataLocation());
    userTableService.renameUserTable(
        fromUserTable.getDatabaseId(),
        fromUserTable.getTableId(),
        toUserTable.getDatabaseId(),
        toUserTable.getTableId(),
        toUserTable.getMetadataLocation());
    return ApiResponse.<Void>builder().httpStatus(HttpStatus.NO_CONTENT).build();
  }
}
