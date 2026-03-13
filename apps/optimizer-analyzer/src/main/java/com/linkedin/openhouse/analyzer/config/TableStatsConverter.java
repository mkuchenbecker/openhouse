package com.linkedin.openhouse.analyzer.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkedin.openhouse.analyzer.model.TableStats;
import java.io.IOException;
import javax.persistence.AttributeConverter;
import javax.persistence.Converter;

/** JPA {@link AttributeConverter} that serializes {@link TableStats} to/from a JSON string. */
@Converter
public class TableStatsConverter implements AttributeConverter<TableStats, String> {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Override
  public String convertToDatabaseColumn(TableStats attribute) {
    if (attribute == null) {
      return null;
    }
    try {
      return OBJECT_MAPPER.writeValueAsString(attribute);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize TableStats to JSON", e);
    }
  }

  @Override
  public TableStats convertToEntityAttribute(String dbData) {
    if (dbData == null) {
      return null;
    }
    try {
      return OBJECT_MAPPER.readValue(dbData, TableStats.class);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to deserialize TableStats from JSON: " + dbData, e);
    }
  }
}
