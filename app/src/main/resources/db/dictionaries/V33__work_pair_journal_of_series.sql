-- A word "different" the application took back, the two works being of one series — by the sites, or by the user's
-- words "one series" —: told apart in the journal from a word the user took back
ALTER TABLE work_pair_journal ADD COLUMN of_series BOOLEAN DEFAULT FALSE NOT NULL;
