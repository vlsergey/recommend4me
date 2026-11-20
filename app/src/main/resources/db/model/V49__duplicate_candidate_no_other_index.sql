-- No index of the candidates by their second work: the one reading of them by a work's key — the pairs among the
-- works of two works' authors (AuthorKinship) — takes the pairs both of whose works are among them, found by the
-- first; the index cost every search 9 to 16 s of its writing (2026-11-20)
DROP INDEX IF EXISTS duplicate_candidate_other;
