package com.linkedin.openhouse.internal.catalog.commit;

import java.util.List;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.UpdateRequirement;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.ValidationException;

/**
 * Checks the preconditions ({@link UpdateRequirement}s) of an Iceberg REST {@code
 * UpdateTableRequest} against the table's current {@link TableMetadata}.
 *
 * <p>This is the precondition half of the commit engine. It is deliberately free of any Spring,
 * repository or HTTP concern so that it can be unit tested in isolation and reused by whichever
 * commit path eventually calls it.
 *
 * <p><b>Why delegate to {@link UpdateRequirement#validate(TableMetadata)} instead of
 * re-implementing each check?</b> The requirement semantics (which field is compared, how a missing
 * ref is treated, the exact {@link CommitFailedException} message) are defined by the Iceberg REST
 * specification and already implemented by the library on our compile classpath. Re-implementing
 * them here would fork the specification into OpenHouse and let the two drift apart on every
 * Iceberg upgrade, which for a precondition check means silently accepting commits a spec-compliant
 * server would reject. We therefore delegate, and confine our own logic to the two cases Iceberg's
 * implementations do not cover: a {@code null} (non-existent) table, and view-only requirements.
 */
public final class UpdateRequirementValidator {

  private UpdateRequirementValidator() {}

  /**
   * Validates every requirement against {@code base}.
   *
   * @param base the table's current metadata, or {@code null} when the table does not exist
   * @param requirements the preconditions to check; must not be {@code null}, may be empty
   * @throws CommitFailedException if any precondition does not hold
   * @throws ValidationException if a requirement cannot be evaluated against a table at all
   * @throws IllegalArgumentException if {@code requirements} or any element is {@code null}
   */
  public static void validate(TableMetadata base, List<UpdateRequirement> requirements) {
    if (requirements == null) {
      throw new IllegalArgumentException("Invalid update requirements: null");
    }
    for (UpdateRequirement requirement : requirements) {
      validateOne(base, requirement);
    }
  }

  private static void validateOne(TableMetadata base, UpdateRequirement requirement) {
    if (requirement == null) {
      throw new IllegalArgumentException("Invalid update requirement: null");
    }

    // View-only requirements have no table semantics at all. Iceberg's interface default would
    // raise ValidationException for them, but only when base is non-null; reject them up front so
    // the create path (base == null) fails the same way instead of reporting a bogus commit
    // conflict.
    if (requirement instanceof UpdateRequirement.AssertViewUUID) {
      throw new ValidationException(
          "Cannot validate %s against a table", requirement.getClass().getSimpleName());
    }

    if (base == null) {
      // The table does not exist. AssertTableDoesNotExist is the only requirement that is
      // meaningful here and its Iceberg implementation handles a null base; every other
      // implementation dereferences base and would NPE, so translate to the precondition failure
      // the REST specification calls for.
      if (!(requirement instanceof UpdateRequirement.AssertTableDoesNotExist)) {
        throw new CommitFailedException(
            "Requirement failed: table does not exist, cannot evaluate %s",
            requirement.getClass().getSimpleName());
      }
    }

    requirement.validate(base);
  }
}
