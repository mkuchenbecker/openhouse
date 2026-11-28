# C10 and C14: the Spark image builds in CI, and the generator jar no longer fails builds at random

Bottom line: both are done and merged into `irc-integration`. C10 (pull request 122) makes the Spark image build on a
CI runner in six to seven minutes, and the dataloader integration checks, 15 of 15, pass against it. C14 (pull
request 123) stops the build from failing at random when Maven Central answers 429 for the OpenAPI generator jar.
After both, the first full run of the flipped layout was green on both jobs. Written 2026-11-23 from the two agents'
records, which are in the session's reports directory (`c10-spark-image-ci.md` and `c14-generator-jar-download.md`).
The records are outside the repository, so this file is the durable copy.

## First post-merge run of the flipped layout

- Head `440249d1` (pull request 118, V9e), run 45202558910: both jobs green. The default line took 25 minutes with
  its Docker steps. The reference line (LinkedIn's Iceberg 1.5.2) took 23 minutes.

## C10: pull request 122, merge commit `71c4c70be081b9d7c51e6538066e59c216586e5a`

- Result: the Spark image of the default build (Spark 3.5.2, Java 17, the fork's Iceberg 1.12 runtime, Livy from the
  pinned commit) builds on a GitHub runner in six to seven minutes. The `oh-hadoop-spark` recipe starts, and all 15
  dataloader integration checks pass. Three runs on three different commits, each green at its first attempt. The
  integration job is back on pull requests that touch the relevant files and now also runs after every merge.
- Spark SQL behaviour: no difference found against the expectations written for Spark 3.1 and Iceberg 1.2, for the
  statements this suite sends (create, insert, select with filters, a day-partitioned create, drop). No test
  expectation was changed and no version choice was changed.
- Root cause of the image never building (the third failure of C9): Livy's build downloaded a 228 MB Spark release
  from Apache's rate-limited archive host, for tests the build skips anyway. One build flag fixes it:
  `-Ddownload.plugin.skip=true` on both Maven lines. The flag C9 proposed, `-Ddownload.skip=true`, would not have
  worked, because the pinned download plugin reads `download.plugin.skip`.
- Latent defect found: the old "wait for services" step never waited. Its probe printed `000` for a refused
  connection and the step appended another `000`, so it saw `000000`, which it did not read as "not ready". It
  reported the services ready after five seconds with nothing listening. The tests passed only because installing
  their packages took longer than the services' start. The step now waits up to 600 seconds for the tables and house
  tables services' health, Livy's sessions route, HDFS out of safe mode with one live datanode, and one alive Spark
  worker. In CI it waited 50 and 67 seconds. Recorded in `21-findings-continued.md`, thirteenth round.
- Not run, so not proven: Scala sessions and batch sessions of Livy; a job submitted through the jobs service (the
  same app was run by `spark-submit` locally in V9d); the `default` builder stage of the image, which still needs
  Apache's archive host and was not built. Livy `pyspark` sessions do not work in the image, as before.
- What the image holds: Java 17, Spark 3.5.2 on Scala 2.12, Hadoop client 3.3.4 against the recipe's HDFS 2.8 (data
  files written by Spark were read back by a Hadoop 2.8 client without error), the fork's Iceberg 1.12 runtime, and
  Livy `0.8.0-incubating-SNAPSHOT`.
- Timing per run: the job took 22, 15 and 17 minutes; the image build took 6:04, 6:21 and 6:37; the tests took about
  2 minutes. Every image build on a runner is cold.
- On the final head the reference-line job failed once with HTTP 429 for the generator jar. That was not this
  change, and it is what C14 fixes.

## C14: pull request 123, merge commit `75d6e1851f80b0688a0aa22ddac52388c9dfbc89`

- Problem: after every `clean`, the build downloaded the OpenAPI generator jar (version 5.3.0, 24.7 MB) with one
  `curl` and no retry. Maven Central's rate limit (HTTP 429) failed whole builds at random, in CI and locally. The
  failure summary said only that a `sh` command exited with 1; the status was 488 lines higher in the log, without
  the address. Reproduced for real: the first attempt in a fresh worktree failed with 429, the second, seconds
  later, worked. CI run 45212160192 was one such failure.
- Fix: the jar is now an ordinary Gradle dependency. It is kept in the Gradle dependency cache, which `clean` does
  not delete and CI restores, and copied into the place the generator scripts already use. The download script is
  deleted. No client project changed how it calls the generator.
- Proof: the jar has identical bytes (SHA-256 `f29d9d71...a7e1`). Generated sources are the same on both lines apart
  from the generator's own timestamps (320 files each side). A from-clean build works twice on each line, and
  works offline once the cache holds the jar. A simulated 429 and 503 now fail with the address and status in the
  message. Pull request CI was green on both jobs at the final head (run 45216713993).
- Remaining limit: an empty cache under a sustained rate limit still fails, now with a clear message. Gradle retries
  three times in quick succession and does not wait for the server's `Retry-After`. Until a post-merge build has
  saved the cache with the jar, each pull request job fetches it once through Gradle (not observed).
- No checksum is verified. The old script verified nothing either. The first commit's message wrongly said Gradle
  checks the jar; the second commit corrects it. Pinning a SHA-256 would take about ten lines and was not asked for.
- Side finding, new small work item C15: the OpenAPI specs the generator reads are not byte-stable between two
  builds of the same sources. Two from-clean generations on the same commit gave different `housetables.json` and
  `tables.json` (the order of the properties of Spring's `Page` type), and 734 differing non-timestamp lines in the
  generated trees. This is probably the cause of the "not reproducible bit for bit" finding of the tenth round, which was not traced. It was not
  fixed here.
- Not run locally: a full `clean build` of either line (CI did) and the Docker steps.
