package com.linkedin.openhouse.tables.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import lombok.Value;
import org.apache.iceberg.catalog.TableIdentifier;

/**
 * One page of view identifiers from a list operation, in wire order, plus the opaque continuation
 * token. An empty {@link #nextPageToken} means the listing is complete: the serialized response
 * then omits {@code next-page-token}, which is the spec's termination signal.
 *
 * <p>Server obligation inherited from the spec's pagination contract: when the caller supplied no
 * {@code pageToken}, the service must return <b>all</b> results in one page. This matters because
 * the 1.5.2.17 client's {@code listViews} issues a single GET and follows no {@code
 * next-page-token}; a server that paginates an un-tokened request would silently truncate that
 * client's listing.
 */
@Value
public class ViewIdentifiersPage {

  /** Never {@code null}: an absent list collapses to empty. Unmodifiable. */
  List<TableIdentifier> identifiers;

  /** Opaque continuation token; empty when the listing is complete. */
  Optional<String> nextPageToken;

  private ViewIdentifiersPage(List<TableIdentifier> identifiers, String nextPageToken) {
    this.identifiers =
        identifiers == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(identifiers));
    this.nextPageToken = Optional.ofNullable(nextPageToken);
  }

  /**
   * A complete listing, which is the only shape an unpaged request may be answered with.
   *
   * @param identifiers every identifier in the namespace
   * @return a page carrying no continuation token
   */
  public static ViewIdentifiersPage complete(List<TableIdentifier> identifiers) {
    return new ViewIdentifiersPage(identifiers, null);
  }

  /**
   * One page of a paged listing, with the token that fetches the next.
   *
   * @param identifiers the identifiers on this page
   * @param nextPageToken the continuation token, required — a page with no more results is a {@link
   *     #complete(List)} page, and passing null here would silently produce one
   * @return a page carrying the continuation token
   */
  public static ViewIdentifiersPage partial(
      List<TableIdentifier> identifiers, String nextPageToken) {
    if (nextPageToken == null) {
      throw new IllegalArgumentException(
          "A partial page requires a continuation token; use complete() for a terminal page");
    }
    return new ViewIdentifiersPage(identifiers, nextPageToken);
  }
}
