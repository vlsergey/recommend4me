-- A pairing the user made of two characters of the dictionary ("pair:<character>|<character>"),
-- known before any work is said to have it: the works of its universes marked by it
CREATE TABLE pairing (
    pairing_key VARCHAR(500)             NOT NULL PRIMARY KEY,
    made_at     TIMESTAMP WITH TIME ZONE NOT NULL
);
