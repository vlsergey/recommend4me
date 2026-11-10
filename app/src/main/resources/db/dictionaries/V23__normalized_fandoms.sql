-- THE UNIVERSES ARE NORMALIZED FANDOMS: every fandom of every site — the site's own key of it — is bound to
-- exactly one normalized fandom, at first the one of its name, then as the user merges and takes out; a
-- normalized fandom is a universe a work may be of, its value of the facet "universe" its id. It may be
-- bound to entries of a catalogue (Wikidata), which name it; the characters of its entries are read and
-- given to its works only when the user said so (characters).

CREATE SEQUENCE fandom_id;

-- A normalized fandom: the name the user gave it, if any; whether the characters of its entries are read;
-- the name of its sites' fandoms of the most works, worked out of the rest — what it is shown by without
-- a name of the user's or of an entry
CREATE TABLE fandom (
    id              BIGINT DEFAULT NEXT VALUE FOR fandom_id PRIMARY KEY,
    name            VARCHAR(1000),
    characters      BOOLEAN DEFAULT FALSE        NOT NULL,
    calculated_name VARCHAR(1000),
    made_at         TIMESTAMP WITH TIME ZONE     NOT NULL
);

-- Every fandom of every site and the normalized fandom it is
CREATE TABLE fandom_site (
    source   VARCHAR(100)             NOT NULL,
    site_key VARCHAR(500)             NOT NULL,
    fandom   BIGINT                   NOT NULL REFERENCES fandom (id),
    bound_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (source, site_key)
);
CREATE INDEX fandom_site_fandom ON fandom_site (fandom);

-- An entry of a catalogue a normalized fandom is bound to — as the catalogue gave it, in "universe"
CREATE TABLE fandom_entry (
    fandom    BIGINT                   NOT NULL,
    catalogue VARCHAR(100)             NOT NULL,
    entry     VARCHAR(100)             NOT NULL,
    bound_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (fandom, catalogue, entry)
);
CREATE INDEX fandom_entry_entry ON fandom_entry (catalogue, entry);

-- A fandom under another: a work of the child is of the parent too
CREATE TABLE fandom_parent (
    child     BIGINT                   NOT NULL REFERENCES fandom (id) ON DELETE CASCADE,
    parent    BIGINT                   NOT NULL REFERENCES fandom (id) ON DELETE CASCADE,
    linked_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (child, parent)
);

-- Two fandoms the user told apart: never offered as one again; the smaller id first
CREATE TABLE fandom_distinct (
    first_fandom BIGINT                   NOT NULL REFERENCES fandom (id) ON DELETE CASCADE,
    other_fandom BIGINT                   NOT NULL REFERENCES fandom (id) ON DELETE CASCADE,
    said_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (first_fandom, other_fandom)
);

-- Every universe of the dictionary a normalized fandom of its own, bound to it, its characters read
INSERT INTO fandom_entry (fandom, catalogue, entry, bound_at)
SELECT NEXT VALUE FOR fandom_id, catalogue, universe_id, added_at FROM universe;

INSERT INTO fandom (id, characters, made_at)
SELECT fandom, TRUE, bound_at FROM fandom_entry;

ALTER TABLE fandom_entry ADD FOREIGN KEY (fandom) REFERENCES fandom (id) ON DELETE CASCADE;
ALTER TABLE fandom_entry ADD FOREIGN KEY (catalogue, entry) REFERENCES universe (catalogue, universe_id) ON DELETE CASCADE;

-- The sites' fandoms the user bound to a universe, of its normalized fandom; the others bound on start by
-- their names
INSERT INTO fandom_site (source, site_key, fandom, bound_at)
SELECT s.source, s.fandom, e.fandom, s.bound_at
FROM site_fandom s
JOIN fandom_entry e ON e.catalogue = s.catalogue AND e.entry = s.universe_id;

DROP TABLE site_fandom;

-- The user's answers on the universes of the works, of the universes' normalized fandoms
UPDATE work_facet_correction c
SET value_key = (SELECT CAST(e.fandom AS VARCHAR) FROM fandom_entry e WHERE c.value_key = e.catalogue || ':' || e.entry)
WHERE c.facet = 'universe'
  AND EXISTS (SELECT 1 FROM fandom_entry e WHERE c.value_key = e.catalogue || ':' || e.entry);

UPDATE work_facet_unknown c
SET value_key = (SELECT CAST(e.fandom AS VARCHAR) FROM fandom_entry e WHERE c.value_key = e.catalogue || ':' || e.entry)
WHERE c.facet = 'universe'
  AND EXISTS (SELECT 1 FROM fandom_entry e WHERE c.value_key = e.catalogue || ':' || e.entry);
