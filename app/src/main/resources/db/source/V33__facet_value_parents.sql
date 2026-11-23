-- The values a site puts one of its facet's values under — the metatags of a fandom of AO3, "… & Related Fandoms" —
-- as the site's page of the value says: the universe of the value put under the universe of the one above it
-- (universe.SiteFandomWords; owner, 2026-11-23)
CREATE TABLE facet_value_parent (
    facet      VARCHAR(100) NOT NULL,
    value_key  VARCHAR(500) NOT NULL,
    parent_key VARCHAR(500) NOT NULL,
    PRIMARY KEY (facet, value_key, parent_key)
);
CREATE INDEX facet_value_parent_of ON facet_value_parent (facet, parent_key);
