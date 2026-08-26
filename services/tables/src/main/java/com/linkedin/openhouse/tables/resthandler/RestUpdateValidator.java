package com.linkedin.openhouse.tables.resthandler;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.linkedin.openhouse.tables.repository.PreservedKeyChecker;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import lombok.extern.slf4j.Slf4j;
import org.apache.iceberg.MetadataUpdate;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.BadRequestException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Guards the Iceberg REST-native commit path against updates that would mutate OpenHouse-owned
 * state through the generic {@code (requirements, updates)} vocabulary.
 *
 * <p>Two kinds of checks:
 *
 * <ul>
 *   <li>{@link #validateRequestShape(TableIdentifier, List)} — request-static checks that do not
 *       depend on current table state (preserved {@code openhouse.*}/{@code policies} property
 *       keys, {@code assign-uuid}). Run once, before the commit retry loop.
 *   <li>{@link #validateAgainstBase(TableIdentifier, TableMetadata)} — checks that must see the
 *       freshest table state (locked tables). Run inside the commit loop on every attempt, so a
 *       concurrently-applied lock cannot be raced past.
 * </ul>
 */
@Component
@Slf4j
public class RestUpdateValidator {

  private static final String POLICIES_KEY = "policies";
  private static final String LOCK_STATE_FIELD = "lockState";
  private static final String LOCKED_FIELD = "locked";

  private static final Gson GSON = new Gson();

  private final PreservedKeyChecker preservedKeyChecker;

  @Autowired
  public RestUpdateValidator(PreservedKeyChecker preservedKeyChecker) {
    this.preservedKeyChecker = preservedKeyChecker;
  }

  /**
   * Rejects updates that are invalid regardless of the current table state.
   *
   * @throws BadRequestException when an update touches a preserved property key or attempts to
   *     re-assign the table UUID
   */
  public void validateRequestShape(TableIdentifier tableIdentifier, List<MetadataUpdate> updates) {
    for (MetadataUpdate update : updates) {
      if (update instanceof MetadataUpdate.AssignUUID) {
        throw new BadRequestException(
            "Cannot commit to table %s: assign-uuid is not allowed outside of table creation",
            tableIdentifier);
      }
      if (update instanceof MetadataUpdate.SetProperties) {
        rejectPreservedKeys(
            tableIdentifier, ((MetadataUpdate.SetProperties) update).updated().keySet(), "set");
      }
      if (update instanceof MetadataUpdate.RemoveProperties) {
        rejectPreservedKeys(
            tableIdentifier, ((MetadataUpdate.RemoveProperties) update).removed(), "remove");
      }
    }
  }

  /**
   * Rejects commits based on the freshly refreshed base metadata. Invoked per commit attempt so
   * that state applied by a concurrent commit (e.g. a table lock) is honored.
   *
   * @throws BadRequestException when the table is locked
   */
  public void validateAgainstBase(TableIdentifier tableIdentifier, TableMetadata base) {
    if (base != null && isTableLocked(base)) {
      throw new BadRequestException(
          "Table %s is in locked state and cannot be written to", tableIdentifier);
    }
  }

  private void rejectPreservedKeys(
      TableIdentifier tableIdentifier, Set<String> keys, String operation) {
    Set<String> preserved = new TreeSet<>();
    for (String key : keys) {
      if (preservedKeyChecker.isKeyPreserved(key)) {
        preserved.add(key);
      }
    }
    if (!preserved.isEmpty()) {
      throw new BadRequestException(
          "Cannot %s preserved properties %s for table %s: %s",
          operation, preserved, tableIdentifier, preservedKeyChecker.describePreservedSpace());
    }
  }

  /**
   * Reads the lock state out of the {@code policies} table property carried in the metadata. This
   * mirrors the {@code TableDto}-based lock predicate used by the legacy write paths, but operates
   * on the raw metadata so it can run against the per-attempt refreshed base.
   */
  private boolean isTableLocked(TableMetadata base) {
    String policiesJson = base.properties().get(POLICIES_KEY);
    if (policiesJson == null || policiesJson.isEmpty()) {
      return false;
    }
    try {
      JsonObject policies = GSON.fromJson(policiesJson, JsonObject.class);
      if (policies == null || !policies.has(LOCK_STATE_FIELD)) {
        return false;
      }
      JsonObject lockState = policies.getAsJsonObject(LOCK_STATE_FIELD);
      return lockState != null
          && lockState.has(LOCKED_FIELD)
          && lockState.get(LOCKED_FIELD).getAsBoolean();
    } catch (JsonSyntaxException | IllegalStateException | UnsupportedOperationException e) {
      // A malformed policies blob must not block writes; the legacy path is equally lenient.
      log.warn("Could not parse policies property while checking lock state", e);
      return false;
    }
  }
}
