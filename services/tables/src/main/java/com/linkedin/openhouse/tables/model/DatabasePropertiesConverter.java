package com.linkedin.openhouse.tables.model;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import javax.persistence.AttributeConverter;
import javax.persistence.Converter;

/**
 * JPA converter persisting {@link DatabaseDto#getProperties()} as a JSON string. Only exercised by
 * the H2-backed test substitutes of {@link
 * com.linkedin.openhouse.tables.repository.DatabaseEntityRepository}; production persistence lives
 * in the HouseTables Service.
 */
@Converter
public class DatabasePropertiesConverter
    implements AttributeConverter<Map<String, String>, String> {

  private static final Gson GSON = new Gson();

  private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();

  @Override
  public String convertToDatabaseColumn(Map<String, String> attribute) {
    return attribute == null ? null : GSON.toJson(attribute, MAP_TYPE);
  }

  @Override
  public Map<String, String> convertToEntityAttribute(String dbData) {
    if (dbData == null || dbData.isEmpty()) {
      return null;
    }
    Map<String, String> parsed = GSON.fromJson(dbData, MAP_TYPE);
    return parsed == null ? null : new HashMap<>(parsed);
  }
}
