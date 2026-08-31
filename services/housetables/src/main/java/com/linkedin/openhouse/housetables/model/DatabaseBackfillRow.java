package com.linkedin.openhouse.housetables.model;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * The durable state of the database backfill: the job that gives every database the table store
 * knows about a row in {@code database_row}.
 *
 * <p>Exactly one row exists, under {@link #SINGLETON_ID}. It carries three independent facts, and
 * the distinction between the last two is the whole point of the row:
 *
 * <ul>
 *   <li>{@code watermark} — the greatest databaseId a scan has finished registering, or null when
 *       no scan is in flight. A scan resumes strictly after it.
 *   <li>{@code scanCompleteTimeMs} — a scan <em>ran</em> to the end of the distinct-database
 *       stream. It says nothing about whether the store is complete now: the scan may have raced a
 *       database created behind it, and a registration may have failed after the scan read it.
 *   <li>{@code verifiedCompleteTimeMs} — a verification pass <em>read the store back</em> and found
 *       no database in the table store without a row. This is the only field that may be read as
 *       "the store is complete", and it is cleared by any verification that finds a gap, so it
 *       never outlives the fact it asserts.
 * </ul>
 *
 * <p>A later slice will gate reads on {@code verifiedCompleteTimeMs}. A value here that claims
 * completeness the store does not have would make that gate deny databases that exist, so nothing
 * but a verification that found zero missing rows may ever set it.
 */
@Entity
@Builder(toBuilder = true)
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
public class DatabaseBackfillRow {

  /** The primary key of the one row this table holds. */
  public static final String SINGLETON_ID = "database_row_backfill";

  @Id String id;

  /**
   * Optimistic lock. Two backfills running at once are not a correctness problem — registration is
   * idempotent — but they would interleave their watermarks into a value neither of them means, so
   * the second writer of a page loses and its run fails rather than corrupting the resume point.
   */
  @Version Long version;

  String watermark;

  Long scanCompleteTimeMs;

  Long verifiedCompleteTimeMs;

  Long lastVerifyTimeMs;

  /** Databases the last verification found in the table store with no row. */
  Long missingCount;
}
