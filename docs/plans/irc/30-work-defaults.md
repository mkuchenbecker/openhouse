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
| W6 | oh | **Running (2026-11-23, with X1, branch `irc/x1-format-v3`; D42).** On the 1.12 service at v3, defaults persist unchanged and no read-bridge config is sent. An `initial-default` of an existing field never changes, only a new field may carry one, and `column-defaults` opt-in is refused on v3. A non-null `write-default` below v3 is refused with `COLUMN_DEFAULT_REQUIRES_V3`, and an ordinary commit cannot change the format version (the rules of former W5 and W10). | V9, X1 | A v3 table with an API-added default round-trips byte-equal. Each refusal has a test. |
| W7 | oh | Spark 3.5 on OpenHouse reads old Parquet files with a v3 default, then ORC and nested defaults. | W3, W6 | The ported ITests pass on the opt-in build. |
| W8 | oh | A migration translates stored defaults to `initial-default`, upgrades to v3 and removes the opt-in in one commit. One-way per table, Mike's say (D12). | W6, W1 | On test tables, ORC answers are the same before and after and Parquet answers change as stated. |
| W9 | ib | Spark SQL statements for setting defaults. Not built now (D11). | none | Not applicable. |
| W11 | ib | Later, only if needed: a Spark 4.2 ORC opt-in for default filling. | W1 | A Spark 4.2 ORC default-fill test. |
| W12 | oh | **Done 2026-11-22 (pull request 97).** The Spark maintenance apps do not declare the column-defaults capability on the Iceberg 1.12 line. | W3 | On the opt-in build the apps' client sends no such capability. |
| W13 | oh | A scheduler image built on the Iceberg 1.12 line does not declare the column-defaults capability. The scheduler's uber jar lacks `IcebergLine`, so W12's detection does not run there (`32-optin-green-ci-result.md`). | W12 | The scheduler's packaged jar detects the line or is configured for it. |
