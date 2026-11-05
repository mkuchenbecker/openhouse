package com.linkedin.openhouse.housetables.model;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import javax.persistence.AttributeConverter;
import javax.persistence.Converter;
import org.apache.commons.lang3.StringUtils;

/**
 * JPA converter persisting a {@code Map<String, String>} as a JSON text column.
 *
 * <p>A {@code null} or empty map is persisted as an empty JSON object, and a {@code null} or blank
 * column reads back as an empty, mutable map, so callers never observe a {@code null} property map.
 */
@Converter
public class PropertiesMapConverter implements AttributeConverter<Map<String, String>, String> {

  private static final Gson GSON = new Gson();

  private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();

  @Override
  public String convertToDatabaseColumn(Map<String, String> attribute) {
    return GSON.toJson(attribute == null ? new HashMap<String, String>() : attribute, MAP_TYPE);
  }

  @Override
  public Map<String, String> convertToEntityAttribute(String dbData) {
    if (StringUtils.isBlank(dbData)) {
      return new HashMap<>();
    }
    Map<String, String> parsed = GSON.fromJson(dbData, MAP_TYPE);
    return parsed == null ? new HashMap<>() : new HashMap<>(parsed);
  }
}
