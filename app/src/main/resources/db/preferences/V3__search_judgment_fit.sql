-- How far a work answers a line searched for: FITS, PARTLY, NOT - a work that answers it in part told apart from one
-- that answers it whole and one that does not at all (user, 2026-11-13). The words said before, fitting or not, are
-- FITS and NOT
ALTER TABLE search_judgment ADD COLUMN fit VARCHAR(10);
UPDATE search_judgment SET fit = CASE WHEN relevant THEN 'FITS' ELSE 'NOT' END;
ALTER TABLE search_judgment ALTER COLUMN fit SET NOT NULL;
ALTER TABLE search_judgment DROP COLUMN relevant;
