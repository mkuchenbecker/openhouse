# The compatibility kit's failures in the Iceberg REST layer

Bottom line (in progress): worktree `/home/user/oh-kit13`, branch `irc/kit-failures-in-the-rest-layer`, base
`origin/irc-integration` `0b3cafb6`. Plan decided after reading; code not written yet.

## Plan (decided)

- MSG 8: reword on the Iceberg REST spec routes only, to the reference implementation's wording (also what LinkedIn
  main's own OpenHouseCatalog shows Spark users: CatalogPluginContractTest pins "Table does not exist: ..." and
  "Table already exists: ..." on the baseline path):
  1. missing table (NoSuchUserTableException) -> "Table does not exist: db.t" (3 tests). OpenHouse routes under
     /iceberg (grants, unlock) keep the native wording (they are adapters over native handlers; Spark smoke s27 pins it).
  2. table already exists on create / staged create -> "Table already exists: db.t"; rename onto a table ->
     "Table already exists: db.to" (JdbcCatalog, the kit's reference backend) (2 tests).
  3. views: NO_SUCH_VIEW -> "View does not exist: db.v"; NAME_ALREADY_EXISTS_AS_TABLE -> "Table with same name already
     exists: db.v" (3 tests).
- IDS 3: on the read routes (existence checks, loads, lists of a namespace) an id the native rules refuse answers
  "does not exist"; writes keep the 400.
- MISSINGMETA: leave (5XX is allowed by the spec for a server-side problem; 404 is documented as "table does not
  exist", which it does).
- VIEWERRTYPE: leave (would need one 400 typed IllegalArgumentException, unlike every other 400, including the same
  rule on the replace path).

## Progress (2026-11-28)

Commits pushed on `irc/kit-failures-in-the-rest-layer`:
- `981dbddf` missing table wording (3 kit tests). Before run: 3 new assertions failed on the message; after: IrcRead 24, IrcWrite 45, IrcGrantsUnlock 5+18 all pass.
- `7a2f16b0` create/staged create/rename onto a table (2 kit tests). Before: 2 failed on the message.
- `e1e00b61` views: missing view, name a table holds (3 kit tests); register route too; Spark 3.5 IcebergRestCatalogViewTest pin updated (not run locally yet). Before: 7 failed on the message.
- `f2159aa6` follow-up: reworded 409s carry no cause (audit parity: IrcServiceAuditTest failed on cause).
- `49d9a8a5` ids the native rules refuse answer as missing on reads (3 kit tests). Before: both new tests failed on 400.
- IRC suite run (all `Irc*` e2e + `api.irc.*`, 52 classes, 487 tests) with fixes: 2 failures, both fixed (audit cause; metrics compared with load).
Left: MISSINGMETA, VIEWERRTYPE (reasons in docs). Next: kit runs (default, v3), reference line, merge base, PR.

## Pull request 169 (draft), head 49d9a8a5a7436e0d59b1dff20223b5a2c12ed31a
Kit at head: default 160 = 63 passed / 81 failed / 16 skipped; v3 160 = 65 / 79 / 16; all listed.
Default line IRC suite 52 classes 487 tests 0 failures; reference line 40 classes 417 tests 17 skipped 0 failures;
Spark 3.5 IcebergRestCatalogViewTest 7/7. Waiting for CI.
