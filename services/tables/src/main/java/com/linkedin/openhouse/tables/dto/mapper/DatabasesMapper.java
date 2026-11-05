package com.linkedin.openhouse.tables.dto.mapper;

import com.linkedin.openhouse.tables.api.spec.v0.response.GetDatabaseResponseBody;
import com.linkedin.openhouse.tables.api.spec.v0.response.UpdateDatabasePropertiesResponseBody;
import com.linkedin.openhouse.tables.model.DatabaseDto;
import com.linkedin.openhouse.tables.model.DatabasePropertiesUpdateDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** Mapper class to transform between DTO and Data Model objects. */
@Mapper(componentModel = "spring")
public interface DatabasesMapper {
  /**
   * From a source {@link DatabaseDto}, prepare a {@link
   * com.linkedin.openhouse.tables.api.spec.v0.response.GetDatabaseResponseBody}
   *
   * @param databaseDto Source {@link DatabaseDto} to transform.
   * @return Destination {@link
   *     com.linkedin.openhouse.tables.api.spec.v0.response.GetDatabaseResponseBody} to be forwarded
   *     to the client.
   */
  GetDatabaseResponseBody toGetDatabaseResponseBody(DatabaseDto databaseDto);

  /**
   * Construct a {@link DatabaseDto} using databaseId and clusterId, as derived from table metadata.
   * Such a DTO carries no UUID or properties.
   */
  @Mapping(target = "databaseUUID", ignore = true)
  @Mapping(target = "properties", ignore = true)
  DatabaseDto toDatabaseDto(String databaseId, String clusterId);

  /** From the outcome of a sparse property update, prepare the response body for the client. */
  UpdateDatabasePropertiesResponseBody toUpdateDatabasePropertiesResponseBody(
      DatabasePropertiesUpdateDto databasePropertiesUpdateDto);
}
