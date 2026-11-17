-- The values the model gives every work of a facet it works out, beyond the site's own — what the filters of the list
-- read, no model run at a request (user, 2026-11-17): written by every pass of the models of a source's facets, of the
-- works whose values changed
CREATE TABLE model_value (
    source    VARCHAR(100) NOT NULL,
    item_id   VARCHAR(200) NOT NULL,
    facet     VARCHAR(100) NOT NULL,
    value_key VARCHAR(500) NOT NULL,
    PRIMARY KEY (source, facet, item_id, value_key)
);
CREATE INDEX model_value_item ON model_value (source, item_id);
