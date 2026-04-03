package com.linkedin.openhouse.housetables.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import javax.persistence.AttributeConverter;
import javax.persistence.Converter;

/** JPA {@link AttributeConverter} that serializes a {@code Map<String, String>} to/from JSON. */
@Converter
public class TablePropertiesConverter implements AttributeConverter<Map<String, String>, String> {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final TypeReference<Map<String, String>> TYPE_REF =
      new TypeReference<Map<String, String>>() {};

  @Override
  public String convertToDatabaseColumn(Map<String, String> attribute) {
    if (attribute == null || attribute.isEmpty()) {
      return null;
    }
    try {
      return OBJECT_MAPPER.writeValueAsString(attribute);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize table properties to JSON", e);
    }
  }

  @Override
  public Map<String, String> convertToEntityAttribute(String dbData) {
    if (dbData == null) {
      return Collections.emptyMap();
    }
    try {
      return OBJECT_MAPPER.readValue(dbData, TYPE_REF);
    } catch (IOException e) {
      throw new IllegalStateException(
          "Failed to deserialize table properties from JSON: " + dbData, e);
    }
  }
}
