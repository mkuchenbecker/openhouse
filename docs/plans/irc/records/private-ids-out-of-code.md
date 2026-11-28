# Private ids out of code (FF-34, FF-49)

Bottom line: done. Draft pull request 164, head f4ae73532dc938c14cf7cd633387e0cec258f117, CI green (14 jobs success, Docker duplicate skipped by design); review findings F1-F8 closed or decided.

## Log
- Read brief, FF-34, FF-49, RF-045, pr-review-154 F1/F5/F8/F9, FV and BD section 4.
- Survey: 500 guard matches over 136 files at 4b9d152d (tests included, docs and delta harness excluded); 20 on bb8929e1 (all false positives).
- Decisions: guard scans the whole tree (no diff), allow-list holds main's matches; scans tests; skips *.md, docs/, delta harness (own catalog in HARNESS-GUIDE.md).
- e4a30e2b guard (fails: 486), 932bbf7a tests for messages (6 of 37 fail before, right reason), ce603531 message fix (37 run, 0 fail, 1 skip default; 34 skip ref), ba72a2b9 guard V0-V3 tweak, 7c1ff7ae main comments (27 files), 3d9c8220 tests (91 files). All pushed.
- Next: build/CI files commit (gradle, settings, capabilities comment incl. publish/one-way + line dependence), FF-49 unzip commit, allow-list of FPs, then merge base, PR.
- 235df121 build files, b1ef3c1c unzip check, d57bb536 guard passes. Ref compile + spotlessCheck ok; SparkIrcFormatV3TypesTest 6/6 default; check-test-groups holds.
- Draft pull request 164, head d57bb53631a57cb40895a540eda99929ca1e1615. Waiting for CI.
- Review round (pr-review-164.md, MERGE AFTER FIXES): 0145cfcb (F1, F6), 9b1d5daa (F8), d7e59c7c (F2 docs), bc95534a (guard: docs scanned, wrap join, P/J, PR numbers unbounded, bare #N 80-999, test-data names, exact N entries, F7 reason, F3 docstring, --self-test). Merged #163 as f4ae7353 (no conflicts; guard clean on merged tree). Pushed f4ae73532dc938c14cf7cd633387e0cec258f117; PR body updated. Waiting for CI.
