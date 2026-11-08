-- A series of the site — a cycle of works — as its own page, or a list of an author's series, says
-- it: its key (the key of the value of the facet of the series that names it), its page, its title,
-- description, status (a standard status of the works) and dates; a field the page does not show is
-- null. A series whose page was never seen has no row: its works name it by their facet alone
CREATE TABLE series (
    series_key  VARCHAR(200)             NOT NULL PRIMARY KEY,
    url         VARCHAR(1000)            NOT NULL,
    title       VARCHAR(1000),
    description CLOB,
    status      VARCHAR(20),
    begun_at    TIMESTAMP WITH TIME ZONE,
    updated_at  TIMESTAMP WITH TIME ZONE,
    seen_at     TIMESTAMP WITH TIME ZONE NOT NULL
);

-- The number of a work in a series of the site, as the site tells it: the series' page, its list,
-- or the work's own page ("#3", "Part 3 of"). That the work is of the series is its facet of the
-- series; this is only its place there
CREATE TABLE series_place (
    series_key VARCHAR(200) NOT NULL,
    item_id    VARCHAR(200) NOT NULL REFERENCES item (item_id) ON DELETE CASCADE,
    position   INT          NOT NULL,
    PRIMARY KEY (series_key, item_id)
);
CREATE INDEX series_place_item ON series_place (item_id);
