package com.linkedin.openhouse.housetables.dto.mapper;

import com.linkedin.openhouse.housetables.api.spec.model.Database;
import com.linkedin.openhouse.housetables.api.spec.response.UpdateDatabasePropertiesResponseBody;
import com.linkedin.openhouse.housetables.dto.model.DatabaseDto;
import com.linkedin.openhouse.housetables.dto.model.DatabasePropertiesUpdateDto;
import com.linkedin.openhouse.housetables.model.DatabaseRow;
import org.mapstruct.Mapper;

/**
 * Mapper class to transform between {@link DatabaseDto}, the {@link DatabaseRow} data model and the
 * {@link Database} API model.
 */
@Mapper(componentModel = "spring")
public interface DatabasesMapper {

  /** From a persisted {@link DatabaseRow}, prepare a {@link DatabaseDto}. */
  DatabaseDto toDatabaseDto(DatabaseRow databaseRow);

  /** From a {@link DatabaseDto}, prepare the {@link Database} API model returned to clients. */
  Database toDatabase(DatabaseDto databaseDto);

  /** From a {@link DatabasePropertiesUpdateDto}, prepare the response body for a sparse update. */
  UpdateDatabasePropertiesResponseBody toUpdateDatabasePropertiesResponseBody(
      DatabasePropertiesUpdateDto databasePropertiesUpdateDto);
}
