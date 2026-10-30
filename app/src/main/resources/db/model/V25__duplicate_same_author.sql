-- Whether the dictionary of authors gives the two works of a pair one author, fully or in part
ALTER TABLE duplicate_candidate ADD COLUMN same_author BOOLEAN DEFAULT FALSE NOT NULL;
