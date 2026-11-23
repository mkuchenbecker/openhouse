package com.linkedin.openhouse.spark.sql.catalyst.analysis

import com.linkedin.openhouse.spark.capabilities.OpenHouseCapabilities
import org.apache.iceberg.exceptions.ValidationException
import org.apache.spark.sql.catalyst.analysis.ResolvedTable
import org.apache.spark.sql.catalyst.plans.logical.{LogicalPlan, RenameTable}
import org.apache.spark.sql.catalyst.rules.Rule

/**
 * Reads the target of `ALTER TABLE ... RENAME TO` for a table of an OpenHouse server reached
 * through Iceberg's own `RESTCatalog`, as the OpenHouse catalog class read it
 * (`OpenHouseCatalog.renameTable` of the Java client).
 *
 * Spark hands the target to the catalog as it was written: `RENAME TO openhouse.db.t2` arrives
 * with the namespace `[openhouse, db]`. The OpenHouse catalog class read a target namespace of two
 * levels as `<catalog>.<database>`: it took its own name off, and refused any other name as a
 * rename across catalogs. Iceberg's `RESTCatalog` sends the levels on, and the service, which
 * cannot know what a client calls its catalog, refuses a namespace of two levels. So the catalog
 * class's reading is done here, during analysis, with its exception classes and messages:
 *
 *  - a target namespace of two levels whose first is the catalog's name loses that level;
 *  - a target namespace of two levels whose first is anything else is refused,
 *    `UnsupportedOperationException: Cannot rename tables across catalogs: from=..., to=...`;
 *  - a target namespace of more than two levels is refused, whatever its first level,
 *    `ValidationException: Namespace has unexpected levels ...`.
 *
 * A target namespace of one level is a database, and is left alone, also when the database is
 * named like the catalog: `RENAME TO openhouse.t2` is the table `t2` of the database `openhouse`,
 * as it was for the catalog class. A target without a namespace is left to Spark, which gives it
 * the table's namespace.
 *
 * Only such a catalog's tables are read this way (`CatalogCapabilities.isOpenHouseRest`, the
 * capability resolver's finding): an OpenHouse catalog class reads the target itself, and any
 * other catalog is not OpenHouse's. `ALTER VIEW ... RENAME TO` is not touched.
 *
 * One thing differs from the catalog class, which refused when the statement ran: a refusal here
 * comes during analysis, so `EXPLAIN` of a refused rename prints the refusal where it printed a
 * plan.
 */
object ResolveRenameTableTarget extends Rule[LogicalPlan] {
  override def apply(plan: LogicalPlan): LogicalPlan = plan.resolveOperators {
    case rename @ RenameTable(ResolvedTable(catalog, from, _, _), target, false)
        if target.length > 2 && OpenHouseCapabilities.resolve(catalog).isOpenHouseRest =>
      val namespace = target.init
      if (namespace.length > 2) {
        throw new ValidationException("Namespace has unexpected levels %s", namespace.mkString("."))
      }
      if (namespace.head != catalog.name()) {
        val source = (catalog.name() +: from.namespace().toSeq :+ from.name()).mkString(".")
        throw new UnsupportedOperationException(
          s"Cannot rename tables across catalogs: from=$source, to=${target.mkString(".")}")
      }
      // One level less: the rule does not match the plan it returns.
      rename.copy(newName = target.tail)
  }
}
