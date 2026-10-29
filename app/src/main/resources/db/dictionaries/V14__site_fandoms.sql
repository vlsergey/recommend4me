-- A fandom of a site — the site's own key of it — and a universe of the dictionary its works are of,
-- by the user's word: every work of the fandom is drafted with the universe. A fandom of several
-- universes a row each; a fandom bound to none has no row
CREATE TABLE site_fandom (
    source      VARCHAR(100)             NOT NULL,
    fandom      VARCHAR(500)             NOT NULL,
    catalogue   VARCHAR(100)             NOT NULL,
    universe_id VARCHAR(100)             NOT NULL,
    bound_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (source, fandom, catalogue, universe_id),
    FOREIGN KEY (catalogue, universe_id) REFERENCES universe (catalogue, universe_id) ON DELETE CASCADE
);
