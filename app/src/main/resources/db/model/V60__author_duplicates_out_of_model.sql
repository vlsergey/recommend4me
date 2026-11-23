-- The pairs of authors of the search of the authors' duplicates and its last search moved out of the model's file into a
-- store of their own (author.AuthorCandidateRepository, a DuckDB file beside the works' duplicates: owner, 2026-11-24),
-- made again by the next search: the user's words on the authors are kept in the dictionaries, nothing here is the only
-- copy of them. The store new, the start looks for the authors' duplicates anew, as of a type never searched
DROP TABLE author_candidate;
DROP TABLE author_search;
