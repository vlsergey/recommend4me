-- The characters of an item's kept texts by their classes (part.CharClasses): letters and digits of one byte in UTF-8,
-- of two, of three or more; punctuation of one byte, of more; spaces; line breaks; the rest — every part's summed as
-- its parts are written, what a site's count of characters is told of; filled for the texts kept before by V28
ALTER TABLE item ADD COLUMN text_letters_1 INTEGER;
ALTER TABLE item ADD COLUMN text_letters_2 INTEGER;
ALTER TABLE item ADD COLUMN text_letters_3 INTEGER;
ALTER TABLE item ADD COLUMN text_punctuation_1 INTEGER;
ALTER TABLE item ADD COLUMN text_punctuation_2 INTEGER;
ALTER TABLE item ADD COLUMN text_spaces INTEGER;
ALTER TABLE item ADD COLUMN text_breaks INTEGER;
ALTER TABLE item ADD COLUMN text_other INTEGER;
