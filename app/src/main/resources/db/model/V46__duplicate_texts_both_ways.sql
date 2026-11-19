-- Every text of a pair compared both by meaning and letter by letter, and the authors told one fully or in part (owner,
-- 2026-11-19: "for every text field both the trigram ones and the vector ones, shown and given to the model of pairs";
-- "for the author a flag of a full or a partial match"; "trigrams also of the site's universe and the site's characters"):
-- the cosine of the vectors of their titles, of the trigrams of their descriptions, of the names the sites give their
-- fandoms and of the sites' lines of their characters — none of a pair of either without one — and whether the dictionary gives the two an author in part
-- one with the other's, none in common. Of the candidates of an earlier search none, and "same_author" of them of either:
-- the next search writes them.
ALTER TABLE duplicate_candidate ADD COLUMN title_vector REAL;
ALTER TABLE duplicate_candidate ADD COLUMN description_letters REAL;
ALTER TABLE duplicate_candidate ADD COLUMN fandom_letters REAL;
ALTER TABLE duplicate_candidate ADD COLUMN character_letters REAL;
ALTER TABLE duplicate_candidate ADD COLUMN partly_same_author BOOLEAN DEFAULT FALSE NOT NULL;
