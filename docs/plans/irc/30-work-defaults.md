# Work: column defaults on the new line

Continues `04-work.md`, from which these rows were moved. Layout and rules are the same. The bottom-line count in
`04-work.md` is the total across both files.

## Column defaults on the new line

From `14-native-defaults-design.md`, adopted in D11. Tests come first for every row.

| ID | Repo | Leaves true | Needs | Proven by |
| --- | --- | --- | --- | --- |
| W1 | ib | **Done 2026-11-22 (iceberg pull request 11).** An ORC reader that does not opt in to default filling, or reads a name-mapped file, throws Apache's `ORC cannot read default value` for an absent field with a non-null `initial-default`. | none | A generic ORC read of a v3 table expects the throw. The ORC and Spark 3.5 ORC default suites still pass. |
| W2 | ib | **Done 2026-11-22 (iceberg pull request 11).** A Spark 3.5 test pins native-default behavior: Parquet, Avro and ORC fills, row and vectorized; the DEFAULT keyword gives NULL; DDL is refused; v2 is refused; no downgrade. | none | Green on the fork. |
| W3 | oh | **Done 2026-11-22 (pull request 91).** The new-line client neither overlays OpenHouse defaults nor declares `column-defaults`. The legacy build is unchanged. | I6a | On the opt-in build the catalog declares nothing and a stamped config does not reach `table.schema()`. The legacy build still passes. |
| W4 | oh | **Done 2026-11-22 (pull request 91).** The three `OpenHouseTableOperationsTest` fixtures are ported to v3 on the opt-in build. | I6a | The three tests pass on the opt-in build and still pass on legacy. |
| W6 | oh | **Done 2026-11-23 (with X1, pull request 124, merge commit `e2e02bfa`; D42, D43).** On the 1.12 service at v3, defaults persist unchanged and no read-bridge config is sent. An `initial-default` of an existing field never changes, only a new field may carry one, and the `column-defaults` opt-in is refused on v3. A non-null `write-default` below v3 is refused with `COLUMN_DEFAULT_REQUIRES_V3` when a commit introduces or changes it, and an ordinary commit cannot change the format version (the rules of former W5 and W10). | V9, X1 | A v3 table with an API-added default round-trips as committed. Each refusal has a test. |
| W7 | oh | **Done 2026-11-24 (with X4, pull request 126, merge commit `57db4015`).** Result in `61-w7-x4-result.md`. Spark 3.5 (native API) and Spark 4.2 (IRC only) read old Parquet and ORC files through native defaults on v3 tables, in every scenario tested. Widening a defaulted column's type is now accepted on v3 (it was refused with 400). ORC vectorized cases were not checked for columnar execution; Avro and nested defaults are not covered. | W3, W6 | 31 new tests pass on both lines; CI green on the pull request. |
| W8 | oh | A migration translates stored defaults to `initial-default`, upgrades to v3 and removes the opt-in in one commit. Untouched. W6 already refuses the opt-in on v3 and names W8. One-way per table, Mike's say (D12). | W6, W1 | On test tables, ORC answers are the same before and after and Parquet answers change as stated. |
| W9 | ib | Spark SQL statements for setting defaults. Not built now (D11). | none | Not applicable. |
| W11 | ib | Later, only if needed: a Spark 4.2 ORC opt-in for default filling. | W1 | A Spark 4.2 ORC default-fill test. |
| W12 | oh | **Done 2026-11-22 (pull request 97).** The Spark maintenance apps do not declare the column-defaults capability on the Iceberg 1.12 line. | W3 | On the opt-in build the apps' client sends no such capability. |
| W13 | oh | **In flight with W14 and N9** (worktree `/home/user/oh-small2`, branch `irc/small-batch-w13-w14-n9`, record `small-batch-w13-w14-n9.md` in the reports directory). The jobs scheduler image carries the Spark 3.5 apps jar (V9d) but still cannot detect the Iceberg line there: `TablesClientFactory` warns and declares `column-defaults` (`47-v9d-deployables-result.md`). It should not on the 1.12 line. | W12 | The scheduler's packaged jar detects the line or is configured for it. |
| W14 | oh | **In flight with W13 and N9** (same worktree and branch as W13). On Spark 3.5 an INSERT that omits a defaulted column gets the `write-default`. Today a column-list INSERT is refused and `DEFAULT` writes NULL, a limitation of Iceberg's Spark 3.5 integration, pinned by `spark35WritesNoWriteDefault` (`61-w7-x4-result.md`). Done when Spark 3.5 writes the `write-default` or the limitation is documented for users. | W7 | The pinning test flips to expect the `write-default`, or the user documentation names the limitation. |
