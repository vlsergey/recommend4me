-- When a part was taken off its site: a whole table of contents read no longer named it (part.PartRepository.save). Its
-- text is kept, the screen of the work shows it crossed out, and nothing of the work is made of it — its words, its
-- vectors, the search; named again, it is the work's again. Null — on the site
ALTER TABLE part ADD COLUMN taken_off_at TIMESTAMP WITH TIME ZONE;
