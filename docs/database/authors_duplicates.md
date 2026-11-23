# The store of the search of the authors' duplicates of a content type: `authors_duplicates`

`types/<type>/authors_duplicates.duckdb` — a DuckDB file beside the works' duplicates ([work_duplicates](work_duplicates.md);
owner, 2026-11-24: "we redo it on DuckDB, the file beside the works' duplicates"), made by its one script
`app/src/main/resources/author/store.sql` and made anew, empty, as its tables are other than the script makes
(`DuckDatabase`): everything in it is made again by the next search, the user's words on the authors kept in the
dictionaries (`AuthorDistinctions`). The pairs and the last search are written in one transaction by every search,
the pairs appended in the order of their key, no index nor key of them kept: a page is a pass of the columns of its filter and its sort.

| Table | What it holds |
|---|---|
| `author_candidate` | two normalized authors who may be one, as the last search of the authors' duplicates found them — the smaller id first (`first_author`, `other_author`): whether they are bound to one entry of a catalogue (`one_entry`), how many pairs of their works the user said are one (`matching`), the chances of the rest summed (`expected`), the sites of their works (`sites`, ",a,b,"), and the user's word on them (`answer`: DIFFERENT, PARTIAL, none of an open pair) — what a page of the offers is chosen of in the store; a pair answered struck out at once, till the next search |
| `author_search` | the last search, one row of id 1: when the authors' duplicates were looked for (`made_at`), of how many works (`works`); none of a type never searched — its start looks for them |
