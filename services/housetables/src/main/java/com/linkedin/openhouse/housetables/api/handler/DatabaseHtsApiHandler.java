package com.linkedin.openhouse.housetables.api.handler;

import com.linkedin.openhouse.common.api.spec.ApiResponse;
import com.linkedin.openhouse.housetables.api.spec.model.Database;
import com.linkedin.openhouse.housetables.api.spec.model.DatabaseKey;
import com.linkedin.openhouse.housetables.api.spec.response.EntityResponseBody;
import com.linkedin.openhouse.housetables.api.spec.response.GetAllEntityResponseBody;

/** Interface layer between the /hts/databases REST surface and the House Tables backend. */
public interface DatabaseHtsApiHandler {

  ApiResponse<EntityResponseBody<Database>> getEntity(DatabaseKey key);

  ApiResponse<GetAllEntityResponseBody<Database>> getEntities();

  ApiResponse<EntityResponseBody<Database>> putEntity(Database database);

  ApiResponse<Void> deleteEntity(DatabaseKey key);
}
