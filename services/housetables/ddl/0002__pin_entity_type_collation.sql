-- This file is an operations record of a schema change applied out of band by the
-- MySQL/DDS team. The service does not execute it. The oh-only-mysql compose recipe
-- mounts this directory into the MySQL image's initialisation directory, so every run
-- of that recipe applies it in filename order and verifies the migration path.
--
-- Pins the discriminator column's collation to utf8mb4_0900_as_ci: case-insensitive,
-- accent-sensitive, NO PAD. EntityType.fromName accepts any case of TABLE or VIEW and
-- nothing else, and the repository's typed predicates compare upper(entity_type)
-- against those spellings under this column's collation. Under the MySQL 8 server
-- default utf8mb4_0900_ai_ci the two vocabularies diverge: that collation calls an
-- accented 'TÁBLE' equal to 'TABLE', so a typed predicate selects a row whose
-- hydration then fails, turning a typed read into a 500. Under utf8mb4_0900_as_ci the
-- set of stored values the predicates match is exactly the set fromName accepts, so a
-- corrupt value is invisible to every typed route and only the neutral /hts/entities
-- read, which deliberately carries no type predicate, reports the corruption. This
-- also matches how the H2 test schema behaves (case-sensitive comparison, NO PAD,
-- folded through the explicit upper()), so the test and deployed engines agree.
--
-- No ALGORITHM=INSTANT here: changing a column's collation is not instant-eligible
-- and rebuilds the table under ALGORITHM=COPY. The column is short and unindexed, but
-- the rebuild cost is the table's, so schedule accordingly.
--
-- schema.sql stays engine-neutral (H2 executes it in tests) and therefore names no
-- collation; this pin is MySQL-only, which is the engine where the divergence exists.

ALTER TABLE user_table_row
    MODIFY COLUMN entity_type VARCHAR (128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_ci DEFAULT NULL;
