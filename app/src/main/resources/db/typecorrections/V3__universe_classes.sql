-- WHAT THE ENTRIES OF A UNIVERSE ARE: a universe of the catalogue holds its characters and also its
-- places, groups, spells and things. Each entry keeps the classes the catalogue gave it, each class
-- whether its entries are characters; the user keeps of a universe what they choose, the characters
-- unless they say otherwise.

-- The classes of every entry of a universe, as the catalogue gave them with the entry
CREATE TABLE universe_character_class (
    catalogue    VARCHAR(100) NOT NULL,
    universe_id  VARCHAR(100) NOT NULL,
    character_id VARCHAR(100) NOT NULL,
    class_id     VARCHAR(100) NOT NULL,
    PRIMARY KEY (catalogue, universe_id, character_id, class_id),
    FOREIGN KEY (catalogue, universe_id, character_id) REFERENCES universe_character (catalogue, universe_id, character_id) ON DELETE CASCADE
);

-- Every class the entries of the dictionary are of: its name, and whether its entries are characters
CREATE TABLE universe_class (
    catalogue    VARCHAR(100)  NOT NULL,
    class_id     VARCHAR(100)  NOT NULL,
    name         VARCHAR(1000) NOT NULL,
    is_character BOOLEAN       NOT NULL,
    PRIMARY KEY (catalogue, class_id)
);

-- The user's word on a class within a universe: its entries are kept, or left out. Kept over a refresh
CREATE TABLE universe_class_choice (
    catalogue   VARCHAR(100) NOT NULL,
    universe_id VARCHAR(100) NOT NULL,
    class_id    VARCHAR(100) NOT NULL,
    included    BOOLEAN      NOT NULL,
    PRIMARY KEY (catalogue, universe_id, class_id),
    FOREIGN KEY (catalogue, universe_id) REFERENCES universe (catalogue, universe_id) ON DELETE CASCADE
);

-- The user's word on one entry, over its classes: kept, or left out. Kept over a refresh
CREATE TABLE universe_character_choice (
    catalogue    VARCHAR(100) NOT NULL,
    universe_id  VARCHAR(100) NOT NULL,
    character_id VARCHAR(100) NOT NULL,
    included     BOOLEAN      NOT NULL,
    PRIMARY KEY (catalogue, universe_id, character_id),
    FOREIGN KEY (catalogue, universe_id) REFERENCES universe (catalogue, universe_id) ON DELETE CASCADE
);
