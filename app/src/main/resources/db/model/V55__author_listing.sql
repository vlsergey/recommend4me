-- The normalized authors of works as the last search of the authors' duplicates read them (owner, 2026-11-20: the tab
-- of all the authors reads no more than its page): of every author how many works they have, whether several authors
-- of the sites are merged into them, whether they are bound to an entry of a catalogue, and their names on the sites
-- and of their entries, lowercase, what a name is looked for in. An author merged into another goes at once, till the
-- next search
CREATE TABLE author_listing (
    author     BIGINT        NOT NULL PRIMARY KEY,
    works      INTEGER       NOT NULL,
    merged     BOOLEAN       NOT NULL,
    catalogued BOOLEAN       NOT NULL,
    names      VARCHAR(4000) NOT NULL
);

-- The authors in the order the tab shows them: the most works first
CREATE INDEX author_listing_works ON author_listing (works DESC, author);

-- The last search let go: the start looks for the authors' duplicates anew, as of a type never searched, and fills it
DELETE FROM author_search;
