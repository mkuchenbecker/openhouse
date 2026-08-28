package com.linkedin.openhouse.internal.catalog.mapper;

import static com.linkedin.openhouse.internal.catalog.mapper.HouseTableSerdeUtils.IS_OH_PREFIXED;
import static com.linkedin.openhouse.internal.catalog.mapper.HouseTableSerdeUtils.OPENHOUSE_NAMESPACE;

import com.linkedin.openhouse.housetables.client.model.UserTable;
import com.linkedin.openhouse.internal.catalog.fileio.FileIOManager;
import com.linkedin.openhouse.internal.catalog.model.HouseTable;
import java.util.HashMap;
import java.util.Map;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.io.FileIO;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;
import org.springframework.beans.factory.annotation.Autowired;

@Mapper(componentModel = "spring")
public abstract class HouseTableMapper {
  @Autowired FileIOManager fileIOManager;

  @Mapping(target = "lastModifiedTime", ignore = true)
  @Mapping(
      target = "storageType",
      expression = "java(fileIOManager.getStorage(fileIO).getType().getValue())")
  public abstract HouseTable toHouseTable(Map<String, String> properties, FileIO fileIO);

  public HouseTable toHouseTable(TableMetadata tableMetadata, FileIO fileIO) {
    return toHouseTable(extractRawHTSFields(tableMetadata.properties()), fileIO);
  }

  @BeanMapping(ignoreByDefault = true)
  @Mapping(target = "databaseId", source = "userTable.databaseId")
  public abstract HouseTable toHouseTableWithDatabaseId(UserTable userTable);

  @BeanMapping(ignoreByDefault = true)
  @Mapping(target = "databaseId", source = "houseTable.databaseId")
  public abstract UserTable toUserTableWithDatabaseId(HouseTable houseTable);

  @Mappings({@Mapping(target = "tableLocation", source = "userTable.metadataLocation")})
  public abstract HouseTable toHouseTable(UserTable userTable);

  /**
   * The discriminator maps through in both directions now that this catalog writes views as well as
   * tables. It was previously ignored on this edge, which meant every write reached House Tables
   * with a null discriminator — correct for a table, and silently wrong for a view, which would
   * have been stored as a table and then been invisible to a typed view read.
   *
   * <p>{@code HouseTable.entityType} stays null on the table path, so table writes are unchanged.
   */
  @Mappings({@Mapping(target = "metadataLocation", source = "houseTable.tableLocation")})
  public abstract UserTable toUserTable(HouseTable houseTable);

  private Map<String, String> extractRawHTSFields(Map<String, String> input) {
    Map<String, String> output = new HashMap<>();
    for (Map.Entry<String, String> entry : input.entrySet()) {
      String key = entry.getKey();
      String value = entry.getValue();
      if (isHtsField(key)) {
        String newKey = stripOhNamespace(key);
        output.put(newKey, value);
      }
    }
    return output;
  }

  private static boolean isHtsField(String key) {
    return IS_OH_PREFIXED.test(key)
        && HouseTableSerdeUtils.HTS_FIELD_NAMES.contains(stripOhNamespace(key));
  }

  /**
   * Private, and it must stay private.
   *
   * <p>MapStruct treats any visible {@code String}-to-{@code String} method on a mapper as a
   * candidate implicit conversion and had been silently applying this one to <b>every</b> String
   * property it generated — 23 call sites, none of them intended. That was harmless only because no
   * mapped value happened to begin with {@code "openhouse."} and none was null; the first nullable
   * String property added to the entity turned it into a {@link NullPointerException} inside
   * generated code. Restricting visibility takes it out of MapStruct's candidate set, which is the
   * fix, and leaves it available to the two call sites above that actually want it.
   */
  private static String stripOhNamespace(String key) {
    return IS_OH_PREFIXED.test(key) ? key.substring(OPENHOUSE_NAMESPACE.length()) : key;
  }
}
