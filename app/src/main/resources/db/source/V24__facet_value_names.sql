-- The values of a facet by their names: a value a site gave by its name alone is the one of that
-- name the site gives a key of, unless two keys have the name (item/NamedValues.kt)
CREATE INDEX facet_value_name ON facet_value (facet, name);
