-- The fandoms of the site by their names, each with its archive's path, "anime/Naruto": as the pages link them. One
-- name may be of two sections ("Pokémon" of anime and of games): a row each
CREATE TABLE ffn_fandom (
    name VARCHAR(500) NOT NULL,
    path VARCHAR(500) NOT NULL,
    PRIMARY KEY (name, path)
);
