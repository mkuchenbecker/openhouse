package com.linkedin.openhouse.tables.api.validator.impl;

import com.linkedin.openhouse.common.api.spec.TableUri;
import com.linkedin.openhouse.tables.api.spec.v0.request.CreateUpdateTableRequestBody;
import com.linkedin.openhouse.tables.api.spec.v0.request.components.PerformanceTierConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Validates the {@link PerformanceTierConfig} in a table request. When {@code auto=false}, a {@code
 * resolved} tier must be explicitly specified.
 */
@Slf4j
@Component
public class PerformanceTierValidator extends PolicySpecValidator {

  @Override
  public boolean validate(
      CreateUpdateTableRequestBody createUpdateTableRequestBody, TableUri tableUri) {
    if (createUpdateTableRequestBody.getPolicies() == null) {
      return true;
    }
    PerformanceTierConfig config = createUpdateTableRequestBody.getPolicies().getPerformanceTier();
    if (config == null) {
      return true;
    }
    if (!config.isAuto() && config.getResolved() == null) {
      failureMessage =
          String.format(
              "performanceTier.resolved must be set when auto=false for table %s", tableUri);
      errorField = "performanceTier";
      return false;
    }
    return true;
  }
}
