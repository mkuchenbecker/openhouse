# Stand-in follow-up and register-view UUID

Bottom line: done. Pull request 165 (draft), head 0442277697373ebe3707e9e4c807aeea57d2aafa, all 14 CI jobs green (Docker job and two workflow wrappers skipped). Real-store contract 33 PASSED in both rest jobs.

## Commits (pushed)
- 8d71727c tests first item 1 (view + table register UUID-only, red); 9e67f5c0 fix (namesUuid from row location dir).
- f845a7bd item 2 pins (RowBuiltAuthorizationResourceTest 5 paths; CommitIoFailureCleanUpPinnedDefectTest).
- 60dc7c11 F2; 3800e9c9 F5 contract cases (real + stand-in green); fb6ea19d F4 tests first (real 33/33, stand-in red); 2a44eaea F4 fix; b920fdcb F6; c5c33d08 F7 README; this commit F8 (bounds 120 s; record corrected).
- Next: merge base, full suites, PR, CI.

## Pull request 165 (draft), head bb81ddc95cd29679e017a02d5278a676e9831aa1 (merge of base 35bd941c). Default line green on b3d92a0c; reference green on merge; private-id check passes. Waiting for CI.

## Review 165 follow-ups: f3402db0 tests, aeb2c695 F1 fix, 07c9d64b F3, ed517ec7/4216e630 tableId, merge d8975dfe (#162), 04422776 third clear. Pushed; waiting CI on 04422776.
