-- How rare every value two works are compared by is among every work of the type, as the last search of
-- duplicates weighed it — ln(works / works having it) — by the value's name ("characters:wikidata:Q…",
-- "author:12"): what a pair the search did not keep is compared by, worked out as it is shown. Made anew whole
-- by every search, with its candidates
CREATE TABLE duplicate_value (
    name   VARCHAR(1000) NOT NULL PRIMARY KEY,
    rarity REAL          NOT NULL
);
