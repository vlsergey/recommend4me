-- THE DICTIONARY OF UNIVERSES the user agreed on, with their characters as the catalogue (Wikidata)
-- gave them when the universe was added or last refreshed. The works are linked to a universe by
-- the facet "universe" of their sources, as any other value of a facet.

CREATE TABLE universe (
    catalogue    VARCHAR(100)             NOT NULL,
    universe_id  VARCHAR(100)             NOT NULL,
    name         VARCHAR(1000)            NOT NULL,
    description  VARCHAR(2000),
    url          VARCHAR(2000)            NOT NULL,
    added_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    refreshed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (catalogue, universe_id)
);

-- names: every name the character goes by, the main one first, one per line
CREATE TABLE universe_character (
    catalogue    VARCHAR(100)  NOT NULL,
    universe_id  VARCHAR(100)  NOT NULL,
    character_id VARCHAR(100)  NOT NULL,
    names        CLOB          NOT NULL,
    description  VARCHAR(2000),
    url          VARCHAR(2000) NOT NULL,
    PRIMARY KEY (catalogue, universe_id, character_id),
    FOREIGN KEY (catalogue, universe_id) REFERENCES universe (catalogue, universe_id) ON DELETE CASCADE
);
