package com.linkedin.openhouse.tables.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.Builder;
import lombok.Value;
import org.apache.iceberg.catalog.TableIdentifier;

/**
 * One page of view identifiers from a list operation, in wire order, plus the opaque continuation
 * token. A {@code null} {@link #nextPageToken} means the listing is complete: the serialized
 * response then omits {@code next-page-token}, which is the spec's termination signal.
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

  /** Opaque continuation token, {@code null} when the listing is complete. */
  String nextPageToken;

  /** Defensively copies the identifier list so the page is deeply immutable. */
  @Builder
  private ViewIdentifiersPage(List<TableIdentifier> identifiers, String nextPageToken) {
    this.identifiers =
        identifiers == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(identifiers));
    this.nextPageToken = nextPageToken;
  }
}
