package com.linkedin.openhouse.housetables.services;

import com.linkedin.openhouse.housetables.dto.model.UserTableDto;
import lombok.Builder;
import lombok.Value;

/**
 * The outcome of a typed put: the row as persisted, and whether the write replaced an existing
 * occupant (an update) or created the key (a create). Named so a caller reads the distinction the
 * HTTP layer renders as 200-vs-201, instead of decoding an unlabeled boolean out of a pair.
 */
@Builder
@Value
public class PutResult {

  UserTableDto entity;

  /** {@code true} when the write replaced an existing row; {@code false} when it created one. */
  boolean replacedExisting;
}
