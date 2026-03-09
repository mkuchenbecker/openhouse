package com.linkedin.openhouse.housetables.dto.mapper;

import com.linkedin.openhouse.housetables.dto.model.TableStatsDto;
import com.linkedin.openhouse.housetables.model.TableStatsRow;
import org.mapstruct.Mapper;

/** MapStruct mapper between {@link TableStatsRow} and {@link TableStatsDto}. */
@Mapper(componentModel = "spring")
public interface TableStatsMapper {

  /** Map a {@link TableStatsRow} to its DTO. */
  TableStatsDto toDto(TableStatsRow row);

  /** Map a {@link TableStatsDto} to a new entity row. */
  TableStatsRow toRow(TableStatsDto dto);
}
