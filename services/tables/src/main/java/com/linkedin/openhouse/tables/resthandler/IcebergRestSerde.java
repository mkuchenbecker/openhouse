package com.linkedin.openhouse.tables.resthandler;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import org.apache.iceberg.rest.RESTSerializers;
import org.springframework.stereotype.Component;

/**
 * Holds the dedicated {@link ObjectMapper} for the Iceberg REST-native commit endpoint, configured
 * identically to Iceberg's own {@code RESTObjectMapper} (kebab-case properties, field visibility,
 * lenient unknown properties, and {@link RESTSerializers} for the spec wire types such as {@code
 * UpdateTableRequest} and {@code LoadTableResponse}).
 *
 * <p>The mapper is deliberately wrapped in this component instead of being exposed as an {@code
 * ObjectMapper} bean: a raw {@code ObjectMapper} bean would suppress Spring Boot's Jackson
 * auto-configuration and replace the service-wide MVC mapper, silently changing (de)serialization
 * of every legacy endpoint. The REST commit controller consumes/produces raw JSON strings through
 * this holder instead.
 */
@Component
public class IcebergRestSerde {

  private final ObjectMapper mapper;

  public IcebergRestSerde() {
    this.mapper = new ObjectMapper();
    mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    mapper.setPropertyNamingStrategy(new PropertyNamingStrategy.KebabCaseStrategy());
    RESTSerializers.registerAll(mapper);
  }

  public <T> T fromJson(String json, Class<T> type) throws JsonProcessingException {
    return mapper.readValue(json, type);
  }

  public String toJson(Object value) throws JsonProcessingException {
    return mapper.writeValueAsString(value);
  }
}
