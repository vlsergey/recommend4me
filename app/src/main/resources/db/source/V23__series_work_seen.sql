-- When a work was last seen made a work of the series other than by the series' own page: of a work's page, a card,
-- a list — the series' page read before then (seen_at) did not list it, and is to be visited again. Of the works of
-- before, the first capture of the newest of them: its own page or card is where it was met
ALTER TABLE series ADD COLUMN work_seen_at TIMESTAMP WITH TIME ZONE;
UPDATE series s SET work_seen_at = (
    SELECT MAX(i.first_seen_at) FROM item_facet f JOIN item i ON i.item_id = f.item_id
    WHERE f.facet = 'series' AND f.value_key = s.series_key
);
