package com.linkedin.openhouse.housetables.model;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.persistence.AttributeConverter;
import javax.persistence.Converter;

/**
 * Persists a bounded {@code Map<String, String>} property bag as a JSON document in a single
 * column.
 *
 * <p>The serialized representation is deliberately an implementation detail of the storage entity:
 * the repository seam speaks maps, the column speaks text.
 */
@Converter
public class PropertiesConverter implements AttributeConverter<Map<String, String>, String> {

  private static final Gson GSON = new Gson();
  private static final Type TYPE = new TypeToken<LinkedHashMap<String, String>>() {}.getType();

  @Override
  public String convertToDatabaseColumn(Map<String, String> properties) {
    return GSON.toJson(properties == null ? Collections.emptyMap() : properties);
  }

  @Override
  public Map<String, String> convertToEntityAttribute(String serialized) {
    if (serialized == null || serialized.isEmpty()) {
      return new LinkedHashMap<>();
    }
    Map<String, String> properties = GSON.fromJson(serialized, TYPE);
    return properties == null ? new LinkedHashMap<>() : properties;
  }
}
