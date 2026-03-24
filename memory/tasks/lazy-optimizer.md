# Task: Add filters to optimizer APIs + update lazy-optimizer tool

## Service changes
- [x] Repository: `TableOperationsRepository.findFiltered()`, remove `findAllActive()` + `find(type)`
- [x] Repository: `TableStatsRepository.findFiltered()`
- [x] Repository: `TableOperationsHistoryRepository.findFiltered()` with Pageable
- [x] Service: update `OptimizerDataService` + `OptimizerDataServiceImpl`
- [x] Controller: `TableOperationsController` — add filter params
- [x] Controller: `TableStatsController` — add list endpoint
- [x] Controller: `TableOperationsHistoryController` — add list endpoint
- [x] Tests: update + add filter tests for all 3 repos
- [x] Run `:services:optimizer:test` — all pass

## Tool changes
- [x] Fix default URL to port 8003
- [x] Replace per-UUID fetching with bulk endpoints
- [x] Pass CLI filters as query params

## Verification
- [x] Rebuild optimizer docker image, restart container
- [x] Smoke test passes
- [ ] lazy-optimizer shows data
