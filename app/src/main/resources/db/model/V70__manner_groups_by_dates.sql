-- The manners of the authors and of the series made anew by the dates of the lists of their works (manner
-- .GroupMannerChanges), not by the items they were last made of: manner_member goes. The manners of the series kept by
-- the keys they had before the series were normalized ("ficbook/28510", "said-12") go too: every series is made again
-- by its number, its list of works dated as it was made (dictionaries V45)
DROP TABLE manner_member;
DELETE FROM group_manner WHERE kind = 'SERIES';
