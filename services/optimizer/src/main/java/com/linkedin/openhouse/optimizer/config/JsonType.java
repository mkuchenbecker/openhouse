package com.linkedin.openhouse.optimizer.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.Serializable;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Objects;
import org.hibernate.HibernateException;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.usertype.UserType;

/**
 * Custom Hibernate {@code UserType} for mapping JSON columns (stored as VARCHAR) to Java objects.
 *
 * <p>Why hand-written instead of using {@code hibernate-types-52}? The {@code hibernate-types-52}
 * library would solve this problem out of the box, but adding it as a new transitive dependency for
 * a single use case is not warranted. This implementation covers our needs. If more JSON columns
 * are added in the future, migrating to {@code hibernate-types-52} should be reconsidered.
 *
 * <p>Registered with Hibernate via the SPI entry in {@code
 * META-INF/services/org.hibernate.boot.model.TypeContributor} (see {@link HibernateConfig}). Entity
 * fields annotated with {@code @Type(type = "json")} will use this implementation.
 */
public class JsonType implements UserType {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Override
  public int[] sqlTypes() {
    return new int[] {Types.VARCHAR};
  }

  @Override
  public Class returnedClass() {
    return Object.class;
  }

  @Override
  public boolean equals(Object x, Object y) throws HibernateException {
    return Objects.equals(x, y);
  }

  @Override
  public int hashCode(Object x) throws HibernateException {
    return Objects.hashCode(x);
  }

  @Override
  public Object nullSafeGet(
      ResultSet rs, String[] names, SharedSessionContractImplementor session, Object owner)
      throws HibernateException, SQLException {
    String json = rs.getString(names[0]);
    if (json == null) {
      return null;
    }
    try {
      return OBJECT_MAPPER.readValue(json, Object.class);
    } catch (IOException e) {
      throw new HibernateException("Failed to deserialize JSON: " + json, e);
    }
  }

  @Override
  public void nullSafeSet(
      PreparedStatement st, Object value, int index, SharedSessionContractImplementor session)
      throws HibernateException, SQLException {
    if (value == null) {
      st.setNull(index, Types.VARCHAR);
    } else {
      try {
        st.setString(index, OBJECT_MAPPER.writeValueAsString(value));
      } catch (JsonProcessingException e) {
        throw new HibernateException("Failed to serialize object to JSON: " + value, e);
      }
    }
  }

  @Override
  public Object deepCopy(Object value) throws HibernateException {
    if (value == null) {
      return null;
    }
    try {
      String json = OBJECT_MAPPER.writeValueAsString(value);
      return OBJECT_MAPPER.readValue(json, value.getClass());
    } catch (IOException e) {
      throw new HibernateException("Failed to deep copy JSON object", e);
    }
  }

  @Override
  public boolean isMutable() {
    return true;
  }

  @Override
  public Serializable disassemble(Object value) throws HibernateException {
    return (Serializable) deepCopy(value);
  }

  @Override
  public Object assemble(Serializable cached, Object owner) throws HibernateException {
    return deepCopy(cached);
  }

  @Override
  public Object replace(Object original, Object target, Object owner) throws HibernateException {
    return deepCopy(original);
  }
}
