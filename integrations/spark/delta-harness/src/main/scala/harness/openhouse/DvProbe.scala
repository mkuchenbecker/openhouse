package harness

// Deletion-vector probe. Boots the embedded OpenHouse server (via OpenHouseEnv, same wiring the
// matrix uses) and drives a format-version=3 merge-on-read table through DELETE/UPDATE/MERGE, then
// reports precisely what the delete path produced: classic position-delete files vs. Puffin
// deletion vectors (deletion-vector-v1 blobs). Server format-version is taken from the JVM system
// property cluster.iceberg.format-version (Spring @Value), so launch with
// -Dcluster.iceberg.format-version=3 to have the OpenHouse server author v3 metadata.
object DvProbe {
  import scala.sys.process._

  def sh(cmd: String): String =
    try Seq("bash", "-c", cmd).!!.trim catch { case _: Throwable => "" }

  def main(args: Array[String]): Unit = {
    val (server, spark, _, _, htsCtxOpt) = OpenHouseEnv.start()
    spark.sparkContext.setLogLevel("ERROR")
    val fmt = if (args.nonEmpty) args(0) else "parquet"
    val tbl = "openhouse.dbMatrix.dv_probe"
    try {
      spark.sql(s"DROP TABLE IF EXISTS $tbl")
      println(s">>> creating $tbl (write.format=$fmt) merge-on-read; server cluster.iceberg.format-version=" +
        sys.props.getOrElse("cluster.iceberg.format-version", "(default 2)"))
      spark.sql(
        s"""CREATE TABLE $tbl (id bigint, data string) USING iceberg
            TBLPROPERTIES (
              'write.format.default'='$fmt',
              'write.delete.mode'='merge-on-read',
              'write.update.mode'='merge-on-read',
              'write.merge.mode'='merge-on-read'
            )""")

      println(">>> effective TBLPROPERTIES:")
      spark.sql(s"SHOW TBLPROPERTIES $tbl").collect()
        .filter(r => Set("format-version", "write.delete.mode", "write.update.mode", "write.merge.mode",
          "write.format.default").contains(r.getString(0)))
        .foreach(r => println(s"      ${r.getString(0)} = ${r.getString(1)}"))

      spark.sql(s"INSERT INTO $tbl VALUES (1,'a'),(2,'b'),(3,'c'),(4,'d'),(5,'e'),(6,'f')")
      println(">>> after INSERT count = " + spark.sql(s"SELECT count(*) FROM $tbl").collect()(0).getLong(0))

      spark.sql(s"DELETE FROM $tbl WHERE id = 2")
      spark.sql(s"UPDATE $tbl SET data = 'Z' WHERE id = 4")
      spark.sql(s"MERGE INTO $tbl t USING (SELECT 3 AS id) s ON t.id = s.id WHEN MATCHED THEN DELETE")

      val cnt = spark.sql(s"SELECT count(*) FROM $tbl").collect()(0).getLong(0)
      println(s">>> after DELETE(id=2)+UPDATE(id=4)+MERGE-DELETE(id=3): count = $cnt (expected 4)")
      println(">>> surviving rows (expect 1a 4Z 5e 6f):")
      spark.sql(s"SELECT * FROM $tbl ORDER BY id").collect().foreach(r => println("      " + r))

      println(">>> .all_delete_files (content 1=POSITION_DELETES, 2=EQUALITY):")
      spark.sql(s"SELECT content, file_format, record_count, file_path FROM $tbl.all_delete_files")
        .collect().foreach(r => println(s"      content=${r.get(0)} fmt=${r.get(1)} records=${r.get(2)} path=${r.getString(3)}"))
      println(">>> .all_data_files:")
      spark.sql(s"SELECT content, file_format, record_count, file_path FROM $tbl.all_data_files")
        .collect().foreach(r => println(s"      content=${r.get(0)} fmt=${r.get(1)} records=${r.get(2)} path=${r.getString(3)}"))

      // Physical inspection: derive table root from any data-file path, scan for .puffin.
      val anyPath = spark.sql(s"SELECT file_path FROM $tbl.all_data_files LIMIT 1").collect().headOption.map(_.getString(0)).getOrElse("")
      val idx = anyPath.indexOf("/data/")
      val root = (if (idx > 0) anyPath.substring(0, idx) else anyPath).replaceFirst("^file:", "")
      println(s">>> table root on disk = $root")
      val puffins = sh(s"find '$root' -name '*.puffin' 2>/dev/null").split("\n").filter(_.nonEmpty)
      println(s">>> .puffin files found: ${puffins.length}")
      puffins.foreach { pf =>
        val hasDv = sh(s"grep -a -o 'deletion-vector-v1' '$pf' | head -1")
        val sz = sh(s"stat -c %s '$pf'")
        println(s"      $pf  size=${sz}B  deletion-vector-v1 blob: ${if (hasDv.nonEmpty) "YES" else "no"}")
      }
      // Also dump the file listing of data + metadata dirs for the record.
      println(">>> data dir listing:")
      println(sh(s"find '$root' -type f | sed 's#$root/##' | sort").split("\n").map("      " + _).mkString("\n"))
    } finally {
      try spark.stop() catch { case _: Throwable => () }
      try server.stop() catch { case _: Throwable => () }
      htsCtxOpt.foreach(ctx => try ctx.close() catch { case _: Throwable => () })
    }
  }
}
