-- How long a part's text is, its characters and words counted once; none till counted
ALTER TABLE part ADD COLUMN chars INT;
ALTER TABLE part ADD COLUMN words INT;
