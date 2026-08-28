package com.linkedin.openhouse.tables.model;

import java.util.Objects;
import java.util.Optional;
import lombok.Value;

/**
 * The paging half of a list-views request: either an unpaged listing or a bounded one.
 *
 * <p>This type exists so the service contract can express "no paging was requested" without a
 * nullable {@code pageToken} and a nullable {@code pageSize}. The distinction is load-bearing
 * rather than cosmetic: the spec requires that a request carrying no {@code pageToken} be answered
 * with <b>all</b> results in a single page, so "unpaged" is a different instruction to the service
 * than "the first page of some size", and a pair of nulls cannot say which was meant.
 *
 * <p>Construct with {@link #unpaged()} or {@link #of(String, Integer)}; both are total, and neither
 * accepts a null it would have to interpret later.
 */
@Value
public class ViewPageRequest {

  private static final ViewPageRequest UNPAGED = new ViewPageRequest(null, null);

  /** Opaque continuation token supplied by the caller; empty on the first or an unpaged request. */
  Optional<String> pageToken;

  /** Caller-requested page size; empty when the caller expressed no preference. */
  Optional<Integer> pageSize;

  private ViewPageRequest(String pageToken, Integer pageSize) {
    this.pageToken = Optional.ofNullable(pageToken);
    this.pageSize = Optional.ofNullable(pageSize);
  }

  /**
   * A listing with no paging instruction, which the service must answer in one complete page.
   *
   * @return the shared unpaged request
   */
  public static ViewPageRequest unpaged() {
    return UNPAGED;
  }

  /**
   * A listing as the wire supplied it, where either field may be absent.
   *
   * <p>Takes the two boxed wire values directly, because this is the one place that adapts an HTTP
   * query string — where absence really is encoded as a missing value — into the total type the
   * rest of the code uses. Callers that already know their intent should prefer {@link #unpaged()}.
   *
   * @param pageToken continuation token from the query string, or {@code null} if absent
   * @param pageSize page size from the query string, or {@code null} if absent
   * @return an unpaged request when both are absent, otherwise a paged one
   */
  public static ViewPageRequest of(String pageToken, Integer pageSize) {
    if (pageToken == null && pageSize == null) {
      return UNPAGED;
    }
    return new ViewPageRequest(pageToken, pageSize);
  }

  /**
   * Whether the caller asked for a complete listing.
   *
   * <p>Keyed on the token alone, not on the page size: the spec's obligation is triggered by an
   * absent {@code pageToken}, and a caller who sends only a {@code pageSize} is still making a
   * first request that must be answered completely.
   *
   * @return true when no continuation token was supplied
   */
  public boolean isUnpaged() {
    return !pageToken.isPresent();
  }

  @Override
  public String toString() {
    return "ViewPageRequest{pageToken="
        + pageToken.map(t -> "<" + t.length() + " chars>").orElse("absent")
        + ", pageSize="
        + pageSize.map(Objects::toString).orElse("absent")
        + "}";
  }
}
