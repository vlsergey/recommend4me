-- The places on the web an author's page on a site lists as theirs ("Места в сети" of fanfics.me), as the page showed
-- them last; of an address that is an author's page of another source, that author — the same person
CREATE TABLE site_author_place (
    source       VARCHAR(100)             NOT NULL,
    author       VARCHAR(500)             NOT NULL,
    url          VARCHAR(2000)            NOT NULL,
    place_source VARCHAR(100),
    place_author VARCHAR(500),
    said_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (source, author, url)
);

-- Why an author of a site is with the authors of the other sites of their normalized author: none — the author it was
-- made of; USER — the user merged them; PLACE — the page of the author of the site merged_by_source and the key
-- merged_by_author listed theirs as a place of theirs
ALTER TABLE site_author ADD COLUMN merged_by VARCHAR(10);
ALTER TABLE site_author ADD COLUMN merged_by_source VARCHAR(100);
ALTER TABLE site_author ADD COLUMN merged_by_author VARCHAR(500);

-- Every merge so far was the user's: of a normalized author of several, every author of a site but the one it was made
-- of, bound to it later
UPDATE site_author s
SET merged_by = 'USER'
WHERE s.bound_at > (SELECT MIN(o.bound_at) FROM site_author o WHERE o.normalized = s.normalized);
