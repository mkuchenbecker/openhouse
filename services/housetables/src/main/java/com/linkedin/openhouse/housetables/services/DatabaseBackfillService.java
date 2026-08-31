package com.linkedin.openhouse.housetables.services;

import com.linkedin.openhouse.housetables.api.spec.model.DatabaseBackfillStatus;

/**
 * Gives every database that predates {@code database_row} a row in it.
 *
 * <p>Every path that creates a table now registers the database it names, so the set of databases
 * without a row can only shrink. It does not shrink on its own: a database whose last table was
 * written before the namespace store existed has no row and never will until something walks the
 * table store and writes one. That walk is this service, and until it has run and been verified
 * every read of the namespace store has to keep deriving a database's existence from the tables it
 * holds.
 *
 * <p>Nothing here runs on its own. A full scan of the table store at boot was rejected in review of
 * the namespace design, and it would run on every replica of every cluster on every restart; both
 * entry points are called by an operator, through {@code /hts/databases/backfill}.
 */
public interface DatabaseBackfillService {

  /**
   * Register every database the table store knows about that has no row yet, resuming after the
   * watermark of an interrupted run and leaving a fresh watermark behind after every page.
   *
   * <p>Idempotent: a database that already has a row is left exactly as it is, properties included.
   * Bounded: the distinct databases are read one page at a time, never all at once.
   *
   * @param pageSize databases read per round trip
   * @return what this run did, and the durable state it left behind
   */
  DatabaseBackfillStatus backfill(int pageSize);

  /**
   * Read the store back and record whether it is complete: whether every database in the table
   * store has a row. Records completeness only when nothing is missing, and clears a previously
   * recorded completeness when something is.
   *
   * @param pageSize databases read per round trip
   * @return what this pass found, and the durable state it left behind
   */
  DatabaseBackfillStatus verify(int pageSize);

  /** @return the durable state, with the per-call counters zero. */
  DatabaseBackfillStatus status();
}
