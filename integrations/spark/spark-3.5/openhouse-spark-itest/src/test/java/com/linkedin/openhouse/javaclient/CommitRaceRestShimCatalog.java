package com.linkedin.openhouse.javaclient;

/**
 * The REST-shaped catalog plugin ({@code com.linkedin.openhouse.spark.OpenHouseRestCatalog}) whose
 * backing catalog is {@link CommitRaceCatalog}: the commit the in-process adapter hands the backing
 * table operations, after it loaded the table and applied the request's updates, calls {@link
 * com.linkedin.openhouse.spark.smoke.CommitRace#beforeCommit} first. That is the last point before
 * the tables service on this path, so the armed step also lands inside the adapter's own
 * load-then-commit window. Test only, the {@code catalog-impl} of the smoke suite's second catalog
 * on the rest-shim path.
 */
public class CommitRaceRestShimCatalog extends com.linkedin.openhouse.spark.OpenHouseRestCatalog {

  @Override
  protected OpenHouseCatalog newBackingCatalog() {
    return new CommitRaceCatalog();
  }
}
