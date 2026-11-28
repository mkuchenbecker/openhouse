# House Tables stand-in fidelity (FF-32, FF-33)

Bottom line (in progress): contract written from the real store and committed (3e9e3984, pushed). Real store 30/30, fixtures stand-in 7/30, service copy 4/30. Next: rewrite the stand-in as one implementation in the fixtures, per context.

## Steps
1. Worktree /home/user/oh-standin, branch irc/house-tables-stand-in-fidelity from 96acfca3.
2. Contract `HouseTableRepositoryContract` (fixtures main), real run `HouseTableRepositoryImplContractTest` (services/tables namespaceIntegrationTest, H2 MODE=MYSQL;IGNORECASE=TRUE with schema.sql), stand-in runs in both test suites. Before-failure lists: scratchpad/standin/fx-before.txt, ts-before.txt.
3. Real store findings: cross-database and same-name rename are 400 (HouseTableCallerException); sort direction dropped by HouseTableRepositoryImpl (getSortByStr sends properties only); soft-deleted listing unsorted when asked unsorted (HTS default sortBy=databaseId).
4. Product finding to check: OpenHouseInternalTableOperations IOException cleanup calls houseTableRepository.delete(entity); real impl throws UnsupportedOperationException.

Attribution: the session notice gives "Claude Opus 5.5" lines; followed over the common brief's "Claude Fable 5.2".
