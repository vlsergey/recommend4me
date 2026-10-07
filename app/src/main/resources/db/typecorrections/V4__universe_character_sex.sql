-- The sex or gender of a character as the catalogue gives it: MALE, FEMALE, OTHER; null when it
-- does not say, and for a universe not refreshed since
ALTER TABLE universe_character ADD COLUMN sex VARCHAR(20);
